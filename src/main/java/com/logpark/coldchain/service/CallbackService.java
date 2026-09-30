package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备/闸口/卸货回调接入：温度、心跳、舱门、铅封、卸货扫描。
 * 所有回调均幂等：客户端幂等键（eventUid/scanUid/Idempotency-Key）+ 数据库唯一约束，
 * 重复回调与重试返回 duplicated=true 且不会产生重复事件、重复冻结或重复工单。
 */
@Service
public class CallbackService {

    private final Db db;
    private final DetectionService detection;

    public CallbackService(Db db, DetectionService detection) {
        this.db = db;
        this.detection = detection;
    }

    /** 通用幂等键：已存在返回 true（重复回调）。 */
    private boolean seen(String key, String endpoint) {
        int n = db.jdbc().update(
                "INSERT INTO idempotency_key (key, endpoint, created_at) VALUES (?,?,?) ON CONFLICT (key) DO NOTHING",
                key, endpoint, Timestamp.valueOf(db.now()));
        return n == 0;
    }

    private Map<String, Object> tripByCode(String code) {
        return db.getTripByCode(code);
    }

    // ---------------- 温度回调（设备直推） ----------------

    /**
     * body: { tripCode, idempotencyKey?, readings: [{recordedAt, celsius}] }
     * 温度点按 (trip_id, recorded_at) 天然去重；随后重跑异常检测（超温合并区间/断点 → 冻结+工单）。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> temperature(Map<String, Object> body, String actor) {
        String code = str(require(body, "tripCode"));
        Map<String, Object> trip = tripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        String idemKey = body.get("idempotencyKey") == null ? null : str(body.get("idempotencyKey"));
        if (idemKey != null && seen("temp:" + idemKey, "/api/callbacks/temperature")) {
            return Map.of("duplicated", true, "tripCode", code);
        }
        List<Map<String, Object>> readings =
                (List<Map<String, Object>>) require(body, "readings");
        String deviceNo = (String) trip.get("temp_device_no");
        int inserted = 0;
        for (Map<String, Object> r : readings) {
            LocalDateTime at = LocalDateTime.parse(str(require(r, "recordedAt")));
            BigDecimal c = new BigDecimal(str(require(r, "celsius")));
            inserted += db.jdbc().update("""
                    INSERT INTO temp_reading (trip_id, device_no, recorded_at, celsius)
                    VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                    """, tripId, deviceNo, Timestamp.valueOf(at), c);
        }
        // 断点标记重算（与月台上传逻辑一致）
        recomputeGapFlags(tripId);
        List<Map<String, Object>> anomalies = detection.detectTemperatureAnomalies(tripId);
        db.event(tripId, "TEMP_CALLBACK", "CARRIER", actor,
                "设备回调温度数据 " + inserted + " 点（去重后），检出异常区间 " + anomalies.size() + " 个");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duplicated", false);
        out.put("inserted", inserted);
        out.put("anomalies", anomalies);
        return out;
    }

    private void recomputeGapFlags(long tripId) {
        int gapMinutes = db.settingInt("TEMP_GAP_MINUTES", 45);
        db.jdbc().update("UPDATE temp_reading SET gap_after = FALSE WHERE trip_id = ?", tripId);
        db.jdbc().update("""
                UPDATE temp_reading a SET gap_after = TRUE
                WHERE a.trip_id = ? AND EXISTS (
                    SELECT 1 FROM temp_reading b
                    WHERE b.trip_id = a.trip_id AND b.recorded_at > a.recorded_at
                      AND NOT EXISTS (SELECT 1 FROM temp_reading c
                                      WHERE c.trip_id = a.trip_id
                                        AND c.recorded_at > a.recorded_at
                                        AND c.recorded_at < b.recorded_at)
                      AND b.recorded_at > a.recorded_at + (? || ' minutes')::interval)
                """, tripId, gapMinutes);
    }

    // ---------------- 设备心跳回调 ----------------

    /** body: { deviceNo, beats: [{beatAt, online}] } —— 区分传感器离线/真实超温的原始依据。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> heartbeat(Map<String, Object> body, String actor) {
        String deviceNo = str(require(body, "deviceNo"));
        List<Map<String, Object>> beats = (List<Map<String, Object>>) require(body, "beats");
        int inserted = 0;
        for (Map<String, Object> b : beats) {
            LocalDateTime at = LocalDateTime.parse(str(require(b, "beatAt")));
            boolean online = Boolean.parseBoolean(String.valueOf(b.getOrDefault("online", true)));
            inserted += db.jdbc().update("""
                    INSERT INTO device_heartbeat (device_no, beat_at, online) VALUES (?,?,?)
                    ON CONFLICT (device_no, beat_at) DO NOTHING
                    """, deviceNo, Timestamp.valueOf(at), online);
        }
        return Map.of("inserted", inserted, "deviceNo", deviceNo);
    }

    // ---------------- 舱门传感器回调 ----------------

    /** body: { tripCode, deviceNo, event: OPEN/CLOSE, occurredAt, eventUid } */
    @Transactional
    public Map<String, Object> door(Map<String, Object> body, String actor) {
        String code = str(require(body, "tripCode"));
        long tripId = ((Number) tripByCode(code).get("id")).longValue();
        String event = str(require(body, "event")).toUpperCase();
        if (!List.of("OPEN", "CLOSE").contains(event)) {
            throw ApiException.badRequest("event 必须为 OPEN/CLOSE");
        }
        String uid = str(require(body, "eventUid"));
        LocalDateTime at = LocalDateTime.parse(str(require(body, "occurredAt")));
        int n = db.jdbc().update("""
                INSERT INTO door_event (trip_id, device_no, event, occurred_at, event_uid)
                VALUES (?,?,?,?,?) ON CONFLICT (event_uid) DO NOTHING
                """, tripId, str(require(body, "deviceNo")), event, Timestamp.valueOf(at), uid);
        if (n > 0) {
            db.event(tripId, "DOOR_" + event, "CARRIER", actor,
                    "舱门传感器上报" + ("OPEN".equals(event) ? "开门" : "关门") + "（" + at + "）");
        }
        return Map.of("duplicated", n == 0, "eventUid", uid);
    }

