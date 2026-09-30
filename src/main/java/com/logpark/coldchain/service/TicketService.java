package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常工单的并行补证与客户决定。
 *
 * 关键规则：
 * 1) 四方只能提交本角色证据：承运商 DEVICE_RAW_LOG/DWELLING_NOTE、园区 GATE_RECORD/UNLOAD_RECORD、
 *    质检 QC_RECHECK、客户 CUSTOMER_DECISION；
 * 2) 证据只追加：每次提交生成新版本，旧版本置 superseded 但永不删除，原始设备/闸口数据不可被覆盖；
 * 3) 客户在时限内按批次选择 继续收货 ACCEPT / 降级收货 DOWNGRADE / 拒收 REJECT，支持部分可收/部分拒收；
 * 4) 客户决定与质检复检结论冲突 → 自动生成 L1 升级审批，工单转 ESCALATED；
 * 5) 证据被新版本替换（或客户改判）时，撤销该事件下所有未生效结算草稿，由结算重新计算。
 */
@Service
public class TicketService {

    /** 业务补证角色 → 允许的证据类型。 */
    private static final Map<String, List<String>> ALLOWED_TYPES = Map.of(
            "CARRIER", List.of("DEVICE_RAW_LOG", "DWELLING_NOTE"),
            "PARK", List.of("GATE_RECORD", "UNLOAD_RECORD"),
            "QC", List.of("QC_RECHECK"),
            "CUSTOMER", List.of("CUSTOMER_DECISION"));

    private final Db db;
    private final SettlementProposalService proposals;
    private final EscalationService escalations;

    public TicketService(Db db, @Lazy SettlementProposalService proposals,
                         @Lazy EscalationService escalations) {
        this.db = db;
        this.proposals = proposals;
        this.escalations = escalations;
    }

    // ============================================================
    // 证据提交（版本化、只追加）
    // ============================================================

    @Transactional
    public Map<String, Object> addEvidence(String ticketNo, Map<String, Object> body,
                                           String domainRole, String username) {
        Map<String, Object> ticket = requireTicket(ticketNo);
        long ticketId = ((Number) ticket.get("id")).longValue();
        long ceId = ((Number) ticket.get("cold_event_id")).longValue();
        long tripId = tripIdOf(ceId);

        if (List.of("CLOSED", "REJECTED").contains(ticket.get("status")) && !"QC_RECHECK".equals(body.get("evidenceType"))) {
            throw ApiException.conflict("工单已闭环（" + ticket.get("status") + "），不能再补证；如需翻案请走升级审批");
        }
        String type = str(require(body, "evidenceType")).toUpperCase();
        if (!ALLOWED_TYPES.getOrDefault(domainRole, List.of()).contains(type)) {
            throw ApiException.badRequest(domainRole + " 角色只能提交 " + ALLOWED_TYPES.get(domainRole)
                    + "，不能提交 " + type);
        }
        String content = str(require(body, "content"));
        String refUri = body.get("refUri") == null ? null : str(body.get("refUri"));

        // 质检复检可携带逐批次结论（用于与客户决定比对冲突），统一以 JSON 片段追加在文本末尾
        String storedContent = content;
        if ("QC_RECHECK".equals(type) && body.get("conclusions") != null) {
            storedContent = content + " || conclusions=" + com.logpark.coldchain.support.Json.write(body.get("conclusions"));
        }

        Integer maxVer = db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM ticket_evidence WHERE ticket_id = ? AND role = ?",
                Integer.class, ticketId, domainRole);
        int version = (maxVer == null ? 0 : maxVer) + 1;
        db.jdbc().update(
                "UPDATE ticket_evidence SET superseded = TRUE WHERE ticket_id = ? AND role = ?",
                ticketId, domainRole);
        db.jdbc().update("""
                INSERT INTO ticket_evidence (ticket_id, version, role, evidence_type, content, ref_uri,
                    submitted_by, submitted_at, superseded)
                VALUES (?,?,?,?,?,?,?,?,FALSE)
                """, ticketId, version, domainRole, type, storedContent, refUri,
                username, Timestamp.valueOf(db.now()));

