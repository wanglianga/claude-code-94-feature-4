package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赔付候选方案与审批链。
 *
 * 计算依据（四要素）：责任归属、可收数量、合同阈值、客户选择。
 * - 责任归属：已结案工单的责任归集（多方有责记 SHARED）；
 * - 可收数量：客户时限内决定的可收/拒收件数（部分可收部分拒收）；
 * - 合同阈值：货主合同允许累计超温分钟与降级折价率，阈值内的真实超温不产生赔付；
 * - 客户选择：继续收货（无赔付）/ 降级收货（折价）/ 拒收（全值）。
 *
 * 纪律：赔付只在审批链完整后生效（EFFECTIVE）并写入结算；
 * 审批驳回或证据被替换 → 撤销全部未生效草稿（DRAFT/IN_APPROVAL → REVOKED）并按新版本重算；
 * payout(batch_id, calc_version) 唯一，重试/并发不会产生重复赔付。
 */
@Service
public class PayoutService {

    private final Db db;
    private final DetectionService detection;

    public PayoutService(Db db, DetectionService detection) {
        this.db = db;
        this.detection = detection;
    }

    // ---------------- 计算与重算 ----------------

    /**
     * （重新）计算车次全部受影响批次的赔付候选。
     * 未生效的旧版本一律撤销，以 calc_version+1 生成新草稿；已 EFFECTIVE 的赔付不动。
     */
    @Transactional
    public List<Map<String, Object>> computeForTrip(long tripId, String actor) {
        detection.ensureBatches(tripId);
        List<Map<String, Object>> batches = db.jdbc().queryForList("""
                SELECT DISTINCT b.* FROM trip_batch b
                JOIN settlement_freeze f ON f.batch_id = b.id
                WHERE b.trip_id = ?
                """, tripId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : batches) {
            out.add(computeForBatch(tripId, ((Number) b.get("id")).longValue(), actor));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> computeForBatch(long tripId, long batchId, String actor) {
        // 咨询锁串行化同批次的并发重算：撤销-重插按序执行，不会残留多条未生效草稿
        db.lock("payout:" + batchId);
        // 已生效赔付的批次不再重算（结算已写入，保证不重复赔付）
        Integer effective = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM payout WHERE batch_id = ? AND status = 'EFFECTIVE'",
                Integer.class, batchId);
        if (effective != null && effective > 0) {
            return db.jdbc().queryForMap(
                    "SELECT * FROM payout WHERE batch_id = ? AND status = 'EFFECTIVE'", batchId);
        }
        Map<String, Object> calc = calcBatch(tripId, batchId);

        // 撤销未生效草稿（审批驳回/证据替换/复盘输入变化 → 重算）
        List<Map<String, Object>> stale = db.jdbc().queryForList(
                "SELECT id FROM payout WHERE batch_id = ? AND status IN ('DRAFT','IN_APPROVAL')", batchId);
        for (Map<String, Object> p : stale) {
            long pid = ((Number) p.get("id")).longValue();
            db.jdbc().update(
                    "UPDATE approval_step SET status = 'SKIPPED' WHERE payout_id = ? AND status = 'PENDING'", pid);
            db.jdbc().update("UPDATE payout SET status = 'REVOKED' WHERE id = ?", pid);
        }

        int version = db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(calc_version), 0) + 1 FROM payout WHERE batch_id = ?", Integer.class, batchId);
        boolean escalated = Boolean.TRUE.equals(calc.get("escalated"));
        Long id = db.jdbc().queryForObject("""
                INSERT INTO payout (trip_id, batch_id, work_order_id, kind, calc_version, amount,
                                    responsibility, basis, status, created_at)
                VALUES (?,?,?, 'COMPENSATION', ?,?,?,?, 'DRAFT', ?)
                RETURNING id
                """, Long.class, tripId, batchId, calc.get("workOrderId"), version,
                calc.get("amount"), calc.get("responsibility"), calc.get("basis"),
                Timestamp.valueOf(db.now()));
        Map<String, Object> row = db.jdbc().queryForMap("SELECT * FROM payout WHERE id = ?", id);
        row.put("escalated", escalated);
        return row;
    }

