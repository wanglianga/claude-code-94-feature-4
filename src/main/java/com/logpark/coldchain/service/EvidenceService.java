package com.logpark.coldchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多方补证与复盘。
 *
 * 角色证据限定：承运商=设备原始日志/停靠说明；园区=闸口/卸货记录；质检=复检结论；
 * 客户=时限内收货决定（继续收货/降级收货/拒收，支持部分可收部分拒收）。
 * 任何补证只新增版本、不覆盖原始数据与历史版本（版本号、提交人、时间全留痕）；
 * 证据被替换时撤销未生效的结算草稿并重新计算。
 *
 * 复盘规则：承运商补传设备原始日志后，系统自动区分传感器离线/真实超温；
 * 客户决定与质检结论冲突时工单自动升级（审批链追加质检复核环节）。
 */
@Service
public class EvidenceService {

    private static final Map<String, List<String>> ROLE_EVIDENCE_TYPES = Map.of(
            "CARRIER", List.of("DEVICE_LOG", "STOP_NOTE"),
            "DISPATCH", List.of("GATE_RECORD", "UNLOAD_RECORD"),
            "QC", List.of("QC_RECHECK"));

    private final Db db;
    private final PayoutService payoutService;
    private final ObjectMapper om = new ObjectMapper();

    public EvidenceService(Db db, PayoutService payoutService) {
        this.db = db;
        this.payoutService = payoutService;
    }

    // ---------------- 补证（版本化，只增不改） ----------------

    /**
     * 提交补证。body: { evidenceType, content }
     * 同（工单,角色,类型）新版本自动 +1，旧版本置为被替换（保留不删除）。
     * 证据被替换 → 撤销未生效结算草稿并重算。
     */
    @Transactional
    public Map<String, Object> submitEvidence(long workOrderId, Map<String, Object> body, String username) {
        Map<String, Object> user = user(username);
        String role = (String) user.get("role");
        List<String> allowed = ROLE_EVIDENCE_TYPES.get(role);
        if (allowed == null) {
            throw ApiException.conflict("当前角色无权补证（仅承运商/园区/质检可提交证据，客户请走收货决定）");
        }
        String type = String.valueOf(body.get("evidenceType")).toUpperCase();
        if (!allowed.contains(type)) {
            throw ApiException.badRequest("角色 " + role + " 仅可提交 " + allowed + "，不允许 " + type);
        }
        String content = String.valueOf(require(body, "content"));
        Map<String, Object> wo = workOrder(workOrderId);
        // 结案后仍允许补充/替换证据（只增版本不改历史），由重算机制撤销未生效结算草稿
        String storeRole = "DISPATCH".equals(role) ? "PARK" : role;

        // 咨询锁串行化同（工单,角色,类型）的并发补证：版本号唯一且连续，不产生重复版本
        db.lock("ev:" + workOrderId + ":" + storeRole + ":" + type);
        Map<String, Object> ev = insertVersion(workOrderId, storeRole, type, content, username);
        long tripId = ((Number) wo.get("trip_id")).longValue();
        int version = ((Number) ev.get("version")).intValue();
        db.event(tripId, "EVIDENCE_SUBMITTED", storeRole, username,
                "工单#" + workOrderId + " 收到补证 " + type + " v" + version
                        + (version > 1 ? "（替换旧版本，未生效结算草稿已撤销并重算）" : ""));

        // 承运商设备原始日志 → 自动区分传感器离线/真实超温
        if ("DEVICE_LOG".equals(type)) {
            autoClassify(tripId, content, username);
        }
        // 证据替换（或新增证据）→ 未生效结算草稿撤销并重算
        payoutService.computeForTrip(tripId, username);
        return ev;
    }

    /** 版本插入：咨询锁保证并发下版本号唯一连续；唯一约束兜底。 */
    private Map<String, Object> insertVersion(long woId, String role, String type,
                                              String content, String username) {
        try {
            return doInsertVersion(woId, role, type, content, username);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("并发补证冲突，请重试");
        }
    }