        db.event(tripId, "EVIDENCE_ADDED", eventActorRole(domainRole), username,
                "工单 #" + ticketNo + " 收到 " + roleCn(domainRole) + "证据[" + type + "] v" + version
                        + (refUri == null ? "" : "，附件=" + refUri)
                        + "；只追加保存，原始数据不受影响");

        // 质检复检结论落库后：工单进入等待客户决定
        if ("QC_RECHECK".equals(type)) {
            db.jdbc().update(
                    "UPDATE anomaly_ticket SET status = 'AWAIT_CUSTOMER', updated_at = ? WHERE id = ? AND status = 'OPEN'",
                    Timestamp.valueOf(db.now()), ticketId);
        }
        // 证据被替换/新增：撤销未生效结算草稿（v>1 说明在替换旧版本；任何新证据都可能改变事实，统一作废旧草稿）
        if (version > 1 || ticketAffectsSettlement(ticketId)) {
            int voided = proposals.voidNonEffective(ceId,
                    "工单 " + ticketNo + " 出现新证据[" + type + " v" + version + "]，未生效结算草稿作废，需重新计算",
                    username);
            if (voided > 0) {
                db.event(tripId, "PROPOSAL_VOIDED", "SETTLE", username,
                        "因证据更新，" + voided + " 个未生效赔付方案已撤销，等待重新计算");
            }
        }
        return ticketView(ticketNo);
    }

    // ============================================================
    // 客户决定（逐批次，支持部分收/拒，时限控制，冲突升级）
    // ============================================================

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> customerDecision(String ticketNo, Map<String, Object> body, String username) {
        Map<String, Object> ticket = requireTicket(ticketNo);
        long ticketId = ((Number) ticket.get("id")).longValue();
        long ceId = ((Number) ticket.get("cold_event_id")).longValue();
        long tripId = tripIdOf(ceId);

        if ("CLOSED".equals(ticket.get("status"))) {
            throw ApiException.conflict("工单已闭环，客户决定不能再变更（如需变更请发起升级审批）");
        }
        LocalDateTime due = ticket.get("due_at") == null ? null
                : ((Timestamp) ticket.get("due_at")).toLocalDateTime();
        boolean overrideDeadline = Boolean.TRUE.equals(body.get("overrideDeadline"));
        if (due != null && db.now().isAfter(due) && !overrideDeadline) {
            throw ApiException.unprocessable("已超过客户决定时限 " + due + "，需主管延期或升级审批后再决定");
        }

        List<Map<String, Object>> decisions = (List<Map<String, Object>>) require(body, "decisions");
        if (decisions.isEmpty()) {
            throw ApiException.badRequest("decisions 不能为空，需逐批次给出 ACCEPT/DOWNGRADE/REJECT");
        }
        List<String> conflictBatches = new ArrayList<>();
        Map<String, String> qcConclusion = latestQcConclusions(ticketId);

        for (Map<String, Object> d : decisions) {
            String batchNo = str(require(d, "batchNo"));
            String decision = str(require(d, "decision")).toUpperCase();
            if (!List.of("ACCEPT", "DOWNGRADE", "REJECT").contains(decision)) {
                throw ApiException.badRequest("客户决定必须为 ACCEPT/DOWNGRADE/REJECT");
            }
            int accepted = ((Number) d.getOrDefault("acceptedPieces", 0)).intValue();
            if (decision.equals("REJECT")) accepted = 0;
            String note = d.get("note") == null ? null : str(d.get("note"));

            db.jdbc().update("""
                    INSERT INTO customer_decision (ticket_id, batch_no, decision, accepted_pieces, note,
                        decided_by, decided_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (ticket_id, batch_no) DO UPDATE
                    SET decision = EXCLUDED.decision, accepted_pieces = EXCLUDED.accepted_pieces,
                        note = EXCLUDED.note, decided_by = EXCLUDED.decided_by, decided_at = EXCLUDED.decided_at
                    """, ticketId, batchNo, decision, accepted, note,
                    username, Timestamp.valueOf(db.now()));

            String qc = qcConclusion.get(batchNo);
            if (qc != null && !qc.equals(decision)) {
                conflictBatches.add(batchNo + "(质检=" + qcCn(qc) + "/客户=" + choiceCn(decision) + ")");
            }
        }

        // 客户决定本身也是证据（版本化、只追加）
        Integer maxVer = db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM ticket_evidence WHERE ticket_id = ? AND role = 'CUSTOMER'",
                Integer.class, ticketId);
        int evVersion = (maxVer == null ? 0 : maxVer) + 1;
        db.jdbc().update("UPDATE ticket_evidence SET superseded = TRUE WHERE ticket_id = ? AND role='CUSTOMER'",
                ticketId);
        db.jdbc().update("""
                INSERT INTO ticket_evidence (ticket_id, version, role, evidence_type, content, ref_uri,
                    submitted_by, submitted_at, superseded)
                VALUES (?,?,'CUSTOMER','CUSTOMER_DECISION',?,NULL,?,?,FALSE)
                """, ticketId, evVersion, "客户逐批次决定 " + body.get("decisions"),
                username, Timestamp.valueOf(db.now()));

        db.event(tripId, "CUSTOMER_DECISION", "CUSTOMER", username,
                "客户对工单 #" + ticketNo + " 逐批次作出收货决定（v" + evVersion + "）：" + body.get("decisions"));

        if (conflictBatches.isEmpty()) {
            // 与质检不冲突：进入复核（待责任复盘闭环后结算）
            db.jdbc().update(
                    "UPDATE anomaly_ticket SET status = 'REVIEWING', updated_at = ? WHERE id = ?",
                    Timestamp.valueOf(db.now()), ticketId);
            // 曾被驳回的升级：补证重做后结论已一致，重新置为待主管复核
            db.jdbc().update("""
                    UPDATE escalation SET status='PENDING', decided_by=NULL, decision_note=NULL,
                        binding_choice_json=NULL, decided_at=NULL,
                        reason=reason || '；补证重做后客户与质检结论一致，待主管复核确认'
                    WHERE ticket_id=? AND status='REJECTED'
                    """, ticketId);
            db.event(tripId, "DECISION_CONSISTENT", "CUSTOMER", username,
                    "客户决定与质检复检结论一致，工单 #" + ticketNo + " 进入责任复核"
                            + "（若曾被驳回，需主管复核关闭升级链）");
        } else {
            // 冲突 → 自动升级 L1
            long escId = escalations.createIfAbsent(ceId, ticketId, 1,
                    "客户决定与质检复检结论冲突，批次：" + String.join("；", conflictBatches)
                            + "。需运营主管/质量经理裁定按哪方结论收货与赔付");
            db.jdbc().update(
                    "UPDATE anomaly_ticket SET status = 'ESCALATED', updated_at = ? WHERE id = ?",
                    Timestamp.valueOf(db.now()), ticketId);
            db.event(tripId, "ESCALATION_RAISED", "SUPERVISOR", "系统",
                    "客户与质检结论冲突，工单 #" + ticketNo + " 自动升级审批 #" + escId
                            + "，冲突批次：" + String.join("；", conflictBatches));
            proposals.voidNonEffective(ceId,
                    "工单 " + ticketNo + " 客户与质检结论冲突进入升级审批，原赔付候选方案冻结待裁定", username);
        }
        return ticketView(ticketNo);
    }

    // ============================================================
    // 复盘结论（区分传感器离线/真实超温）与工单闭环
    // ============================================================

    /**
     * 质检/调度/主管对温度区间给出复盘结论。
     * body: {conclusion: SENSOR_OFFLINE/REAL_EXCURSION/INCONCLUSIVE,
     *        liabilityParty: CARRIER/PARK/OWNER/SHARED/NONE,
     *        conclusions: [{batchNo, conclusion}], note}
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> reviewConclusion(String ticketNo, Map<String, Object> body, String username) {
        Map<String, Object> ticket = requireTicket(ticketNo);
        long ticketId = ((Number) ticket.get("id")).longValue();
        long ceId = ((Number) ticket.get("cold_event_id")).longValue();
        long tripId = tripIdOf(ceId);
        String conclusion = body.get("conclusion") == null ? null
                : str(body.get("conclusion")).toUpperCase();
        if (conclusion != null && !List.of("SENSOR_OFFLINE", "REAL_EXCURSION", "INCONCLUSIVE").contains(conclusion)) {
            throw ApiException.badRequest("复盘结论必须为 SENSOR_OFFLINE/REAL_EXCURSION/INCONCLUSIVE");
        }
        if (conclusion == null && body.get("liabilityParty") == null) {
            throw ApiException.badRequest("必须提供 conclusion（离线/超温/存疑）或 liabilityParty（责任方）");
        }
        String party = body.get("liabilityParty") == null
                ? inferParty(conclusion, ticket)
                : str(body.get("liabilityParty")).toUpperCase();
        if (!List.of("CARRIER", "PARK", "OWNER", "SHARED", "NONE").contains(party)) {
            throw ApiException.badRequest("责任方必须为 CARRIER/PARK/OWNER/SHARED/NONE");
        }
        String note = body.get("note") == null ? "" : str(body.get("note"));

        Long intervalId = ticket.get("interval_id") == null ? null
                : ((Number) ticket.get("interval_id")).longValue();
        if (intervalId != null && conclusion != null) {
            String intervalConclusion = switch (conclusion) {
                case "SENSOR_OFFLINE" -> "SENSOR_OFFLINE";
                case "REAL_EXCURSION" -> "REAL_EXCURSION";
                default -> "INCONCLUSIVE";
            };
            db.jdbc().update("""
                    UPDATE temp_anomaly_interval
                    SET conclusion = ?, liability_party = ?, status = 'REVIEWING', updated_at = ?
                    WHERE id = ?
                    """, intervalConclusion, "NONE".equals(party) ? null : party,
                    Timestamp.valueOf(db.now()), intervalId);
        }
        db.jdbc().update("UPDATE anomaly_ticket SET responsibility = ?, updated_at = ? WHERE id = ?",
                party, Timestamp.valueOf(db.now()), ticketId);
        db.event(tripId, "REVIEW_CONCLUSION", "QC", username,
                "工单 #" + ticketNo + " 复盘结论=" + (conclusion == null ? "仅定责" : conclusionCn(conclusion))
                        + "，责任=" + party
                        + (note.isBlank() ? "" : "，依据=" + note));

        // 复盘结论也以质检复检证据形式版本化（若调用方是 QC），并携带逐批次结论
        Object conclusions = body.get("conclusions");
        if (conclusions instanceof List<?> list && !list.isEmpty()) {
            storeQcRecheck(ticketId,
                    "复盘逐批次结论 || conclusions=" + com.logpark.coldchain.support.Json.write(conclusions),
                    username);
        }
        proposals.voidNonEffective(ceId, "工单 " + ticketNo + " 复盘责任结论更新，赔付方案需重新计算", username);
        return ticketView(ticketNo);
    }

    /** 闭环工单：责任/处置已确定，释放该工单关联的批次结算冻结。 */
    @Transactional
    public Map<String, Object> closeTicket(String ticketNo, String note, String username) {
        Map<String, Object> ticket = requireTicket(ticketNo);
        long ticketId = ((Number) ticket.get("id")).longValue();
        long ceId = ((Number) ticket.get("cold_event_id")).longValue();
        long tripId = tripIdOf(ceId);
        if ("CLOSED".equals(ticket.get("status"))) {
            throw ApiException.conflict("工单已闭环");
        }
        if ("ESCALATED".equals(ticket.get("status"))) {
            throw ApiException.conflict("工单处于升级审批中，须待审批链完整（L1/L2 通过）后再闭环");
        }
        if ("PENDING".equals(ticket.get("responsibility"))) {
            throw ApiException.unprocessable("尚未完成责任复盘，不能闭环");
        }
        Integer openEsc = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM escalation WHERE ticket_id = ? AND status IN ('PENDING','REJECTED')",
                Integer.class, ticketId);
        if (openEsc != null && openEsc > 0) {
            throw ApiException.unprocessable("该工单存在 " + openEsc + " 条未完成（待审/被驳回）升级审批，审批链完整前不能闭环");
        }
        db.jdbc().update(
                "UPDATE anomaly_ticket SET status = 'CLOSED', closed_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()), ticketId);
        // 释放本工单关联的、且不再被其它未闭环工单引用的冻结
        int released = releaseFreezesForTicket(ticketId, username);
        if (intervalId(ticketId) != null) {
            db.jdbc().update("UPDATE temp_anomaly_interval SET status = 'CLOSED', updated_at = ? WHERE id = ?",
                    Timestamp.valueOf(db.now()), intervalId(ticketId));
        }
        db.event(tripId, "TICKET_CLOSED", "QC", username,
                "异常工单 #" + ticketNo + " 已闭环，责任=" + ticket.get("responsibility")
                        + "，释放关联结算冻结 " + released + " 条；备注=" + note);
        return ticketView(ticketNo);
    }

    /** 主管延长客户决定时限。 */
    @Transactional
    public Map<String, Object> extendDue(String ticketNo, int hours, String username) {
        Map<String, Object> ticket = requireTicket(ticketNo);
        long ticketId = ((Number) ticket.get("id")).longValue();
        LocalDateTime base = ticket.get("due_at") == null || db.now().isAfter(((Timestamp) ticket.get("due_at")).toLocalDateTime())
                ? db.now() : ((Timestamp) ticket.get("due_at")).toLocalDateTime();
        LocalDateTime due = base.plusHours(hours);
        db.jdbc().update("UPDATE anomaly_ticket SET due_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.valueOf(due), Timestamp.valueOf(db.now()), ticketId);
        db.event(tripIdOf(((Number) ticket.get("cold_event_id")).longValue()),
                "DUE_EXTENDED", "SUPERVISOR", username,
                "工单 #" + ticketNo + " 客户决定时限延长至 " + due);
        return ticketView(ticketNo);
    }

    // ============================================================
    // 读模型
    // ============================================================

    public Map<String, Object> requireTicket(String ticketNo) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT * FROM anomaly_ticket WHERE ticket_no = ?", ticketNo);
        if (rows.isEmpty()) {
            throw ApiException.notFound("异常工单不存在: " + ticketNo);
        }
        return rows.get(0);
    }

    public Map<String, Object> ticketView(String ticketNo) {
        Map<String, Object> ticket = requireTicket(ticketNo);
        long ticketId = ((Number) ticket.get("id")).longValue();
        long ceId = ((Number) ticket.get("cold_event_id")).longValue();
        Map<String, Object> out = new LinkedHashMap<>(ticket);
        out.put("evidence", db.jdbc().queryForList("""
                SELECT id, version, role, evidence_type, content, ref_uri, submitted_by, submitted_at, superseded
                FROM ticket_evidence WHERE ticket_id = ? ORDER BY role, version
                """, ticketId));
        out.put("customerDecisions", db.jdbc().queryForList(
                "SELECT batch_no, decision, accepted_pieces, note, decided_by, decided_at " +
                "FROM customer_decision WHERE ticket_id = ? ORDER BY batch_no", ticketId));
        out.put("freezes", db.jdbc().queryForList(
                "SELECT id, batch_no, status, reason, frozen_at, released_at FROM settlement_freeze " +
                "WHERE ticket_id = ? ORDER BY id", ticketId));
        out.put("escalations", db.jdbc().queryForList(
                "SELECT id, level, status, reason, decision_note, decided_by, decided_at " +
                "FROM escalation WHERE ticket_id = ? ORDER BY level", ticketId));
        out.put("coldEventNo", db.jdbc().queryForObject(
                "SELECT event_no FROM cold_event WHERE id = ?", String.class, ceId));
        return out;
    }

    public List<Map<String, Object>> listByEvent(long ceId, String status) {
        String sql = "SELECT * FROM anomaly_ticket WHERE cold_event_id = ?"
                + (status == null ? "" : " AND status = ?") + " ORDER BY id";
        List<Map<String, Object>> rows = status == null
                ? db.jdbc().queryForList(sql, ceId)
                : db.jdbc().queryForList(sql, ceId, status);
        for (Map<String, Object> t : rows) {
            long id = ((Number) t.get("id")).longValue();
            t.put("evidenceCount", db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM ticket_evidence WHERE ticket_id = ?", Integer.class, id));
            t.put("latestEvidence", db.jdbc().queryForList("""
                    SELECT role, evidence_type, version, submitted_by, submitted_at, superseded
                    FROM ticket_evidence WHERE ticket_id = ? AND version IN
                      (SELECT MAX(version) FROM ticket_evidence e2 WHERE e2.ticket_id = ? GROUP BY role)
                    ORDER BY role
                    """, id, id));
        }
        return rows;
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 解析一张工单上最新质检复检证据中的逐批次结论。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> latestQcConclusions(long ticketId) {
        Map<String, String> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                SELECT content FROM ticket_evidence
                WHERE ticket_id = ? AND evidence_type IN ('QC_RECHECK')
                ORDER BY version DESC
                """, ticketId);
        for (Map<String, Object> row : rows) {
            String content = String.valueOf(row.get("content"));
            int idx = content.indexOf("conclusions=");
            if (idx < 0) continue;
            try {
                String json = content.substring(idx + "conclusions=".length());
                List<Map<String, Object>> list =
                        com.logpark.coldchain.support.Json.parseList(json);
                for (Map<String, Object> c : list) {
                    String batch = String.valueOf(c.get("batchNo"));
                    String conc = String.valueOf(c.get("conclusion")).toUpperCase();
                    result.putIfAbsent(batch, normalizeQc(conc));
                }
                if (!result.isEmpty()) return result; // 只用最新一条含结论的证据
            } catch (Exception ignored) {
                // 证据为自由文本时不参与自动冲突比对
            }
        }
        return result;
    }

    private void storeQcRecheck(long ticketId, String content, String username) {
        Integer maxVer = db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM ticket_evidence WHERE ticket_id = ? AND role='QC'",
                Integer.class, ticketId);
        db.jdbc().update("UPDATE ticket_evidence SET superseded=TRUE WHERE ticket_id=? AND role='QC'", ticketId);
        db.jdbc().update("""
                INSERT INTO ticket_evidence (ticket_id, version, role, evidence_type, content, submitted_by,
                    submitted_at, superseded)
                VALUES (?,?,'QC','QC_RECHECK',?,?,?,FALSE)
                """, ticketId, (maxVer == null ? 0 : maxVer) + 1, content,
                username, Timestamp.valueOf(db.now()));
    }

    private int releaseFreezesForTicket(long ticketId, String username) {
        // 一个批次可能被多张工单（各自冻结行）覆盖；当某工单关闭后，对其持有的每个批次，
        // 只要不再存在“关联未闭环工单”的冻结行，就释放该批次的全部冻结行（含已闭环工单遗留行）。
        List<Map<String, Object>> mine = db.jdbc().queryForList(
                "SELECT DISTINCT batch_no FROM settlement_freeze WHERE ticket_id = ? AND status = 'FROZEN'",
                ticketId);
        int n = 0;
        for (Map<String, Object> f : mine) {
            String batchNo = String.valueOf(f.get("batch_no"));
            Integer stillCovered = db.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM settlement_freeze f2
                    JOIN anomaly_ticket t ON t.id = f2.ticket_id
                    WHERE f2.batch_no = ? AND f2.status = 'FROZEN' AND t.status <> 'CLOSED'
                    """, Integer.class, batchNo);
            if (stillCovered != null && stillCovered > 0) continue;
            int released = db.jdbc().update("""
                    UPDATE settlement_freeze SET status = 'RELEASED', released_by = ?, released_at = ?
                    WHERE batch_no = ? AND status = 'FROZEN'
                    """, username, Timestamp.valueOf(db.now()), batchNo);
            n += released;
        }
        return n;
    }

    private Long intervalId(long ticketId) {
        List<Map<String, Object>> r = db.jdbc().queryForList(
                "SELECT interval_id FROM anomaly_ticket WHERE id = ?", ticketId);
        Object v = r.get(0).get("interval_id");
        return v == null ? null : ((Number) v).longValue();
    }

    private boolean ticketAffectsSettlement(long ticketId) {
        List<Map<String, Object>> r = db.jdbc().queryForList("""
                SELECT 1 FROM settlement_proposal p JOIN anomaly_ticket t ON t.cold_event_id = p.cold_event_id
                WHERE t.id = ? AND p.status IN ('DRAFT','APPROVING','APPROVED') LIMIT 1
                """, ticketId);
        return !r.isEmpty();
    }

    private String inferParty(String conclusion, Map<String, Object> ticket) {
        return switch (conclusion) {
            case "SENSOR_OFFLINE" -> "CARRIER";   // 默认可用承运商停靠说明推翻
            case "REAL_EXCURSION" -> "CARRIER";
            default -> "PENDING".equals(ticket.get("responsibility")) ? "SHARED"
                    : String.valueOf(ticket.get("responsibility"));
        };
    }

    private String normalizeQc(String c) {
        return switch (c) {
            case "PASS", "ACCEPTABLE", "ACCEPT" -> "ACCEPT";
            case "DOWNGRADED", "DOWNGRADE" -> "DOWNGRADE";
            case "REJECTED", "REJECT" -> "REJECT";
            default -> c;
        };
    }

    private long tripIdOf(long ceId) {
        return db.jdbc().queryForObject("SELECT trip_id FROM cold_event WHERE id = ?", Long.class, ceId);
    }

    private static String eventActorRole(String domainRole) {
        return switch (domainRole) {
            case "PARK" -> "DISPATCH";
            case "CUSTOMER" -> "CS";
            default -> domainRole;
        };
    }

    static String roleCn(String r) {
        return switch (r) {
            case "CARRIER" -> "承运商";
            case "PARK" -> "园区";
            case "QC" -> "质检";
            case "CUSTOMER" -> "客户";
            default -> r;
        };
    }

    static String qcCn(String c) {
        return switch (c) {
            case "ACCEPT" -> "可继续收货";
            case "DOWNGRADE" -> "建议降级";
            case "REJECT" -> "建议拒收";
            default -> c;
        };
    }

    static String choiceCn(String c) {
        return switch (c) {
            case "ACCEPT" -> "继续收货";
            case "DOWNGRADE" -> "降级收货";
            case "REJECT" -> "拒收";
            default -> c;
        };
    }

    static String conclusionCn(String c) {
        return switch (c) {
            case "SENSOR_OFFLINE" -> "传感器离线（数据断点，非真实超温）";
            case "REAL_EXCURSION" -> "真实超温";
            case "INCONCLUSIVE" -> "证据不足无法判定";
            default -> c;
        };
    }

    private static String str(Object o) {
        return String.valueOf(o).trim();
    }

    private static Object require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw ApiException.badRequest("缺少必填字段: " + key);
        }
        return v;
    }
}