    /** 单批次赔付测算：责任归属 × 可收数量 × 合同阈值 × 客户选择。 */
    private Map<String, Object> calcBatch(long tripId, long batchId) {
        Map<String, Object> batch = db.jdbc().queryForMap("""
                SELECT b.*, tc.value_per_piece, tc.owner_id, o.name AS owner_name,
                       o.contract_max_excursion_minutes, o.contract_temp_tolerance_c, o.downgrade_rate
                FROM trip_batch b
                JOIN trip_cargo tc ON tc.id = b.cargo_id
                JOIN cargo_owner o ON o.id = tc.owner_id
                WHERE b.id = ?
                """, batchId);

        // ---- 责任归属：已结案工单归集 ----
        List<Map<String, Object>> wos = db.jdbc().queryForList("""
                SELECT * FROM work_order
                WHERE trip_id = ? AND (batch_id = ? OR batch_id IS NULL)
                ORDER BY id
                """, tripId, batchId);
        Set<String> parties = new LinkedHashSet<>();
        long excursionMinutes = 0;
        boolean sensorOfflineOnly = true;
        boolean escalated = false;
        Long anyWoId = null;
        List<String> woBasis = new ArrayList<>();
        for (Map<String, Object> wo : wos) {
            anyWoId = ((Number) wo.get("id")).longValue();
            String status = (String) wo.get("status");
            if ("ESCALATED".equals(status) || Boolean.TRUE.equals(wo.get("escalated"))) escalated = true;
            if (!"RESOLVED".equals(status)) {
                woBasis.add("工单#" + wo.get("id") + "(" + wo.get("type") + ")未结案");
                continue;
            }
            String resp = (String) wo.get("responsibility");
            if (resp != null) parties.add(resp);
            String type = (String) wo.get("type");
            String cls = (String) wo.get("classification");
            if ("TEMP_EXCURSION".equals(type) || "DATA_GAP".equals(type)) {
                if ("REAL_EXCURSION".equals(cls)) {
                    sensorOfflineOnly = false;
                    Timestamp s = (Timestamp) wo.get("anomaly_start");
                    Timestamp e = (Timestamp) wo.get("anomaly_end");
                    if (s != null && e != null) {
                        excursionMinutes += Duration.between(s.toLocalDateTime(), e.toLocalDateTime()).toMinutes();
                    }
                }
            } else {
                sensorOfflineOnly = false; // 铅封/扫描类确认异常
            }
            woBasis.add("工单#" + wo.get("id") + "(" + type + "/" + (cls == null ? "确认" : cls) + ")→" + resp);
        }
        String responsibility = parties.isEmpty() ? null
                : parties.size() == 1 ? parties.iterator().next() : "SHARED";

        // ---- 客户选择 + 可收数量 ----
        List<Map<String, Object>> decisions = db.jdbc().queryForList("""
                SELECT * FROM customer_decision WHERE batch_id = ? AND active = TRUE
                ORDER BY decided_at DESC, id DESC LIMIT 1
                """, batchId);
        int expected = ((Number) batch.get("expected_qty")).intValue();
        int acceptQty = expected, rejectQty = 0;
        String choice = "ACCEPT";
        String choiceNote = "客户未决定（默认继续收货）";
        if (!decisions.isEmpty()) {
            Map<String, Object> d = decisions.get(0);
            choice = (String) d.get("decision");
            acceptQty = ((Number) d.get("accept_qty")).intValue();
            rejectQty = ((Number) d.get("reject_qty")).intValue();
            choiceNote = "客户决定=" + choice + "（可收 " + acceptQty + " / 拒收 " + rejectQty + "）";
        }

        // ---- 合同阈值 ----
        int contractMinutes = ((Number) batch.get("contract_max_excursion_minutes")).intValue();
        BigDecimal downgradeRate = (BigDecimal) batch.get("downgrade_rate");
        BigDecimal vpp = (BigDecimal) batch.get("value_per_piece");
        boolean withinContract = excursionMinutes <= contractMinutes;

        // ---- 货损与赔付 ----
        BigDecimal grossLoss = switch (choice) {
            case "REJECT" -> vpp.multiply(BigDecimal.valueOf(rejectQty));
            case "DOWNGRADE" -> vpp.multiply(BigDecimal.valueOf(rejectQty)).multiply(downgradeRate);
            default -> BigDecimal.ZERO;
        };
        boolean waived = false;
        if (withinContract && sensorOfflineOnly && grossLoss.compareTo(BigDecimal.ZERO) > 0) {
            // 合同阈值内且温度类异常均判定为传感器离线（非真实超温）→ 温度因素不产生赔付
            waived = true;
        }
        BigDecimal factor = responsibility == null ? BigDecimal.ZERO : switch (responsibility) {
            case "CARRIER", "PARK" -> BigDecimal.ONE;
            case "SHARED" -> new BigDecimal("0.5");
            default -> BigDecimal.ZERO; // OWNER 自担
        };
        BigDecimal amount = waived ? BigDecimal.ZERO
                : grossLoss.multiply(factor).setScale(2, RoundingMode.HALF_UP);

        String basis = String.format(
                "责任=%s；%s；真实超温累计=%d分钟（合同阈值=%d分钟%s）；传感器离线之外异常=%s；毛损=%.2f，责任系数=%s%s；应付赔付=%.2f。明细：%s",
                responsibility == null ? "未定/无责" : responsibility,
                choiceNote, excursionMinutes, contractMinutes,
                withinContract ? "，阈值内" : "，超阈值",
                sensorOfflineOnly ? "无" : "有",
                grossLoss, factor.toPlainString(),
                waived ? "；合同阈值内且非真实超温，温度因素赔付豁免" : "",
                amount, String.join("；", woBasis));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", amount);
        out.put("responsibility", responsibility);
        out.put("basis", basis);
        out.put("workOrderId", anyWoId);
        out.put("escalated", escalated);
        return out;
    }

