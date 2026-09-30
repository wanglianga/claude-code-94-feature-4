package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一页可审计的冷链异常时间线：
 * 每个断点（区间/批次/铅封）列出 证据、处置、责任结论、赔付状态、后续预约影响；
 * 并汇总冻结/解冻、补证版本、升级链、赔付方案版本与正式入账，生成审计指纹。
 */
@Service
public class AuditService {

    private final Db db;
    private final SettlementProposalService proposals;

    public AuditService(Db db, SettlementProposalService proposals) {
        this.db = db;
        this.proposals = proposals;
    }

    public Map<String, Object> auditByTripCode(String tripCode) {
        long ceId = db.jdbc().queryForObject(
                "SELECT id FROM cold_event WHERE trip_id = (SELECT id FROM trip WHERE code = ?)",
                Long.class, tripCode);
        return audit(ceId);
    }

    public long idOfEvent(String eventNo) {
        List<Map<String, Object>> r = db.jdbc().queryForList(
                "SELECT id FROM cold_event WHERE event_no = ?", eventNo);
        if (r.isEmpty()) {
            throw com.logpark.coldchain.support.ApiException.notFound("冷链事件不存在: " + eventNo);
        }
        return ((Number) r.get(0).get("id")).longValue();
    }

    public Map<String, Object> audit(long ceId) {
        Map<String, Object> ev = db.jdbc().queryForMap("SELECT * FROM cold_event WHERE id = ?", ceId);
        long tripId = ((Number) ev.get("trip_id")).longValue();
        Map<String, Object> trip = db.jdbc().queryForMap(
                "SELECT t.*, c.name AS carrier_name, dr.name AS driver_name FROM trip t " +
                "JOIN carrier c ON c.id=t.carrier_id JOIN driver dr ON dr.id=t.driver_id WHERE t.id=?", tripId);

        // ---- 断点（异常区间 + 批次不符 + 铅封）逐行 ----
        List<Map<String, Object>> breakpoints = new ArrayList<>();

        // 温度区间断点
        List<Map<String, Object>> intervals = db.jdbc().queryForList(
                "SELECT * FROM temp_anomaly_interval WHERE cold_event_id=? ORDER BY start_at", ceId);
        for (Map<String, Object> iv : intervals) {
            long intervalId = ((Number) iv.get("id")).longValue();
            List<Map<String, Object>> linked = db.jdbc().queryForList(
                    "SELECT ticket_no FROM anomaly_ticket WHERE interval_id=?", intervalId);
            breakpoints.add(baseBreakpoint("TEMP",
                    iv.get("start_at") + " ~ " + iv.get("end_at"),
                    kindCn(String.valueOf(iv.get("kind"))) + "：" + iv.get("evidence_summary"),
                    linked.stream().map(x -> String.valueOf(x.get("ticket_no"))).toList(),
                    ceId));
        }
        // 批次不符断点
        List<Map<String, Object>> mismatches = db.jdbc().queryForList("""
                SELECT ub.batch_no, ub.scanned_pieces, ie.expected_pieces, ub.mismatch_note, ub.scanned_at
                FROM unload_batch ub JOIN inbound_event ie
                  ON ie.cold_event_id=ub.cold_event_id AND ie.batch_no=ub.batch_no
                WHERE ub.cold_event_id=? AND ub.matched=FALSE
                """, ceId);
        for (Map<String, Object> m : mismatches) {
            List<Map<String, Object>> linked = db.jdbc().queryForList(
                    "SELECT ticket_no FROM anomaly_ticket WHERE cold_event_id=? AND category='BATCH_MISMATCH' AND batch_no=?",
                    ceId, m.get("batch_no"));
            breakpoints.add(baseBreakpoint("BATCH", String.valueOf(m.get("batch_no")),
                    "卸货扫描与预约不一致：预约 " + m.get("expected_pieces") + " / 实扫 "
                            + m.get("scanned_pieces") + "（" + m.get("mismatch_note") + "）",
                    linked.stream().map(x -> String.valueOf(x.get("ticket_no"))).toList(), ceId));
        }
        // 铅封断点
        List<Map<String, Object>> sealAlarms = db.jdbc().queryForList(
                "SELECT seal_no, recorded_at FROM seal_event WHERE cold_event_id=? AND seal_status='ALARM'", ceId);
        for (Map<String, Object> s : sealAlarms) {
            List<Map<String, Object>> linked = db.jdbc().queryForList(
                    "SELECT ticket_no FROM anomaly_ticket WHERE cold_event_id=? AND category='SEAL_ABNORMAL'", ceId);
            breakpoints.add(baseBreakpoint("SEAL", String.valueOf(s.get("recorded_at")),
                    "电子铅封 " + s.get("seal_no") + " 告警",
                    linked.stream().map(x -> String.valueOf(x.get("ticket_no"))).toList(), ceId));
        }

        // ---- 工单 → 证据/处置/责任/赔付状态 汇总 ----
        List<Map<String, Object>> tickets = db.jdbc().queryForList(
                "SELECT * FROM anomaly_ticket WHERE cold_event_id=? ORDER BY id", ceId);
        List<Map<String, Object>> ticketRows = new ArrayList<>();
        for (Map<String, Object> t : tickets) {
            long ticketId = ((Number) t.get("id")).longValue();
            Map<String, Object> row = new LinkedHashMap<>(t);
            row.put("evidenceVersions", db.jdbc().queryForList("""
                    SELECT version, role, evidence_type, submitted_by, submitted_at, superseded,
                           LEFT(content, 200) AS content_preview, ref_uri
                    FROM ticket_evidence WHERE ticket_id=? ORDER BY role, version
                    """, ticketId));
            row.put("customerDecisions", db.jdbc().queryForList(
                    "SELECT batch_no, decision, accepted_pieces, decided_by, decided_at " +
                    "FROM customer_decision WHERE ticket_id=? ORDER BY batch_no", ticketId));
            row.put("escalations", db.jdbc().queryForList(
                    "SELECT level, status, reason, decision_note, binding_choice_json, decided_by, decided_at " +
                    "FROM escalation WHERE ticket_id=? ORDER BY level", ticketId));
            row.put("freezeStatus", db.jdbc().queryForList(
                    "SELECT batch_no, status, frozen_at, released_at FROM settlement_freeze WHERE ticket_id=?",
                    ticketId));
            ticketRows.add(row);
        }

        // ---- 赔付方案版本时间线 ----
        List<Map<String, Object>> proposalsView = new ArrayList<>();
        for (Map<String, Object> p : proposals.listByEvent(ceId)) {
            long pid = ((Number) p.get("id")).longValue();
            Map<String, Object> pv = new LinkedHashMap<>(p);
            pv.put("entries", db.jdbc().queryForList("""
                    SELECT batch_no, expected_pieces, accepted_pieces, rejected_pieces,
                           customer_choice, qc_conclusion, liability_party, loss_amount, compensation
                    FROM settlement_entry WHERE proposal_id=? ORDER BY id
                    """, pid));
            proposalsView.add(pv);
        }
        Map<String, Object> ledgerRow = db.jdbc().queryForList(
                "SELECT * FROM settlement_ledger WHERE cold_event_id=?", ceId)
                .stream().findFirst().orElse(null);

        // ---- 后续预约影响：信用分变化 ----
        Integer creditNow = db.jdbc().queryForObject(
                "SELECT credit_score FROM carrier WHERE id=?", Integer.class, trip.get("carrier_id"));

        // ---- 设备与批次事实 ----
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("chillerCount", count("chiller_event", ceId));
        facts.put("doorCount", count("door_event", ceId));
        facts.put("sealCount", count("seal_event", ceId));
        facts.put("readingCount", db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM temp_reading WHERE trip_id=?", Integer.class, tripId));
        facts.put("inboundBatches", db.jdbc().queryForList(
                "SELECT batch_no, expected_pieces FROM inbound_event WHERE cold_event_id=? ORDER BY id", ceId));
        facts.put("unloadBatches", db.jdbc().queryForList(
                "SELECT batch_no, scanned_pieces, matched FROM unload_batch WHERE cold_event_id=? ORDER BY id", ceId));

        // ---- 时间线（事件流）----
        List<Map<String, Object>> timeline = db.jdbc().queryForList("""
                SELECT event_type, actor_role, actor_name, detail, created_at
                FROM trip_event WHERE trip_id=? ORDER BY created_at, id
                """, tripId);

        // ---- 汇总状态 ----
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("frozen", ev.get("frozen"));
        summary.put("frozenReason", ev.get("frozen_reason"));
        summary.put("frozenAt", ev.get("frozen_at"));
        summary.put("releasedAt", ev.get("released_at"));
        summary.put("openTickets", db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM anomaly_ticket WHERE cold_event_id=? AND status IN "
                + "('OPEN','AWAIT_CUSTOMER','REVIEWING','ESCALATED')", Integer.class, ceId));
        summary.put("closedTickets", db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM anomaly_ticket WHERE cold_event_id=? AND status='CLOSED'", Integer.class, ceId));
        summary.put("activeFreezes", db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM settlement_freeze WHERE cold_event_id=? AND status='FROZEN'",
                Integer.class, ceId));
        summary.put("pendingEscalations", db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM escalation WHERE cold_event_id=? AND status='PENDING'", Integer.class, ceId));
        summary.put("settlementBlockers", proposals.settlementBlockers(ceId));
        summary.put("settlementPosted", ledgerRow != null);

        // ---- 审计指纹（对关键不可变事实做 SHA-256）----
        String fingerprint = fingerprint(ceId, trip, tickets, proposalsView, ledgerRow, timeline);
        db.jdbc().update("UPDATE cold_event SET timeline_hash=?, updated_at=? WHERE id=?",
                fingerprint, Timestamp.valueOf(db.now()), ceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventNo", ev.get("event_no"));
        result.put("tripCode", trip.get("code"));
        result.put("carrier", trip.get("carrier_name"));
        result.put("driver", trip.get("driver_name"));
        result.put("vehiclePlate", trip.get("vehicle_plate"));
        result.put("chillerDeviceNo", ev.get("chiller_device_no"));
        result.put("doorSensorNo", ev.get("door_sensor_no"));
        result.put("eSealNo", ev.get("e_seal_no"));
        result.put("generatedAt", db.now().toString());
        result.put("auditFingerprint", fingerprint);
        result.put("summary", summary);
        result.put("facts", facts);
        result.put("breakpoints", breakpoints);
        result.put("tickets", ticketRows);
        result.put("proposals", proposalsView);
        result.put("ledger", ledgerRow);
        result.put("carrierCreditScoreNow", creditNow);
        result.put("futureBookingImpact",
                "承运商当前信用分 " + creditNow + "，影响后续预约 priority_score（信用分越低排窗优先级越低）");
        result.put("timeline", timeline);
        return result;
    }

    private Map<String, Object> baseBreakpoint(String kind, String at, String description,
                                               List<String> ticketNos, long ceId) {
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("breakpointKind", kind);
        bp.put("at", at);
        bp.put("description", description);
        bp.put("ticketNos", ticketNos);
        bp.put("evidence", new ArrayList<Map<String, Object>>());
        bp.put("disposition", new ArrayList<Map<String, Object>>());
        bp.put("responsibility", new LinkedHashSet<String>());
        bp.put("compensationStatus", "未进入赔付");
        for (String no : ticketNos) {
            List<Map<String, Object>> evs = db.jdbc().queryForList("""
                    SELECT role, evidence_type, version, submitted_by, submitted_at, superseded
                    FROM ticket_evidence e JOIN anomaly_ticket t ON t.id=e.ticket_id
                    WHERE t.ticket_no=? ORDER BY e.role, e.version
                    """, no);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evidence = (List<Map<String, Object>>) bp.get("evidence");
            evidence.addAll(evs);
            Map<String, Object> t = db.jdbc().queryForMap(
                    "SELECT status, responsibility, category FROM anomaly_ticket WHERE ticket_no=?", no);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> disposition = (List<Map<String, Object>>) bp.get("disposition");
            disposition.add(t);
            @SuppressWarnings("unchecked")
            Set<String> resp = (Set<String>) bp.get("responsibility");
            resp.add(String.valueOf(t.get("responsibility")));
        }
        // 赔付状态：该事件是否已有生效 ledger
        Integer posted = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM settlement_ledger WHERE cold_event_id=?", Integer.class, ceId);
        if (posted != null && posted > 0) {
            bp.put("compensationStatus", "已计入正式赔付（见 ledger）");
        } else if (!ticketNos.isEmpty()) {
            bp.put("compensationStatus", "赔付候选计算中/待审批");
        }
        return bp;
    }

    private Integer count(String table, long ceId) {
        return db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE cold_event_id=?", Integer.class, ceId);
    }

    private String kindCn(String kind) {
        return switch (kind) {
            case "OFFLINE" -> "传感器离线/数据断点";
            case "EXCURSION" -> "真实超温";
            case "MIXED" -> "混合区间（离线+超温）";
            default -> kind;
        };
    }

    private String fingerprint(long ceId, Map<String, Object> trip, List<Map<String, Object>> tickets,
                               List<Map<String, Object>> proposals, Object ledger,
                               List<Map<String, Object>> timeline) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(ceId).append('|').append(trip.get("code"));
            for (Map<String, Object> t : tickets) {
                sb.append("|T").append(t.get("ticket_no")).append(':').append(t.get("status"))
                        .append(':').append(t.get("responsibility"));
            }
            for (Map<String, Object> p : proposals) {
                sb.append("|P").append(p.get("version")).append(':').append(p.get("status"))
                        .append(':').append(p.get("compensation_amount"));
            }
            sb.append("|L:").append(ledger == null ? "none" : "posted");
            sb.append("|E:").append(timeline.size());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