    private Map<String, Object> doInsertVersion(long woId, String role, String type,
                                                String content, String username) {
        List<Map<String, Object>> prev = db.jdbc().queryForList("""
                SELECT id, version FROM evidence
                WHERE work_order_id = ? AND role = ? AND evidence_type = ? AND active = TRUE
                """, woId, role, type);
        int nextVersion = 1;
        Long supersedes = null;
        if (!prev.isEmpty()) {
            nextVersion = ((Number) prev.get(0).get("version")).intValue() + 1;
            supersedes = ((Number) prev.get(0).get("id")).longValue();
            db.jdbc().update("UPDATE evidence SET active = FALSE WHERE id = ?", supersedes);
        }
        Long id = db.jdbc().queryForObject("""
                INSERT INTO evidence (work_order_id, role, evidence_type, version, content,
                                      submitted_by, submitted_at, supersedes, active)
                VALUES (?,?,?,?,?,?,?,?,TRUE) RETURNING id
                """, Long.class, woId, role, type, nextVersion, content, username,
                Timestamp.valueOf(db.now()), supersedes);
        return db.jdbc().queryForMap("SELECT * FROM evidence WHERE id = ?", id);
    }

    // ---------------- 复盘：传感器离线 vs 真实超温 ----------------

    /**
     * 解析承运商设备原始日志（JSON: {"readings":[{"at":"...","celsius":...}]}），
     * 对未分类的超温/断点工单自动判定：
     * 区间内日志温度均不超阈 → SENSOR_OFFLINE（传感器/传输离线，非真实超温）；
     * 存在超阈 → REAL_EXCURSION；无日志覆盖但心跳离线 → SENSOR_OFFLINE。
     */
    private void autoClassify(long tripId, String deviceLogJson, String actor) {
        List<Map<String, Object>> wos = db.jdbc().queryForList("""
                SELECT * FROM work_order
                WHERE trip_id = ? AND type IN ('TEMP_EXCURSION','DATA_GAP')
                  AND classification IS NULL AND status IN ('OPEN','IN_REVIEW','ESCALATED')
                """, tripId);
        if (wos.isEmpty()) return;

        record LogPoint(LocalDateTime t, BigDecimal c) {}
        List<LogPoint> logs = new ArrayList<>();
        try {
            JsonNode root = om.readTree(deviceLogJson);
            for (JsonNode n : root.path("readings")) {
                logs.add(new LogPoint(LocalDateTime.parse(n.path("at").asText()),
                        new BigDecimal(n.path("celsius").asText())));
            }
        } catch (Exception e) {
            db.event(tripId, "CLASSIFY_SKIPPED", "SYSTEM", "系统",
                    "设备原始日志非 JSON 格式（{readings:[{at,celsius}]}），本次不自动分类，待人工复盘");
            return;
        }
        BigDecimal limit = db.jdbc().queryForObject(
                "SELECT MIN(max_temp_celsius) FROM trip_cargo WHERE trip_id = ?", BigDecimal.class, tripId);
        String deviceNo = (String) db.getTripByCode(
                db.jdbc().queryForObject("SELECT code FROM trip WHERE id = ?", String.class, tripId))
                .get("temp_device_no");

        for (Map<String, Object> wo : wos) {
            Timestamp s = (Timestamp) wo.get("anomaly_start");
            Timestamp e = (Timestamp) wo.get("anomaly_end");
            if (s == null || e == null) continue;
            LocalDateTime start = s.toLocalDateTime(), end = e.toLocalDateTime();
            BigDecimal maxC = null;
            for (LogPoint p : logs) {
                if (!p.t().isBefore(start) && !p.t().isAfter(end)) {
                    if (maxC == null || p.c().compareTo(maxC) > 0) maxC = p.c();
                }
            }
            String classification;
            String note;
            if (maxC != null) {
                if (limit != null && maxC.compareTo(limit) > 0) {
                    classification = "REAL_EXCURSION";
                    note = "设备原始日志显示区间内最高 " + maxC + "℃ 超上限 " + limit + "℃，判定真实超温";
                } else {
                    classification = "SENSOR_OFFLINE";
                    note = "设备原始日志显示区间内最高 " + maxC + "℃ 未超上限 " + limit
                            + "℃，判定传感器/传输离线（非真实超温）";
                }
            } else {
                Integer offline = db.jdbc().queryForObject("""
                        SELECT COUNT(*) FROM device_heartbeat
                        WHERE device_no = ? AND online = FALSE AND beat_at BETWEEN ? AND ?
                        """, Integer.class, deviceNo, s, e);
                if (offline != null && offline > 0) {
                    classification = "SENSOR_OFFLINE";
                    note = "区间内设备心跳离线 " + offline + " 次，日志无超温记录，判定传感器离线";
                } else {
                    continue; // 证据不足，保持待分类
                }
            }
            long woId = ((Number) wo.get("id")).longValue();
            db.jdbc().update(
                    "UPDATE work_order SET classification = ?, classify_note = ? WHERE id = ?",
                    classification, note + "（依据设备日志 v 最新补证）", woId);
            db.event(tripId, "CLASSIFIED", "SYSTEM", actor, "工单#" + woId + " " + note);
        }
    }

