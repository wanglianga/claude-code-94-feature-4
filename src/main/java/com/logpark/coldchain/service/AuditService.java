package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一页可审计异常时间线：
 * 将预约单、车辆/司机、冷机设备、舱门传感器、电子铅封、温度曲线、卸货批次
 * 关联为一条冷链事件时间线；每个异常断点给出证据、处置、责任结论、赔付状态与后续预约影响。
 */
@Service
public class AuditService {

    private final Db db;

    public AuditService(Db db) {
        this.db = db;
    }

    public Map<String, Object> audit(String code) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trip", tripHeader(trip));
        out.put("devices", devices(trip, tripId));
        out.put("batches", batches(tripId));
        out.put("temperature", temperature(tripId));
        out.put("anomalies", anomalies(tripId));
        out.put("settlement", settlement(trip));
        out.put("followUpImpact", followUp(trip));
        out.put("timeline", timeline(tripId));
        return out;
    }

    // ---------------- 车辆/司机/预约 ----------------

    private Map<String, Object> tripHeader(Map<String, Object> trip) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("code", trip.get("code"));
        h.put("status", trip.get("status"));
        h.put("vehiclePlate", trip.get("vehicle_plate"));
        h.put("windowStart", trip.get("window_start"));
        h.put("windowEnd", trip.get("window_end"));
        h.put("arrivedAt", trip.get("arrived_at"));
        Map<String, Object> carrier = db.jdbc().queryForMap("""
                SELECT c.name, c.credit_score, d.name AS driver_name, d.license_no, d.license_expire_date
                FROM carrier c, driver d
                WHERE c.id = ? AND d.id = ?
                """, trip.get("carrier_id"), trip.get("driver_id"));
        h.put("carrier", carrier.get("name"));
        h.put("carrierCreditScore", carrier.get("credit_score"));
        h.put("driver", carrier.get("driver_name"));
        h.put("driverLicense", carrier.get("license_no"));
        h.put("licenseExpireDate", carrier.get("license_expire_date"));
        h.put("requestedAt", trip.get("requested_at"));
        return h;
    }

    // ---------------- 冷机/舱门/铅封设备 ----------------

    private Map<String, Object> devices(Map<String, Object> trip, long tripId) {
        Map<String, Object> d = new LinkedHashMap<>();
        String deviceNo = (String) trip.get("temp_device_no");
        d.put("reeferDevice", db.jdbc().queryForList(
                "SELECT device_no, model FROM reefer_device WHERE device_no = ?", deviceNo));
        d.put("tempDeviceNo", deviceNo);
        d.put("eSealNo", trip.get("e_seal_no"));
        d.put("doorEvents", db.jdbc().queryForList(
                "SELECT device_no, event, occurred_at FROM door_event WHERE trip_id = ? ORDER BY occurred_at",
                tripId));
        d.put("sealEvents", db.jdbc().queryForList(
                "SELECT seal_no, event, occurred_at FROM seal_event WHERE trip_id = ? ORDER BY occurred_at",
                tripId));
        d.put("offlineBeats", db.jdbc().queryForList(
                "SELECT beat_at FROM device_heartbeat WHERE device_no = ? AND online = FALSE ORDER BY beat_at",
                deviceNo));
        return d;
    }

    // ---------------- 卸货批次 ----------------

    private List<Map<String, Object>> batches(long tripId) {
        return db.jdbc().queryForList("""
                SELECT b.id, b.batch_code, b.expected_qty, b.scanned_qty, b.scan_status,
                       b.accept_qty, b.reject_qty, o.name AS owner_name, tc.cargo_type,
                       (SELECT string_agg(DISTINCT f.reason || ':' || f.status, ', ')
                          FROM settlement_freeze f WHERE f.batch_id = b.id) AS freeze_state
                FROM trip_batch b
                JOIN trip_cargo tc ON tc.id = b.cargo_id
                JOIN cargo_owner o ON o.id = tc.owner_id
                WHERE b.trip_id = ? ORDER BY b.id
                """, tripId);
    }

    // ---------------- 温度曲线 ----------------

    private Map<String, Object> temperature(long tripId) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("stats", db.jdbc().queryForMap("""
                SELECT COUNT(*) AS points, MIN(celsius) AS min_c, MAX(celsius) AS max_c,
                       COUNT(*) FILTER (WHERE gap_after) AS gaps
                FROM temp_reading WHERE trip_id = ?
                """, tripId));
        t.put("gapPoints", db.jdbc().queryForList(
                "SELECT recorded_at, celsius FROM temp_reading WHERE trip_id = ? AND gap_after = TRUE ORDER BY recorded_at",
                tripId));
        return t;
    }

    // ---------------- 异常断点：证据/处置/责任/赔付 ----------------

    private List<Map<String, Object>> anomalies(long tripId) {
        List<Map<String, Object>> wos = db.jdbc().queryForList(
                "SELECT * FROM work_order WHERE trip_id = ? ORDER BY anomaly_start NULLS LAST, id", tripId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> wo : wos) {
            long woId = ((Number) wo.get("id")).longValue();
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("workOrderId", woId);
            a.put("type", wo.get("type"));
            a.put("status", wo.get("status"));
            a.put("anomalyStart", wo.get("anomaly_start"));
            a.put("anomalyEnd", wo.get("anomaly_end"));
            a.put("mergedIntervals", wo.get("merged_count"));
            a.put("classification", wo.get("classification"));
            a.put("classifyNote", wo.get("classify_note"));
            a.put("responsibility", wo.get("responsibility"));
            a.put("reviewNote", wo.get("review_note"));
            // 证据（全版本留痕，含被替换版本）
            a.put("evidence", db.jdbc().queryForList("""
                    SELECT role, evidence_type, version, content, submitted_by, submitted_at, active
                    FROM evidence WHERE work_order_id = ? ORDER BY evidence_type, role, version
                    """, woId));
            // 客户决定
            a.put("customerDecisions", db.jdbc().queryForList("""
                    SELECT cd.batch_id, b.batch_code, cd.decision, cd.accept_qty, cd.reject_qty,
                           cd.decided_by, cd.decided_at, cd.deadline_at, cd.active
                    FROM customer_decision cd JOIN trip_batch b ON b.id = cd.batch_id
                    WHERE cd.work_order_id = ? ORDER BY cd.id
                    """, woId));
            // 冻结状态
            a.put("freezes", db.jdbc().queryForList("""
                    SELECT f.reason, f.status, b.batch_code, f.created_at, f.released_at, f.release_note
                    FROM settlement_freeze f JOIN trip_batch b ON b.id = f.batch_id
                    WHERE f.work_order_id = ?
                    """, woId));
            // 赔付状态（该工单关联批次的最新赔付）
            a.put("payouts", db.jdbc().queryForList("""
                    SELECT p.id, p.batch_id, b.batch_code, p.calc_version, p.amount, p.responsibility,
                           p.status, p.basis
                    FROM payout p JOIN trip_batch b ON b.id = p.batch_id
                    WHERE p.trip_id = ? AND (p.work_order_id = ? OR p.batch_id IN (
                        SELECT batch_id FROM settlement_freeze WHERE work_order_id = ?))
                    ORDER BY p.batch_id, p.calc_version
                    """, tripId, woId, woId));
            out.add(a);
        }
        return out;
    }

    // ---------------- 结算 ----------------

    private Map<String, Object> settlement(Map<String, Object> trip) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("liabilityParty", trip.get("liability_party"));
        s.put("penaltyAmount", trip.get("penalty_amount"));
        s.put("compensationAmount", trip.get("compensation_amount"));
        s.put("liabilityNote", trip.get("liability_note"));
        s.put("settledAt", trip.get("settled_at"));
        s.put("frozenBatches", db.jdbc().queryForObject("""
                SELECT COUNT(*) FROM settlement_freeze WHERE trip_id = ? AND status = 'FROZEN'
                """, Integer.class, trip.get("id")));
        s.put("effectivePayouts", db.jdbc().queryForList(
                "SELECT id, batch_id, amount, responsibility FROM payout WHERE trip_id = ? AND status = 'EFFECTIVE'",
                trip.get("id")));
        return s;
    }

    // ---------------- 后续预约影响 ----------------

    private Map<String, Object> followUp(Map<String, Object> trip) {
        Map<String, Object> f = new LinkedHashMap<>();
        Map<String, Object> carrier = db.jdbc().queryForMap(
                "SELECT name, credit_score FROM carrier WHERE id = ?", trip.get("carrier_id"));
        f.put("carrier", carrier.get("name"));
        f.put("currentCreditScore", carrier.get("credit_score"));
        f.put("impactNote", "承运商信用分直接参与后续预约的窗口优先级评分（priority_score）："
                + "责任定责与赔付生效后信用分下调，后续预约排窗顺位相应下降；无责或豁免则不受影响。");
        f.put("openWorkOrders", db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE trip_id = ? AND status <> 'RESOLVED'",
                Integer.class, trip.get("id")));
        return f;
    }

    // ---------------- 统一时间线 ----------------

    private List<Map<String, Object>> timeline(long tripId) {
        List<Map<String, Object>> events = new ArrayList<>();
        events.addAll(db.jdbc().queryForList("""
                SELECT created_at AS at, 'PROCESS' AS source, event_type AS type,
                       actor_role AS role, actor_name AS actor, detail
                FROM trip_event WHERE trip_id = ?
                """, tripId));
        events.addAll(db.jdbc().queryForList("""
                SELECT occurred_at AS at, 'DOOR_SENSOR' AS source, 'DOOR_' || event AS type,
                       'DEVICE' AS role, device_no AS actor, '舱门' || CASE event WHEN 'OPEN' THEN '开' ELSE '关' END AS detail
                FROM door_event WHERE trip_id = ?
                """, tripId));
        events.addAll(db.jdbc().queryForList("""
                SELECT occurred_at AS at, 'E_SEAL' AS source, 'SEAL_' || event AS type,
                       'DEVICE' AS role, seal_no AS actor, '铅封状态 ' || event AS detail
                FROM seal_event WHERE trip_id = ?
                """, tripId));
        events.addAll(db.jdbc().queryForList("""
                SELECT scanned_at AS at, 'UNLOAD_SCAN' AS source, 'SCAN' AS type,
                       'QC' AS role, batch_code AS actor, '卸货扫描数量 ' || qty AS detail
                FROM unload_scan WHERE trip_id = ?
                """, tripId));
        events.addAll(db.jdbc().queryForList("""
                SELECT submitted_at AS at, 'EVIDENCE' AS source, e.evidence_type || '_V' || e.version AS type,
                       e.role, e.submitted_by AS actor,
                       '工单#' || e.work_order_id || ' 补证' || CASE e.active WHEN TRUE THEN '' ELSE '（已被新版本替换）' END AS detail
                FROM evidence e WHERE e.work_order_id IN (SELECT id FROM work_order WHERE trip_id = ?)
                """, tripId));
        events.addAll(db.jdbc().queryForList("""
                SELECT created_at AS at, 'FREEZE' AS source, 'FREEZE_' || status AS type,
                       'SYSTEM' AS role, '系统' AS actor,
                       '批次#' || batch_id || ' 原因=' || reason AS detail
                FROM settlement_freeze WHERE trip_id = ?
                """, tripId));
        events.addAll(db.jdbc().queryForList("""
                SELECT created_at AS at, 'PAYOUT' AS source, 'PAYOUT_V' || calc_version AS type,
                       'SYSTEM' AS role, '系统' AS actor,
                       '批次#' || batch_id || ' 赔付候选 ¥' || amount || '（' || status || '）' AS detail
                FROM payout WHERE trip_id = ?
                """, tripId));
        events.sort(Comparator.comparing(e -> ((Timestamp) e.get("at")).toLocalDateTime()));
        return events;
    }
}