    // ---------------- 电子铅封回调 ----------------

    /** body: { tripCode, sealNo, event: SEALED/TAMPERED/BROKEN/UNSEALED, occurredAt, eventUid } */
    @Transactional
    public Map<String, Object> seal(Map<String, Object> body, String actor) {
        String code = str(require(body, "tripCode"));
        long tripId = ((Number) tripByCode(code).get("id")).longValue();
        String event = str(require(body, "event")).toUpperCase();
        if (!List.of("SEALED", "TAMPERED", "BROKEN", "UNSEALED").contains(event)) {
            throw ApiException.badRequest("event 必须为 SEALED/TAMPERED/BROKEN/UNSEALED");
        }
        String uid = str(require(body, "eventUid"));
        LocalDateTime at = LocalDateTime.parse(str(require(body, "occurredAt")));
        int n = db.jdbc().update("""
                INSERT INTO seal_event (trip_id, seal_no, event, occurred_at, event_uid)
                VALUES (?,?,?,?,?) ON CONFLICT (event_uid) DO NOTHING
                """, tripId, str(require(body, "sealNo")), event, Timestamp.valueOf(at), uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duplicated", n == 0);
        out.put("eventUid", uid);
        if (n > 0) {
            db.event(tripId, "SEAL_" + event, "CARRIER", actor,
                    "电子铅封 " + body.get("sealNo") + " 上报 " + event + "（" + at + "）");
            if (List.of("TAMPERED", "BROKEN").contains(event)) {
                long woId = detection.raiseSealAbnormal(tripId, uid, event, at);
                db.raiseIncident(tripId, "SEAL_ABNORMAL", "HIGH",
                        "电子铅封异常 " + event + "，全部批次入库结算已自动冻结，待多方补证复盘",
                        "PENDING", "CARRIER");
                db.event(tripId, "FREEZE_AUTO", "SYSTEM", "系统",
                        "铅封异常触发自动冻结：工单#" + woId + "，全车批次暂停入库结算");
                out.put("workOrderId", woId);
                out.put("frozen", true);
            }
        }
        return out;
    }

    // ---------------- 卸货扫描回调 ----------------

    /** body: { tripCode, batchCode, qty, scannedAt, scanUid } —— 与预约批次比对，不一致自动冻结。 */
    @Transactional
    public Map<String, Object> unloadScan(Map<String, Object> body, String actor) {
        String code = str(require(body, "tripCode"));
        long tripId = ((Number) tripByCode(code).get("id")).longValue();
        String batchCode = str(require(body, "batchCode"));
        int qty = ((Number) require(body, "qty")).intValue();
        String uid = str(require(body, "scanUid"));
        LocalDateTime at = LocalDateTime.parse(str(require(body, "scannedAt")));
        int n = db.jdbc().update("""
                INSERT INTO unload_scan (trip_id, batch_code, qty, scanned_at, scan_uid)
                VALUES (?,?,?,?,?) ON CONFLICT (scan_uid) DO NOTHING
                """, tripId, batchCode, qty, Timestamp.valueOf(at), uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duplicated", n == 0);
        out.put("scanUid", uid);
        if (n > 0) {
            String result = detection.checkScan(tripId, batchCode, qty);
            out.put("scanResult", result);
            db.event(tripId, "UNLOAD_SCAN", "QC", actor,
                    "卸货扫描批次 " + batchCode + " 数量 " + qty + "，比对结果=" + result);
            if (!"MATCHED".equals(result)) {
                db.raiseIncident(tripId, "SCAN_MISMATCH", "HIGH",
                        "EXTRA".equals(result)
                                ? "卸货扫描到预约外批次 " + batchCode + "，全车批次入库结算已自动冻结"
                                : "批次 " + batchCode + " 扫描数量与预约不一致，该批次入库结算已自动冻结",
                        "PENDING", "QC");
                db.event(tripId, "FREEZE_AUTO", "SYSTEM", "系统",
                        "扫描比对不一致（" + result + "）触发自动冻结，待多方补证复盘");
                out.put("frozen", true);
            }
        }
        return out;
    }

    private static Object require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw ApiException.badRequest("缺少必填字段: " + key);
        }
        return v;
    }

    private static String str(Object o) {
        return String.valueOf(o).trim();
    }
}