    /** 人工确认/改判分类（质检或园区调度复盘）。 */
    @Transactional
    public Map<String, Object> classify(long workOrderId, String classification, String note, String actor) {
        Map<String, Object> wo = workOrder(workOrderId);
        if ("RESOLVED".equals(wo.get("status"))) {
            throw ApiException.conflict("工单已结案");
        }
        if (!List.of("SENSOR_OFFLINE", "REAL_EXCURSION", "CONFIRMED", "CLEARED").contains(classification)) {
            throw ApiException.badRequest("分类必须为 SENSOR_OFFLINE/REAL_EXCURSION/CONFIRMED/CLEARED");
        }
        db.jdbc().update(
                "UPDATE work_order SET classification = ?, classify_note = ?, status = 'IN_REVIEW' WHERE id = ?",
                classification, note, workOrderId);
        long tripId = ((Number) wo.get("trip_id")).longValue();
        db.event(tripId, "CLASSIFIED", "QC", actor,
                "工单#" + workOrderId + " 人工复盘分类=" + classification + "，说明=" + note);
        payoutService.computeForTrip(tripId, actor);
        return workOrder(workOrderId);
    }

    // ---------------- 客户收货决定（时限内，部分可收/部分拒收） ----------------

    /**
     * body: { batchId, decision: ACCEPT/DOWNGRADE/REJECT, acceptQty, rejectQty }
     * 客户仅可决定本货主批次；超过时限不可提交；与质检结论冲突自动升级审批。
     */
    @Transactional
    public Map<String, Object> customerDecision(long workOrderId, Map<String, Object> body, String username) {
        Map<String, Object> user = user(username);
        if (user.get("owner_id") == null) {
            throw ApiException.conflict("客户账号未绑定货主");
        }
        long ownerId = ((Number) user.get("owner_id")).longValue();
        Map<String, Object> wo = workOrder(workOrderId);
        if ("RESOLVED".equals(wo.get("status"))) {
            throw ApiException.conflict("工单已结案，不能再提交收货决定");
        }
        long tripId = ((Number) wo.get("trip_id")).longValue();

        // 咨询锁串行化同工单的并发客户决定（决定版本与证据版本唯一）
        db.lock("cd:" + workOrderId);

        // 时限校验
        int hours = db.settingInt("CUSTOMER_DECISION_HOURS", 48);
        LocalDateTime deadline = ((Timestamp) wo.get("created_at")).toLocalDateTime().plusHours(hours);
        if (db.now().isAfter(deadline)) {
            throw ApiException.conflict("已超过客户收货决定时限（" + deadline + "），系统按默认继续收货处理");
        }

        long batchId = ((Number) require(body, "batchId")).longValue();
        Map<String, Object> batch = db.jdbc().queryForMap("""
                SELECT b.*, tc.owner_id, tc.qc_result, tc.disposition AS cargo_disposition
                FROM trip_batch b JOIN trip_cargo tc ON tc.id = b.cargo_id
                WHERE b.id = ? AND b.trip_id = ?
                """, batchId, tripId);
        if (((Number) batch.get("owner_id")).longValue() != ownerId) {
            throw ApiException.conflict("客户仅可对本货主批次提交收货决定");
        }
        String decision = String.valueOf(require(body, "decision")).toUpperCase();
        if (!List.of("ACCEPT", "DOWNGRADE", "REJECT").contains(decision)) {
            throw ApiException.badRequest("decision 必须为 ACCEPT（继续收货）/DOWNGRADE（降级收货）/REJECT（拒收）");
        }
        int acceptQty = ((Number) require(body, "acceptQty")).intValue();
        int rejectQty = ((Number) require(body, "rejectQty")).intValue();
        int expected = ((Number) batch.get("expected_qty")).intValue();
        if (acceptQty < 0 || rejectQty < 0 || acceptQty + rejectQty != expected) {
            throw ApiException.badRequest("可收+拒收必须等于批次预约数量 " + expected
                    + "（支持部分可收/部分拒收），当前=" + acceptQty + "+" + rejectQty);
        }
        if ("ACCEPT".equals(decision) && rejectQty > 0) {
            throw ApiException.badRequest("继续收货（ACCEPT）时拒收数量必须为 0；部分拒收请选择 REJECT");
        }
        if (List.of("DOWNGRADE", "REJECT").contains(decision) && rejectQty == 0) {
            throw ApiException.badRequest("降级收货/拒收时拒收数量必须大于 0");
        }

        // 版本化决定（同工单递增），旧决定失效但保留
        int version = db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM customer_decision WHERE work_order_id = ?",
                Integer.class, workOrderId);
        db.jdbc().update(
                "UPDATE customer_decision SET active = FALSE WHERE work_order_id = ? AND batch_id = ? AND active = TRUE",
                workOrderId, batchId);
        Long id = db.jdbc().queryForObject("""
                INSERT INTO customer_decision (work_order_id, batch_id, decision, accept_qty, reject_qty,
                                               decided_by, decided_at, deadline_at, version, active)
                VALUES (?,?,?,?,?,?,?,?,?,TRUE) RETURNING id
                """, Long.class, workOrderId, batchId, decision, acceptQty, rejectQty,
                username, Timestamp.valueOf(db.now()), Timestamp.valueOf(deadline), version);
        db.jdbc().update(
                "UPDATE trip_batch SET accept_qty = ?, reject_qty = ? WHERE id = ?",
                acceptQty, rejectQty, batchId);
        // 同步留痕到补证链（客户证据）
        insertVersion(workOrderId, "CUSTOMER", "CUSTOMER_DECISION",
                String.format("{\"batchId\":%d,\"decision\":\"%s\",\"acceptQty\":%d,\"rejectQty\":%d}",
                        batchId, decision, acceptQty, rejectQty), username);