    // ---------------- 审批链 ----------------

    /** 提交审批：DRAFT → IN_APPROVAL，生成审批链（升级单追加质检复核环节）。 */
    @Transactional
    public Map<String, Object> submit(long payoutId, String actor) {
        Map<String, Object> p = payout(payoutId);
        if (!"DRAFT".equals(p.get("status"))) {
            throw ApiException.conflict("仅 DRAFT 状态可提交审批，当前=" + p.get("status"));
        }
        long tripId = ((Number) p.get("trip_id")).longValue();
        Integer escalated = db.jdbc().queryForObject("""
                SELECT COUNT(*) FROM work_order WHERE trip_id = ? AND escalated = TRUE
                """, Integer.class, tripId);
        List<String> chain = escalated != null && escalated > 0
                ? List.of("QC", "DISPATCH", "SETTLE")   // 客户决定与质检结论冲突 → 升级审批
                : List.of("DISPATCH", "SETTLE");
        int seq = 1;
        for (String role : chain) {
            db.jdbc().update("""
                    INSERT INTO approval_step (payout_id, seq, approver_role, status)
                    VALUES (?,?,?, 'PENDING') ON CONFLICT (payout_id, seq) DO NOTHING
                    """, payoutId, seq++, role);
        }
        db.jdbc().update("UPDATE payout SET status = 'IN_APPROVAL' WHERE id = ?", payoutId);
        db.event(tripId, "PAYOUT_SUBMITTED", "SETTLE", actor,
                "赔付候选#" + payoutId + "（金额 " + p.get("amount") + "）提交审批，审批链=" + String.join("→", chain));
        return payout(payoutId);
    }

