package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 升级审批链（L1 运营主管 → L2 质量与商务经理）。
 *
 * 触发：客户决定与质检复检结论冲突（TicketService 自动建 L1）；或赔付金额超合同阈值（结算发起）。
 * 通过：L1 通过后按“严重冲突 / 金额超阈值”自动生成 L2；L2 通过或无需 L2 时审批链完整。
 * 驳回：任一级驳回，工单退回，未生效赔付草稿撤销，补证/决定重做后重新发起。
 */
@Service
public class EscalationService {

    private final Db db;
    private final SettlementProposalService proposals;

    public EscalationService(Db db, @Lazy SettlementProposalService proposals) {
        this.db = db;
        this.proposals = proposals;
    }

    /** 幂等创建升级审批。 */
    @Transactional
    public long createIfAbsent(long ceId, Long ticketId, int level, String reason) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT id FROM escalation WHERE cold_event_id = ? AND COALESCE(ticket_id, -1) = COALESCE(?, -1) AND level = ?",
                ceId, ticketId, level);
        if (!rows.isEmpty()) {
            long existId = ((Number) rows.get(0).get("id")).longValue();
            // 已被驳回后再次触发：重新置为 PENDING
            db.jdbc().update("UPDATE escalation SET status='PENDING', reason=?, decided_by=NULL, " +
                    "decision_note=NULL, decided_at=NULL WHERE id=? AND status='REJECTED'", reason, existId);
            return existId;
        }
        long id = db.jdbc().queryForObject("""
                INSERT INTO escalation (cold_event_id, ticket_id, level, reason, status, created_at)
                VALUES (?,?,?,?,'PENDING',?) RETURNING id
                """, Long.class, ceId, ticketId, level, reason, Timestamp.valueOf(db.now()));
        long tripId = tripIdOf(ceId);
        db.event(tripId, "ESCALATION_OPEN", "SUPERVISOR", "系统",
                "升级审批 L" + level + " 已发起：" + reason + "（#" + id + "）");
        return id;
    }

    /**
     * 主管审批。body: {decision: APPROVE/REJECT, note, requireL2}
     */
    @Transactional
    public Map<String, Object> decide(long escalationId, Map<String, Object> body, String actor) {
        Map<String, Object> esc = requireEscalation(escalationId);
        long ceId = ((Number) esc.get("cold_event_id")).longValue();
        long tripId = tripIdOf(ceId);
        if (!"PENDING".equals(esc.get("status"))) {
            throw ApiException.conflict("该审批已处理（" + esc.get("status") + "），不能重复审批");
        }
        String decision = String.valueOf(body.getOrDefault("decision", "")).toUpperCase();
        String note = body.get("note") == null ? "" : String.valueOf(body.get("note"));
        Long ticketId = esc.get("ticket_id") == null ? null : ((Number) esc.get("ticket_id")).longValue();
        int level = ((Number) esc.get("level")).intValue();

        if ("REJECT".equals(decision)) {
            db.jdbc().update("""
                    UPDATE escalation SET status='REJECTED', decision_note=?, decided_by=?, decided_at=? WHERE id=?
                    """, note, actor, Timestamp.valueOf(db.now()), escalationId);
            if (ticketId != null) {
                // 退回等待客户重新决定/重新补证
                db.jdbc().update(
                        "UPDATE anomaly_ticket SET status='AWAIT_CUSTOMER', updated_at=? WHERE id=? AND status='ESCALATED'",
                        Timestamp.valueOf(db.now()), ticketId);
            }
            int voided = proposals.voidNonEffective(ceId,
                    "升级审批 L" + level + " 被驳回（" + note + "），未生效赔付草稿撤销，补证/重算后重新发起", actor);
            db.event(tripId, "ESCALATION_REJECTED", "SUPERVISOR", actor,
                    "升级审批 #" + escalationId + " L" + level + " 驳回：" + note
                            + "；" + voided + " 个未生效赔付方案已撤销");
            return view(escalationId);
        }
        if (!"APPROVE".equals(decision)) {
            throw ApiException.badRequest("decision 必须为 APPROVE/REJECT");
        }

        db.jdbc().update("""
                UPDATE escalation SET status='APPROVED', decision_note=?, binding_choice_json=?,
                                      decided_by=?, decided_at=? WHERE id=?
                """, note, body.get("bindingChoices") == null ? null
                        : com.logpark.coldchain.support.Json.write(body.get("bindingChoices")),
                actor, Timestamp.valueOf(db.now()), escalationId);

        // 主管对冲突的绑定裁定：落为权威的逐批次收货结论（覆盖客户与质检分歧）
        if (ticketId != null && body.get("bindingChoices") instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) o;
                String batchNo = String.valueOf(c.get("batchNo"));
                String choice = String.valueOf(c.get("decision")).toUpperCase();
                int accepted = ((Number) c.getOrDefault("acceptedPieces", 0)).intValue();
                if ("REJECT".equals(choice)) accepted = 0;
                db.jdbc().update("""
                        INSERT INTO customer_decision (ticket_id, batch_no, decision, accepted_pieces, note,
                            decided_by, decided_at)
                        VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT (ticket_id, batch_no) DO UPDATE
                        SET decision=EXCLUDED.decision, accepted_pieces=EXCLUDED.accepted_pieces,
                            note=EXCLUDED.note, decided_by=EXCLUDED.decided_by, decided_at=EXCLUDED.decided_at
                        """, ticketId, batchNo, choice, accepted,
                        "升级审批 L" + level + " 主管绑定裁定", "SUPERVISOR:" + actor,
                        Timestamp.valueOf(db.now()));
            }
            db.event(tripId, "ESCALATION_BINDING", "SUPERVISOR", actor,
                    "升级审批 #" + escalationId + " 对冲突批次作出绑定裁定：" + body.get("bindingChoices")
                            + "，赔付计算以该裁定为准");
            // 裁定结果改变可收数量与责任，赔付需重算
            proposals.voidNonEffective(ceId, "升级审批绑定裁定改变收货结论，赔付方案重新计算", actor);
        }

        boolean needL2 = Boolean.TRUE.equals(body.get("requireL2"));
        if (level == 1 && needL2) {
            long l2 = createIfAbsent(ceId, ticketId, 2,
                    "L1 审批人判定为重大冲突，升级质量与商务经理 L2 终审。L1 意见：" + note);
            db.event(tripId, "ESCALATION_L1_APPROVED", "SUPERVISOR", actor,
                    "L1 审批通过 #" + escalationId + "，显式升级 L2 #" + l2);
        } else {
            // 审批链完整：工单解除升级态
            if (ticketId != null) {
                db.jdbc().update(
                        "UPDATE anomaly_ticket SET status='REVIEWING', updated_at=? WHERE id=? AND status='ESCALATED'",
                        Timestamp.valueOf(db.now()), ticketId);
            }
            db.event(tripId, "ESCALATION_CHAIN_COMPLETE", "SUPERVISOR", actor,
                    "升级审批 #" + escalationId + "（L" + level + "）通过，审批链完整，可闭环工单并进入赔付生效");
        }
        return view(escalationId);
    }

    /** 审批链是否完整：事件下无 PENDING、无 REJECTED 的升级审批。 */
    public boolean chainComplete(long ceId) {
        Integer n = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM escalation WHERE cold_event_id = ? AND status IN ('PENDING','REJECTED')",
                Integer.class, ceId);
        return n == null || n == 0;
    }

    public boolean hasAny(long ceId) {
        Integer n = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM escalation WHERE cold_event_id = ?", Integer.class, ceId);
        return n != null && n > 0;
    }

    /** 是否已有通过的 L2（大额赔付终审）。 */
    public boolean hasApprovedL2(long ceId) {
        Integer n = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM escalation WHERE cold_event_id=? AND level=2 AND status='APPROVED'",
                Integer.class, ceId);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> listByEvent(long ceId) {
        return db.jdbc().queryForList(
                "SELECT * FROM escalation WHERE cold_event_id = ? ORDER BY id", ceId);
    }

    public Map<String, Object> view(long id) {
        Map<String, Object> esc = requireEscalation(id);
        return esc;
    }

    // ---- L2 条件 ----

    // ---- L2 条件由 SettlementProposalService 在方案生成时统一建立 ----

    private Map<String, Object> requireEscalation(long id) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("SELECT * FROM escalation WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("升级审批不存在: " + id);
        }
        return rows.get(0);
    }

    private long tripIdOf(long ceId) {
        return db.jdbc().queryForObject("SELECT trip_id FROM cold_event WHERE id = ?", Long.class, ceId);
    }
}