        // 客户决定 vs 质检结论冲突 → 升级审批
        String qcView = qcConclusion(workOrderId, batch);
        boolean qcBad = "THAWED".equals(qcView);
        boolean qcGood = "PASS".equals(qcView);
        boolean conflict = (qcGood && "REJECT".equals(decision)) || (qcBad && "ACCEPT".equals(decision));
        if (conflict) {
            db.jdbc().update("UPDATE work_order SET status = 'ESCALATED', escalated = TRUE WHERE id = ? AND status <> 'RESOLVED'",
                    workOrderId);
            db.event(tripId, "ESCALATED", "SYSTEM", "系统",
                    "工单#" + workOrderId + " 客户决定（" + decision + "）与质检结论（" + qcView
                            + "）冲突，升级审批（审批链追加质检复核环节）");
        }
        db.event(tripId, "CUSTOMER_DECIDED", "CUSTOMER", username,
                "工单#" + workOrderId + " 客户决定=" + decision + "，可收 " + acceptQty + " / 拒收 " + rejectQty
                        + "（批次 " + batch.get("batch_code") + "，时限 " + deadline + " 前）");

        payoutService.computeForTrip(tripId, username);
        Map<String, Object> out = db.jdbc().queryForMap("SELECT * FROM customer_decision WHERE id = ?", id);
        out.put("qcConclusion", qcView);
        out.put("escalated", conflict);
        return out;
    }

    /** 质检结论：优先取本工单最新有效复检结论，否则取货品初检结果。 */
    private String qcConclusion(long workOrderId, Map<String, Object> batch) {
        List<String> recheck = db.jdbc().queryForList("""
                SELECT content FROM evidence
                WHERE work_order_id = ? AND evidence_type = 'QC_RECHECK' AND active = TRUE
                ORDER BY version DESC LIMIT 1
                """, String.class, workOrderId);
        if (!recheck.isEmpty()) {
            try {
                String result = om.readTree(recheck.get(0)).path("result").asText("");
                if (!result.isBlank()) return result.toUpperCase();
            } catch (Exception ignored) {
                // 非 JSON 复检内容，回落到初检结论
            }
        }
        String r = (String) batch.get("qc_result");
        if (r != null) return r;
        String disp = (String) batch.get("cargo_disposition");
        return disp == null ? "UNKNOWN" : disp;
    }

    // ---------------- 结案（解冻 + 触发赔付计算） ----------------

    /**
     * 工单结案：分类齐全、相关批次均有客户决定（或时限已过按默认继续收货）后，
     * 解除对应冻结并触发赔付候选重算。
     */
    @Transactional
    public Map<String, Object> resolve(long workOrderId, String responsibility, String note, String actor) {
        Map<String, Object> wo = workOrder(workOrderId);
        if ("RESOLVED".equals(wo.get("status"))) {
            throw ApiException.conflict("工单已结案");
        }
        if (responsibility == null
                || !List.of("CARRIER", "PARK", "OWNER", "SHARED").contains(responsibility.toUpperCase())) {
            throw ApiException.badRequest("结案必须给定责任归属 CARRIER/PARK/OWNER/SHARED");
        }
        String type = (String) wo.get("type");
        if (List.of("TEMP_EXCURSION", "DATA_GAP").contains(type) && wo.get("classification") == null) {
            throw ApiException.conflict("温度/断点工单须先完成传感器离线/真实超温分类（补传设备日志或人工复盘）");
        }
        long tripId = ((Number) wo.get("trip_id")).longValue();

        // 该工单冻结的批次：须均有客户决定；时限已过未决定的按默认继续收货兜底
        List<Map<String, Object>> freezes = db.jdbc().queryForList("""
                SELECT f.*, b.expected_qty, b.batch_code FROM settlement_freeze f
                JOIN trip_batch b ON b.id = f.batch_id
                WHERE f.work_order_id = ? AND f.status = 'FROZEN'
                """, workOrderId);
        int hours = db.settingInt("CUSTOMER_DECISION_HOURS", 48);
        LocalDateTime deadline = ((Timestamp) wo.get("created_at")).toLocalDateTime().plusHours(hours);
        for (Map<String, Object> f : freezes) {
            long batchId = ((Number) f.get("batch_id")).longValue();
            Integer decided = db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM customer_decision WHERE batch_id = ? AND active = TRUE",
                    Integer.class, batchId);
            if (decided != null && decided > 0) continue;
            if (db.now().isAfter(deadline)) {
                defaultDecision(workOrderId, batchId, ((Number) f.get("expected_qty")).intValue(), deadline);
            } else {
                throw ApiException.conflict("批次 " + f.get("batch_code")
                        + " 客户尚未在时限内（" + deadline + " 前）提交收货决定，不能结案");
            }
        }

        db.jdbc().update("""
                UPDATE work_order SET status = 'RESOLVED', responsibility = ?, review_note = ?, resolved_at = ?
                WHERE id = ?
                """, responsibility.toUpperCase(), note, Timestamp.valueOf(db.now()), workOrderId);
        int released = db.jdbc().update("""
                UPDATE settlement_freeze SET status = 'RELEASED', released_at = ?, release_note = ?
                WHERE work_order_id = ? AND status = 'FROZEN'
                """, Timestamp.valueOf(db.now()), "工单#" + workOrderId + " 结案解冻", workOrderId);
        db.event(tripId, "WORK_ORDER_RESOLVED", "DISPATCH", actor,
                "工单#" + workOrderId + " 结案：责任=" + responsibility.toUpperCase()
                        + "，解冻批次 " + released + " 个。复盘说明=" + note);

        payoutService.computeForTrip(tripId, actor);
        return workOrder(workOrderId);
    }

    /** 时限已过客户未决定 → 系统默认继续收货（留痕）。 */
    private void defaultDecision(long workOrderId, long batchId, int expectedQty, LocalDateTime deadline) {
        int version = db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM customer_decision WHERE work_order_id = ?",
                Integer.class, workOrderId);
        db.jdbc().update(
                "UPDATE customer_decision SET active = FALSE WHERE work_order_id = ? AND batch_id = ? AND active = TRUE",
                workOrderId, batchId);
        db.jdbc().update("""
                INSERT INTO customer_decision (work_order_id, batch_id, decision, accept_qty, reject_qty,
                                               decided_by, decided_at, deadline_at, version, active)
                VALUES (?,?, 'ACCEPT', ?, 0, 'SYSTEM', ?, ?, ?, TRUE)
                """, workOrderId, batchId, expectedQty, Timestamp.valueOf(db.now()),
                Timestamp.valueOf(deadline), version);
        db.jdbc().update(
                "UPDATE trip_batch SET accept_qty = ?, reject_qty = 0 WHERE id = ?", expectedQty, batchId);
    }

    // ---------------- 查询 ----------------

    public Map<String, Object> workOrder(long id) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("SELECT * FROM work_order WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("工单不存在: " + id);
        }
        return rows.get(0);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> wo = new LinkedHashMap<>(workOrder(id));
        wo.put("evidence", db.jdbc().queryForList(
                "SELECT * FROM evidence WHERE work_order_id = ? ORDER BY id", id));
        wo.put("customerDecisions", db.jdbc().queryForList(
                "SELECT * FROM customer_decision WHERE work_order_id = ? ORDER BY id", id));
        wo.put("freezes", db.jdbc().queryForList(
                "SELECT * FROM settlement_freeze WHERE work_order_id = ?", id));
        return wo;
    }

    public List<Map<String, Object>> list(Long tripId, String status) {
        String sql = "SELECT * FROM work_order WHERE 1=1"
                + (tripId != null ? " AND trip_id = " + tripId : "")
                + (status != null ? " AND status = '" + status.replace("'", "") + "'" : "")
                + " ORDER BY id";
        return db.jdbc().queryForList(sql);
    }

    private Map<String, Object> user(String username) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT username, role, owner_id, carrier_id FROM app_user WHERE username = ?", username);
        if (rows.isEmpty()) {
            throw ApiException.notFound("用户不存在: " + username);
        }
        return rows.get(0);
    }

    private static Object require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw ApiException.badRequest("缺少必填字段: " + key);
        }
        return v;
    }
}