    /** 审批通过：当前环节角色审批；审批链完整后赔付生效（EFFECTIVE），方可写入结算。 */
    @Transactional
    public Map<String, Object> approve(long payoutId, String role, String actor, String comment) {
        Map<String, Object> step = currentStep(payoutId);
        if (!role.equals(step.get("approver_role"))) {
            throw ApiException.conflict("当前审批环节应由 " + step.get("approver_role") + " 审批");
        }
        finishStep(step, "APPROVED", actor, comment);
        Map<String, Object> p = payout(payoutId);
        Integer pending = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM approval_step WHERE payout_id = ? AND status = 'PENDING'",
                Integer.class, payoutId);
        if (pending != null && pending == 0) {
            // 审批链完整 → 生效（仅一次；状态守卫保证重试/并发不重复生效）
            int n = db.jdbc().update(
                    "UPDATE payout SET status = 'EFFECTIVE' WHERE id = ? AND status = 'IN_APPROVAL'", payoutId);
            if (n > 0) {
                db.event(((Number) p.get("trip_id")).longValue(), "PAYOUT_EFFECTIVE", "SYSTEM", "系统",
                        "赔付#" + payoutId + " 审批链完整，金额 " + p.get("amount") + " 生效，待写入结算");
            }
        }
        return payout(payoutId);
    }

    /** 审批驳回：赔付单驳回，撤销未生效草稿并重算新版本。 */
    @Transactional
    public Map<String, Object> reject(long payoutId, String role, String actor, String comment) {
        Map<String, Object> step = currentStep(payoutId);
        if (!role.equals(step.get("approver_role"))) {
            throw ApiException.conflict("当前审批环节应由 " + step.get("approver_role") + " 审批");
        }
        finishStep(step, "REJECTED", actor, comment);
        db.jdbc().update(
                "UPDATE approval_step SET status = 'SKIPPED' WHERE payout_id = ? AND status = 'PENDING'", payoutId);
        db.jdbc().update("UPDATE payout SET status = 'REJECTED' WHERE id = ?", payoutId);
        Map<String, Object> p = payout(payoutId);
        long tripId = ((Number) p.get("trip_id")).longValue();
        db.event(tripId, "PAYOUT_REJECTED", role, actor,
                "赔付#" + payoutId + " 被驳回（" + comment + "），系统撤销未生效草稿并重新计算");
        // 驳回 → 撤销未生效草稿并重算
        computeForTrip(tripId, actor);
        return payout(payoutId);
    }

    private Map<String, Object> currentStep(long payoutId) {
        Map<String, Object> p = payout(payoutId);
        if (!"IN_APPROVAL".equals(p.get("status"))) {
            throw ApiException.conflict("赔付单不在审批中，当前=" + p.get("status"));
        }
        List<Map<String, Object>> steps = db.jdbc().queryForList(
                "SELECT * FROM approval_step WHERE payout_id = ? AND status = 'PENDING' ORDER BY seq LIMIT 1",
                payoutId);
        if (steps.isEmpty()) {
            throw ApiException.conflict("审批链已完结");
        }
        return steps.get(0);
    }

    private void finishStep(Map<String, Object> step, String result, String actor, String comment) {
        db.jdbc().update("""
                UPDATE approval_step SET status = ?, decided_by = ?, decided_at = ?, comment = ?
                WHERE id = ?
                """, result, actor, Timestamp.valueOf(db.now()), comment,
                ((Number) step.get("id")).longValue());
    }

    public Map<String, Object> payout(long id) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("SELECT * FROM payout WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("赔付单不存在: " + id);
        }
        Map<String, Object> p = new LinkedHashMap<>(rows.get(0));
        p.put("approvalSteps", db.jdbc().queryForList(
                "SELECT * FROM approval_step WHERE payout_id = ? ORDER BY seq", id));
        return p;
    }

    public List<Map<String, Object>> ofTrip(long tripId) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT * FROM payout WHERE trip_id = ? ORDER BY batch_id, calc_version", tripId);
        for (Map<String, Object> p : rows) {
            p.put("approvalSteps", db.jdbc().queryForList(
                    "SELECT * FROM approval_step WHERE payout_id = ? ORDER BY seq",
                    ((Number) p.get("id")).longValue()));
        }
        return rows;
    }
}
