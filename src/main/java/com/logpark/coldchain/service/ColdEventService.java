package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * feature-4-b 核心：冷链事件时间线聚合与异常引擎。
 *
 * 职责：
 * 1) 车辆入园后把预约单、车辆/司机、冷机、舱门传感器、电子铅封、温度曲线、卸货批次
 *    聚合为一条 cold_event；
 * 2) 冷机/舱门/铅封回调幂等入库（每类事件 (cold_event_id, event_id) 唯一约束去重，重复/并发/重试回调不产生重复数据）；
 * 3) 温度复盘：区分“传感器离线（数据断点）”与“真实超温”，相邻异常区间自动合并；
 * 4) 超温/断点/铅封异常/卸货批次不一致 → 幂等创建异常工单并冻结该批次正常入库结算。
 */
@Service
public class ColdEventService {

    public static final List<String> TRIP_RELEVANT_STATUSES =
            List.of("AT_GATE", "QUEUED_AT_GATE", "AT_DOCK", "IN_QC",
                    "QUARANTINED", "AWAIT_DISPOSITION", "SETTLING", "DONE");

    private final Db db;

    public ColdEventService(Db db) {
        this.db = db;
    }

    // ============================================================
    // 冷链事件聚合
    // ============================================================

    /** 车辆入园时（闸口放行）确保冷链事件已建立并播种预约批次。幂等。 */
    @Transactional
    public long ensureForTrip(long tripId, String actor) {
        List<Map<String, Object>> exist = db.jdbc().queryForList(
                "SELECT id FROM cold_event WHERE trip_id = ?", tripId);
        if (!exist.isEmpty()) {
            return ((Number) exist.get(0).get("id")).longValue();
        }
        return createColdEvent(tripId, null, actor);
    }

    @Transactional
    public Map<String, Object> createByTripCode(String tripCode, Map<String, Object> body, String actor) {
        Map<String, Object> trip = db.getTripByCode(tripCode);
        long tripId = ((Number) trip.get("id")).longValue();
        String doorSensor = body == null ? null : str(body.get("doorSensorNo"));
        long ceId;
        List<Map<String, Object>> exist = db.jdbc().queryForList(
                "SELECT id FROM cold_event WHERE trip_id = ?", tripId);
        if (!exist.isEmpty()) {
            ceId = ((Number) exist.get(0).get("id")).longValue();
            if (doorSensor != null) {
                db.jdbc().update("UPDATE cold_event SET door_sensor_no = ?, updated_at = ? WHERE id = ?",
                        doorSensor, Timestamp.valueOf(db.now()), ceId);
            }
        } else {
            ceId = createColdEvent(tripId, doorSensor, actor);
        }
        return eventView(ceId);
    }

    private long createColdEvent(long tripId, String doorSensorNo, String actor) {
        Map<String, Object> trip = db.jdbc().queryForMap("SELECT * FROM trip WHERE id = ?", tripId);
        String eventNo = "CE" + db.now().getYear()
                + String.format("%02d", db.now().getMonthValue())
                + String.format("%04d", db.jdbc().queryForObject(
                        "SELECT nextval('cold_event_no_seq')", Long.class));

        long ceId = db.jdbc().queryForObject("""
                INSERT INTO cold_event (trip_id, event_no, appointment_code, carrier_id, driver_id,
                    chiller_device_no, door_sensor_no, e_seal_no, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                RETURNING id
                """, Long.class, tripId, eventNo, trip.get("code"),
                trip.get("carrier_id"), trip.get("driver_id"),
                trip.get("temp_device_no"), doorSensorNo, trip.get("e_seal_no"),
                Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()));

        // 预约批次来自预约明细（预约单声明的应到批次）
        List<Map<String, Object>> lines = db.jdbc().queryForList(
                "SELECT id, owner_id, cargo_type, pieces FROM trip_cargo WHERE trip_id = ? ORDER BY id", tripId);
        int idx = 0;
        for (Map<String, Object> line : lines) {
            idx++;
            String batchNo = trip.get("code") + "-B" + idx;
            db.jdbc().update("""
                    INSERT INTO inbound_event (cold_event_id, batch_no, owner_id, cargo_type, expected_pieces, created_at)
                    VALUES (?,?,?,?,?,?)
                    """, ceId, batchNo, line.get("owner_id"), line.get("cargo_type"),
                    ((Number) line.get("pieces")).intValue(), Timestamp.valueOf(db.now()));
        }
        db.event(tripId, "COLD_EVENT_CREATED", "DISPATCH", actor,
                "冷链事件时间线已建立 " + eventNo + "，关联预约单/车辆司机/冷机"
                        + (doorSensorNo == null ? "" : "/" + doorSensorNo)
                        + "/电子铅封/温度曲线/卸货批次，预约批次 " + idx + " 个");
        return ceId;
    }

    public Map<String, Object> requireEventByNo(String eventNo) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT * FROM cold_event WHERE event_no = ?", eventNo);
        if (rows.isEmpty()) {
            throw ApiException.notFound("冷链事件不存在: " + eventNo);
        }
        return rows.get(0);
    }

    public long eventIdByTripCode(String tripCode) {
        long tripId = db.tripId(tripCode);
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT id FROM cold_event WHERE trip_id = ?", tripId);
        if (rows.isEmpty()) {
            throw ApiException.unprocessable("该车次尚未建立冷链事件（车辆入园后自动建立）");
        }
        return ((Number) rows.get(0).get("id")).longValue();
    }

    /** 承运商回调仅能操作本公司冷链事件。 */
    public void assertCarrierOwnership(long coldEventId, String username) {
        Map<String, Object> user = db.jdbc().queryForMap(
                "SELECT carrier_id FROM app_user WHERE username = ?", username);
        Long userCarrier = user.get("carrier_id") == null ? null
                : ((Number) user.get("carrier_id")).longValue();
        Long eventCarrier = db.jdbc().queryForObject(
                "SELECT carrier_id FROM cold_event WHERE id = ?", Long.class, coldEventId);
        if (userCarrier == null || !userCarrier.equals(eventCarrier)) {
            throw ApiException.conflict("只能向本公司承运的冷链事件推送设备数据");
        }
    }

    // ============================================================
    // 设备回调（冷机 / 舱门 / 铅封）——幂等
    // ============================================================

    /** 冷机回调。events: [{eventId, recordedAt, status, celsius}]；可整批重放。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingestChiller(String eventNo, Map<String, Object> body, String actor) {
        long ceId = ((Number) requireEventByNo(eventNo).get("id")).longValue();
        assertCarrierOwnership(ceId, actor);
        long tripId = tripIdOf(ceId);
        String deviceNo = String.valueOf(requireEventByNo(eventNo).get("chiller_device_no"));

        List<Map<String, Object>> events = ((List<Map<String, Object>>) body.getOrDefault("events", List.of()));
        if (events.isEmpty()) {
            throw ApiException.badRequest("events 不能为空");
        }
        int inserted = 0;
        LocalDateTime latest = null;
        for (Map<String, Object> e : events) {
            String eventId = str(require(e, "eventId"));
            LocalDateTime at = LocalDateTime.parse(str(require(e, "recordedAt")));
            String status = e.get("status") == null ? "RUNNING" : str(e.get("status")).toUpperCase();
            BigDecimal celsius = e.get("celsius") == null ? null
                    : new BigDecimal(str(e.get("celsius")));
            int n = db.jdbc().update("""
                    INSERT INTO chiller_event (cold_event_id, event_id, device_no, recorded_at,
                        payload_status, celsius, raw_payload, received_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT (cold_event_id, event_id) DO NOTHING
                    """, ceId, eventId, deviceNo, Timestamp.valueOf(at), status, celsius,
                    String.valueOf(e.getOrDefault("raw", e)), Timestamp.valueOf(db.now()));
            if (n > 0) {
                inserted++;
                if (celsius != null) {
                    // 同步进统一温度曲线（原始点唯一），供区间分析与追溯
                    db.jdbc().update("""
                            INSERT INTO temp_reading (trip_id, device_no, recorded_at, celsius)
                            VALUES (?,?,?,?) ON CONFLICT (trip_id, recorded_at) DO NOTHING
                            """, tripId, deviceNo, Timestamp.valueOf(at), celsius);
                }
                latest = (latest == null || at.isAfter(latest)) ? at : latest;
                // 冷机上报告警状态：登记设备侧事实到时间线
                if ("ALARM".equals(status) || "DEFROST_ALARM".equals(status)) {
                    db.event(tripId, "CHILLER_ALARM", "CARRIER", actor,
                            "冷机设备 " + deviceNo + " 于 " + at + " 上报告警 " + status
                                    + (celsius == null ? "" : "，厢温 " + celsius + "℃"));
                }
            }
        }
        if (inserted > 0) {
            db.event(tripId, "CHILLER_CALLBACK", "CARRIER", actor,
                    "冷机回调入库 " + inserted + "/" + events.size() + " 条（重复 eventId 自动去重）");
            analyzeTemperature(ceId, actor);
        }
        Map<String, Object> view = eventView(ceId);
        view.put("callback", Map.of("type", "CHILLER", "received", events.size(),
                "newInserted", inserted, "duplicated", events.size() - inserted));
        return view;
    }

    /** 舱门传感器回调。events: [{eventId, sensorNo, recordedAt, action: OPEN/CLOSE}] */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingestDoor(String eventNo, Map<String, Object> body, String actor) {
        long ceId = ((Number) requireEventByNo(eventNo).get("id")).longValue();
        assertCarrierOwnership(ceId, actor);
        long tripId = tripIdOf(ceId);
        List<Map<String, Object>> events = ((List<Map<String, Object>>) body.getOrDefault("events", List.of()));
        if (events.isEmpty()) {
            throw ApiException.badRequest("events 不能为空");
        }
        int inserted = 0;
        for (Map<String, Object> e : events) {
            String eventId = str(require(e, "eventId"));
            String sensorNo = str(e.getOrDefault("sensorNo", requireEventByNo(eventNo).get("door_sensor_no")));
            LocalDateTime at = LocalDateTime.parse(str(require(e, "recordedAt")));
            String action = str(require(e, "action")).toUpperCase();
            if (!List.of("OPEN", "CLOSE").contains(action)) {
                throw ApiException.badRequest("舱门动作必须为 OPEN/CLOSE");
            }
            int n = db.jdbc().update("""
                    INSERT INTO door_event (cold_event_id, event_id, sensor_no, recorded_at, action,
                        raw_payload, received_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (cold_event_id, event_id) DO NOTHING
                    """, ceId, eventId, sensorNo, Timestamp.valueOf(at), action,
                    String.valueOf(e.getOrDefault("raw", e)), Timestamp.valueOf(db.now()));
            if (n > 0) inserted++;
        }
        if (inserted > 0) {
            db.event(tripId, "DOOR_CALLBACK", "CARRIER", actor,
                    "舱门传感器回调入库 " + inserted + "/" + events.size() + " 条（重复 eventId 自动去重）");
        }
        Map<String, Object> view = eventView(ceId);
        view.put("callback", Map.of("type", "DOOR", "received", events.size(),
                "newInserted", inserted, "duplicated", events.size() - inserted));
        return view;
    }

    /** 电子铅封回调。events: [{eventId, sealNo, recordedAt, sealStatus: SEALED/UNSEALED/ALARM}] */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingestSeal(String eventNo, Map<String, Object> body, String actor) {
        long ceId = ((Number) requireEventByNo(eventNo).get("id")).longValue();
        assertCarrierOwnership(ceId, actor);
        long tripId = tripIdOf(ceId);
        List<Map<String, Object>> events = ((List<Map<String, Object>>) body.getOrDefault("events", List.of()));
        if (events.isEmpty()) {
            throw ApiException.badRequest("events 不能为空");
        }
        int inserted = 0;
        for (Map<String, Object> e : events) {
            String eventId = str(require(e, "eventId"));
            String sealNo = str(e.getOrDefault("sealNo", requireEventByNo(eventNo).get("e_seal_no")));
            LocalDateTime at = LocalDateTime.parse(str(require(e, "recordedAt")));
            String sealStatus = str(require(e, "sealStatus")).toUpperCase();
            int n = db.jdbc().update("""
                    INSERT INTO seal_event (cold_event_id, event_id, seal_no, recorded_at, seal_status,
                        raw_payload, received_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (cold_event_id, event_id) DO NOTHING
                    """, ceId, eventId, sealNo, Timestamp.valueOf(at), sealStatus,
                    String.valueOf(e.getOrDefault("raw", e)), Timestamp.valueOf(db.now()));
            if (n > 0) {
                inserted++;
                if ("ALARM".equals(sealStatus)) {
                    handleSealAlarm(ceId, tripId, eventId, sealNo, at, actor);
                }
            }
        }
        if (inserted > 0) {
            db.event(tripId, "SEAL_CALLBACK", "CARRIER", actor,
                    "电子铅封回调入库 " + inserted + "/" + events.size() + " 条（重复 eventId 自动去重）");
        }
        Map<String, Object> view = eventView(ceId);
        view.put("callback", Map.of("type", "SEAL", "received", events.size(),
                "newInserted", inserted, "duplicated", events.size() - inserted));
        return view;
    }

    private void handleSealAlarm(long ceId, long tripId, String eventId, String sealNo,
                                 LocalDateTime at, String actor) {
        // 幂等：同一铅封告警事件只产生一张工单、一次批次冻结
        String ticketKey = "SEAL:" + eventId;
        String desc = "电子铅封 " + sealNo + " 于 " + at + " 上报告警（运输途中异常开启/破坏风险）";
        Long ticketId = findTicket(ceId, ticketKey);
        if (ticketId == null) {
            ticketId = createTicket(ceId, null, null, "SEAL_ABNORMAL", "HIGH",
                    "电子铅封异常：" + sealNo + " @ " + at, ticketKey, desc);
            db.event(tripId, "INCIDENT_SEAL_ALARM", "CARRIER", actor,
                    "铅封告警自动建单 #" + ticketId + "，相关批次正常入库结算已冻结");
        }
        freezeBatches(ceId, ticketId, "SEAL",
                "电子铅封异常 " + sealNo + " @ " + at + "，待补证核验", tripId);
    }

    // ============================================================
    // 卸货扫描批次核对
    // ============================================================

    /** 月台卸货扫码。batches: [{batchNo, scannedPieces, scannedBy}] */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> unloadScan(String tripCode, Map<String, Object> body, String actor) {
        long ceId = eventIdByTripCode(tripCode);
        long tripId = db.tripId(tripCode);
        List<Map<String, Object>> batches = ((List<Map<String, Object>>) body.getOrDefault("batches", List.of()));
        if (batches.isEmpty()) {
            throw ApiException.badRequest("batches 不能为空");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> b : batches) {
            String batchNo = str(require(b, "batchNo"));
            int scanned = ((Number) require(b, "scannedPieces")).intValue();
            String scannedBy = b.get("scannedBy") == null ? actor : str(b.get("scannedBy"));

            // 预约批次是否存在
            List<Map<String, Object>> expectedRows = db.jdbc().queryForList(
                    "SELECT id, expected_pieces FROM inbound_event WHERE cold_event_id = ? AND batch_no = ?",
                    ceId, batchNo);
            boolean batchExpected = !expectedRows.isEmpty();
            int expected = batchExpected ? ((Number) expectedRows.get(0).get("expected_pieces")).intValue() : 0;
            boolean countMatch = batchExpected && scanned == expected;
            String note = !batchExpected ? "扫描到预约单中不存在的批次"
                    : (countMatch ? "件数一致" : "件数不一致：预约 " + expected + "，实扫 " + scanned);

            int n = db.jdbc().update("""
                    INSERT INTO unload_batch (cold_event_id, batch_no, scanned_pieces, scanned_by,
                        scanned_at, matched, mismatch_note)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (cold_event_id, batch_no) DO UPDATE
                    SET scanned_pieces = EXCLUDED.scanned_pieces, scanned_by = EXCLUDED.scanned_by,
                        scanned_at = EXCLUDED.scanned_at, matched = EXCLUDED.matched,
                        mismatch_note = EXCLUDED.mismatch_note
                    """, ceId, batchNo, scanned, scannedBy, Timestamp.valueOf(db.now()),
                    countMatch, note);
            results.add(Map.of("batchNo", batchNo, "expectedPieces", expected,
                    "scannedPieces", scanned, "matched", countMatch, "note", note, "inserted", n > 0));

            if (!countMatch) {
                String key = "BATCH:" + batchNo;
                Long ticketId = findTicket(ceId, key);
                String title = "卸货批次不符：" + batchNo + "（预约 " + expected + " / 实扫 " + scanned + "）";
                if (ticketId == null) {
                    ticketId = createTicket(ceId, null, batchNo, "BATCH_MISMATCH", "HIGH", title, key,
                            "卸货扫描与预约批次不一致：" + note + "，扫描人 " + scannedBy);
                    db.event(tripId, "INCIDENT_BATCH_MISMATCH", "PARK", actor,
                            "卸货扫描批次 " + batchNo + " 与预约不一致，自动建单 #" + ticketId
                                    + " 并冻结该批次正常入库结算");
                } else {
                    db.jdbc().update(
                            "UPDATE anomaly_ticket SET title = ?, updated_at = ? WHERE id = ?",
                            title, Timestamp.valueOf(db.now()), ticketId);
                }
                // 只冻结不符的这一个批次
                freezeOneBatch(ceId, batchNo, ticketId, "BATCH_MISMATCH",
                        "卸货扫描与预约批次不一致：" + note, tripId);
            } else {
                // 一致：若该批次曾因批次不符被冻结，保持冻结需人工闭环，不在此处自动解除
                db.event(tripId, "UNLOAD_BATCH_MATCHED", "PARK", actor,
                        "卸货批次 " + batchNo + " 扫码 " + scanned + " 件，与预约一致");
            }
        }
        Map<String, Object> view = eventView(ceId);
        view.put("scanResults", results);
        return view;
    }

    // ============================================================
    // 温度区间分析：离线 vs 超温，多区间合并
    // ============================================================

    record Point(LocalDateTime t, BigDecimal c) {}

    /** 复盘用中间区间，携带合并次数。 */
    static final class MInterval {
        LocalDateTime start, end;
        String kind;
        BigDecimal maxC;
        int gapMinutes;
        int mergedCount = 1;
        MInterval(LocalDateTime start, LocalDateTime end, String kind, BigDecimal maxC, int gapMinutes) {
            this.start = start; this.end = end; this.kind = kind;
            this.maxC = maxC; this.gapMinutes = gapMinutes;
        }
    }

    /**
     * 基于当前全量温度曲线重算异常区间，并对新增区间建单/冻结。
     * 重复回调/并发重放时区间唯一约束 + 工单 business_key 保证不重复。
     */
    public List<Map<String, Object>> analyzeTemperature(long ceId, String actor) {
        long tripId = tripIdOf(ceId);
        // 锁定冷链事件行，串行化并发回调的分析过程
        db.jdbc().queryForObject("SELECT id FROM cold_event WHERE id = ? FOR UPDATE", Long.class, ceId);

        int offlineGapMin = db.settingInt("SENSOR_OFFLINE_MINUTES", 45);
        int mergeWindowMin = db.settingInt("INTERVAL_MERGE_MINUTES", 30);
        BigDecimal limit = db.jdbc().queryForObject("""
                SELECT MIN(tc.max_temp_celsius) FROM trip_cargo tc
                JOIN cold_event ce ON ce.trip_id = tc.trip_id WHERE ce.id = ?
                """, BigDecimal.class, ceId);

        List<Point> points = db.jdbc().queryForList(
                "SELECT recorded_at, celsius FROM temp_reading WHERE trip_id = ? ORDER BY recorded_at",
                tripId).stream()
                .map(r -> new Point(((Timestamp) r.get("recorded_at")).toLocalDateTime(),
                        (BigDecimal) r.get("celsius")))
                .sorted(Comparator.comparing(Point::t))
                .toList();

        List<MInterval> raws = new ArrayList<>();
        // 1) 真实超温：连续超限读数合并为一段
        int i = 0;
        while (i < points.size()) {
            if (limit != null && points.get(i).c().compareTo(limit) > 0) {
                LocalDateTime start = points.get(i).t();
                BigDecimal max = points.get(i).c();
                int j = i;
                while (j + 1 < points.size()
                        && points.get(j + 1).c().compareTo(limit) > 0) {
                    j++;
                    if (points.get(j).c().compareTo(max) > 0) max = points.get(j).c();
                }
                raws.add(new MInterval(start, points.get(j).t(), "EXCURSION", max, 0));
                i = j + 1;
            } else {
                i++;
            }
        }
        // 2) 传感器离线：相邻数据间隔超过阈值（数据断点）
        for (int k = 0; k < points.size() - 1; k++) {
            long gap = Duration.between(points.get(k).t(), points.get(k + 1).t()).toMinutes();
            if (gap > offlineGapMin) {
                raws.add(new MInterval(points.get(k).t(), points.get(k + 1).t(),
                        "OFFLINE", null, (int) gap));
            }
        }
        // 3) 相邻区间合并（间隔 ≤ mergeWindowMin 或时间重叠），异类合并为 MIXED
        List<MInterval> merged = mergeIntervals(raws, mergeWindowMin);

        // 4) 落库（唯一键 ce+start+end+kind）
        List<Long> liveIds = new ArrayList<>();
        for (MInterval iv : merged) {
            liveIds.add(upsertInterval(ceId, iv, limit));
        }
        supersedeStaleIntervals(ceId, liveIds, merged);

        // 5) 对每个现存异常区间保证存在对应工单与批次冻结（幂等）
        List<Map<String, Object>> intervals = db.jdbc().queryForList(
                "SELECT * FROM temp_anomaly_interval WHERE cold_event_id = ? AND status <> 'CLOSED' ORDER BY start_at",
                ceId);
        for (Map<String, Object> iv : intervals) {
            long intervalId = ((Number) iv.get("id")).longValue();
            String kind = (String) iv.get("kind");
            LocalDateTime start = ((Timestamp) iv.get("start_at")).toLocalDateTime();
            if (kind.equals("EXCURSION") || kind.equals("MIXED")) {
                String key = "TEMP:" + start;
                Long ticketId = findTicket(ceId, key);
                if (ticketId == null) {
                    ticketId = createTicket(ceId, intervalId, null, "TEMP_EXCURSION", "HIGH",
                            "温度超限区间 " + start + "（" + kind + "）", key,
                            "真实超温：" + iv.get("evidence_summary"));
                    db.jdbc().update("UPDATE anomaly_ticket SET interval_id = ? WHERE id = ?",
                            intervalId, ticketId);
                    db.event(tripId, "INCIDENT_TEMP_EXCURSION_F4", "DISPATCH", "系统",
                            "检出真实超温区间并自动建单 #" + ticketId + "，相关批次正常入库结算已冻结");
                }
                freezeByInterval(ceId, intervalId, ticketId,
                        "温度异常区间 " + start + "（" + kind + "）待复盘", tripId);
            }
            if (kind.equals("OFFLINE") || kind.equals("MIXED")) {
                String key = "GAP:" + start;
                Long ticketId = findTicket(ceId, key);
                if (ticketId == null) {
                    ticketId = createTicket(ceId, intervalId, null, "DATA_GAP", "MED",
                            "温度数据断点区间 " + start + "（" + kind + "）", key,
                            "传感器离线/数据断点：" + iv.get("evidence_summary"));
                    db.jdbc().update("UPDATE anomaly_ticket SET interval_id = ? WHERE id = ?",
                            intervalId, ticketId);
                    db.event(tripId, "INCIDENT_DATA_GAP_F4", "DISPATCH", "系统",
                            "检出温度数据断点区间并自动建单 #" + ticketId + "，需区分传感器离线与真实超温");
                }
                freezeByInterval(ceId, intervalId, ticketId,
                        "温度数据断点区间 " + start + " 待复盘", tripId);
            }
        }
        return intervals;
    }

    private List<MInterval> mergeIntervals(List<MInterval> raws, int mergeWindowMin) {
        List<MInterval> sorted = raws.stream()
                .sorted(Comparator.comparing((MInterval x) -> x.start).thenComparing(x -> x.end))
                .toList();
        List<MInterval> out = new ArrayList<>();
        for (MInterval r : sorted) {
            if (out.isEmpty()) {
                out.add(r);
                continue;
            }
            MInterval last = out.get(out.size() - 1);
            long gap = Duration.between(last.end, r.start).toMinutes();
            boolean overlap = !r.start.isAfter(last.end);
            if (overlap || gap <= mergeWindowMin) {
                if (r.start.isBefore(last.start)) last.start = r.start;
                if (r.end.isAfter(last.end)) last.end = r.end;
                if (!last.kind.equals(r.kind)) last.kind = "MIXED";
                if (r.maxC != null) {
                    last.maxC = (last.maxC == null || r.maxC.compareTo(last.maxC) > 0) ? r.maxC : last.maxC;
                }
                last.gapMinutes += r.gapMinutes;
                last.mergedCount += r.mergedCount;
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private Long upsertInterval(long ceId, MInterval iv, BigDecimal limit) {
        String summary = switch (iv.kind) {
            case "EXCURSION" -> "连续超限读数，最高 " + iv.maxC + "℃（上限 " + limit + "℃）";
            case "OFFLINE" -> "相邻温度点间隔 " + iv.gapMinutes + " 分钟无数据";
            default -> "区间内同时存在超温读数与数据断点（混合区间）";
        };
        if (iv.mergedCount > 1) {
            summary += "；由 " + iv.mergedCount + " 个相邻异常区间自动合并";
        }
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                SELECT id FROM temp_anomaly_interval
                WHERE cold_event_id = ? AND start_at = ? AND end_at = ? AND kind = ?
                """, ceId, Timestamp.valueOf(iv.start), Timestamp.valueOf(iv.end), iv.kind);
        if (!rows.isEmpty()) {
            long id = ((Number) rows.get(0).get("id")).longValue();
            db.jdbc().update("""
                    UPDATE temp_anomaly_interval
                    SET max_celsius = ?, limit_celsius = ?, gap_minutes = ?, merged_count = ?,
                        evidence_summary = ?, status = CASE WHEN status='CLOSED' THEN 'OPEN' ELSE status END,
                        updated_at = ?
                    WHERE id = ?
                    """, iv.maxC, limit, iv.gapMinutes, iv.mergedCount, summary,
                    Timestamp.valueOf(db.now()), id);
            return id;
        }
        return db.jdbc().queryForObject("""
                INSERT INTO temp_anomaly_interval (cold_event_id, start_at, end_at, kind, max_celsius,
                    limit_celsius, gap_minutes, merged_count, evidence_summary, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                RETURNING id
                """, Long.class, ceId, Timestamp.valueOf(iv.start), Timestamp.valueOf(iv.end),
                iv.kind, iv.maxC, limit, iv.gapMinutes, iv.mergedCount, summary,
                Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()));
    }

    /**
     * 旧区间在新曲线中已消失（被更大区间合并）：有工单/证据的保留为 CLOSED 可审计，
     * 并把其工单迁移到覆盖它的现存区间；无任何人工数据且无覆盖区间的空区间直接删除。
     */
    private void supersedeStaleIntervals(long ceId, List<Long> liveIds, List<MInterval> merged) {
        List<Map<String, Object>> stale = db.jdbc().queryForList("""
                SELECT * FROM temp_anomaly_interval
                WHERE cold_event_id = ? AND status <> 'CLOSED'
                """, ceId);
        for (Map<String, Object> s : stale) {
            long sid = ((Number) s.get("id")).longValue();
            if (liveIds.contains(sid)) continue;
            LocalDateTime sStart = ((Timestamp) s.get("start_at")).toLocalDateTime();
            LocalDateTime sEnd = ((Timestamp) s.get("end_at")).toLocalDateTime();
            Long cover = null;
            for (MInterval m : merged) {
                if (!m.start.isAfter(sStart) && !m.end.isBefore(sEnd)) {
                    cover = findIntervalId(ceId, m);
                    break;
                }
            }
            Integer linkedTickets = db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM anomaly_ticket WHERE interval_id = ?", Integer.class, sid);
            if ((linkedTickets == null || linkedTickets == 0) && cover == null) {
                db.jdbc().update("DELETE FROM temp_anomaly_interval WHERE id = ?", sid);
                continue;
            }
            db.jdbc().update(
                    "UPDATE temp_anomaly_interval SET status='CLOSED', conclusion='SUPERSEDED', updated_at=? WHERE id=?",
                    Timestamp.valueOf(db.now()), sid);
            if (cover != null) {
                db.jdbc().update("UPDATE anomaly_ticket SET interval_id = ?, updated_at = ? WHERE interval_id = ?",
                        cover, Timestamp.valueOf(db.now()), sid);
            }
        }
    }

    private Long findIntervalId(long ceId, MInterval m) {
        List<Map<String, Object>> r = db.jdbc().queryForList("""
                SELECT id FROM temp_anomaly_interval
                WHERE cold_event_id = ? AND start_at = ? AND end_at = ? AND kind = ?
                """, ceId, Timestamp.valueOf(m.start), Timestamp.valueOf(m.end), m.kind);
        return r.isEmpty() ? null : ((Number) r.get(0).get("id")).longValue();
    }

    // ============================================================
    // 工单 / 冻结 幂等原语
    // ============================================================

    public Long findTicket(long ceId, String businessKey) {
        List<Map<String, Object>> r = db.jdbc().queryForList(
                "SELECT id FROM anomaly_ticket WHERE cold_event_id = ? AND business_key = ?",
                ceId, businessKey);
        return r.isEmpty() ? null : ((Number) r.get(0).get("id")).longValue();
    }

    public long createTicket(long ceId, Long intervalId, String batchNo, String category,
                             String severity, String title, String businessKey, String description) {
        int decisionHours = db.settingInt("CUSTOMER_DECISION_HOURS", 24);
        long ticketId = db.jdbc().queryForObject("""
                INSERT INTO anomaly_ticket (cold_event_id, interval_id, batch_no, ticket_no, category,
                    title, severity, status, business_key, responsibility, due_at, created_at, updated_at)
                VALUES (?,?,?,?,?,? ,?, 'OPEN', ?, 'PENDING', ?, ?, ?)
                RETURNING id
                """, Long.class, ceId, intervalId, batchNo, nextTicketNo(), category, title,
                severity, businessKey, Timestamp.valueOf(db.now().plusHours(decisionHours)),
                Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()));
        markEventFrozen(ceId, "异常工单 " + category + " 自动冻结相关批次正常入库结算");
        return ticketId;
    }

    private void markEventFrozen(long ceId, String reason) {
        db.jdbc().update("""
                UPDATE cold_event SET frozen = TRUE, frozen_at = COALESCE(frozen_at, ?),
                    frozen_reason = ?, updated_at = ? WHERE id = ? AND frozen = FALSE
                """, Timestamp.valueOf(db.now()), reason, Timestamp.valueOf(db.now()), ceId);
    }

    /** 温度/铅封类异常冻结该冷链事件下全部预约批次；幂等（同批次同类别仅一条 FROZEN）。 */
    private void freezeBatches(long ceId, Long ticketId, String category, String reason, long tripId) {
        List<Map<String, Object>> batches = db.jdbc().queryForList(
                "SELECT batch_no FROM inbound_event WHERE cold_event_id = ?", ceId);
        for (Map<String, Object> b : batches) {
            freezeOneBatch(ceId, (String) b.get("batch_no"), ticketId, category, reason, tripId);
        }
    }

    /** 按异常区间冻结全部预约批次（不同区间各自一条冻结，互不覆盖）。 */
    private void freezeByInterval(long ceId, long intervalId, Long ticketId, String reason, long tripId) {
        List<Map<String, Object>> batches = db.jdbc().queryForList(
                "SELECT batch_no FROM inbound_event WHERE cold_event_id = ?", ceId);
        for (Map<String, Object> b : batches) {
            String batchNo = (String) b.get("batch_no");
            String key = "INTERVAL:" + intervalId + ":" + batchNo;
            int n = db.jdbc().update("""
                    INSERT INTO settlement_freeze (cold_event_id, batch_no, ticket_id, status, reason,
                        business_key, frozen_by, frozen_at)
                    VALUES (?,?,?, 'FROZEN', ?, ?, '系统', ?)
                    ON CONFLICT (cold_event_id, business_key) DO NOTHING
                    """, ceId, batchNo, ticketId, reason, key, Timestamp.valueOf(db.now()));
            if (n > 0) {
                db.event(tripId, "SETTLEMENT_FROZEN", "SETTLE", "系统",
                        "批次 " + batchNo + " 正常入库结算已冻结（异常区间 #" + intervalId + "）：" + reason);
            }
        }
    }

    private void freezeOneBatch(long ceId, String batchNo, Long ticketId, String category,
                                String reason, long tripId) {
        String key = category + ":" + batchNo;
        int n = db.jdbc().update("""
                INSERT INTO settlement_freeze (cold_event_id, batch_no, ticket_id, status, reason,
                    business_key, frozen_by, frozen_at)
                VALUES (?,?,?, 'FROZEN', ?, ?, '系统', ?)
                ON CONFLICT (cold_event_id, business_key) DO NOTHING
                """, ceId, batchNo, ticketId, reason, key, Timestamp.valueOf(db.now()));
        if (n > 0) {
            db.event(tripId, "SETTLEMENT_FROZEN", "SETTLE", "系统",
                    "批次 " + batchNo + " 正常入库结算已冻结（" + category + "）：" + reason);
        }
    }

    // ============================================================
    // 读模型
    // ============================================================

    public long tripIdOf(long ceId) {
        return db.jdbc().queryForObject("SELECT trip_id FROM cold_event WHERE id = ?",
                Long.class, ceId);
    }

    public Map<String, Object> eventView(long ceId) {
        Map<String, Object> ev = db.jdbc().queryForMap("SELECT * FROM cold_event WHERE id = ?", ceId);
        long tripId = ((Number) ev.get("trip_id")).longValue();
        ev.put("appointment", db.jdbc().queryForMap(
                "SELECT code, vehicle_plate, temp_device_no, e_seal_no, window_start, status, current_stage " +
                "FROM trip WHERE id = ?", tripId));
        ev.put("inboundBatches", db.jdbc().queryForList(
                "SELECT * FROM inbound_event WHERE cold_event_id = ? ORDER BY id", ceId));
        ev.put("unloadBatches", db.jdbc().queryForList(
                "SELECT * FROM unload_batch WHERE cold_event_id = ? ORDER BY id", ceId));
        ev.put("chillerEvents", db.jdbc().queryForList(
                "SELECT event_id, device_no, recorded_at, payload_status, celsius FROM chiller_event " +
                "WHERE cold_event_id = ? ORDER BY recorded_at", ceId));
        ev.put("doorEvents", db.jdbc().queryForList(
                "SELECT event_id, sensor_no, recorded_at, action FROM door_event " +
                "WHERE cold_event_id = ? ORDER BY recorded_at", ceId));
        ev.put("sealEvents", db.jdbc().queryForList(
                "SELECT event_id, seal_no, recorded_at, seal_status FROM seal_event " +
                "WHERE cold_event_id = ? ORDER BY recorded_at", ceId));
        ev.put("temperatureIntervals", db.jdbc().queryForList(
                "SELECT * FROM temp_anomaly_interval WHERE cold_event_id = ? ORDER BY start_at", ceId));
        ev.put("tickets", db.jdbc().queryForList(
                "SELECT * FROM anomaly_ticket WHERE cold_event_id = ? ORDER BY id", ceId));
        ev.put("freezes", db.jdbc().queryForList(
                "SELECT * FROM settlement_freeze WHERE cold_event_id = ? ORDER BY id", ceId));
        ev.put("temperatureReadings", db.jdbc().queryForList(
                "SELECT recorded_at, celsius, gap_after FROM temp_reading WHERE trip_id = ? ORDER BY recorded_at",
                tripId));
        ev.put("timeline", db.jdbc().queryForList(
                "SELECT event_type, actor_role, actor_name, detail, created_at FROM trip_event " +
                "WHERE trip_id = ? ORDER BY created_at, id", tripId));
        return ev;
    }

    public Map<String, Object> eventViewByTripCode(String tripCode) {
        return eventView(eventIdByTripCode(tripCode));
    }

    private String nextTicketNo() {
        return "TK" + String.format("%013d",
                db.jdbc().queryForObject("SELECT nextval('anomaly_ticket_no_seq')", Long.class));
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
