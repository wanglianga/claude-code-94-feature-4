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
 * 月台接车与卸货质检：
 * - 接车：车辆从闸口进入月台；
 * - 上传温控设备原始数据：检测曲线断点（相邻点间隔超阈值）与开门前温度是否超限；
 * - 卸货：开门前温度、卸货照片、破损件数、卸货时长；
 * - 质检抽样：按每条货主明细记录抽样件数/化冻件数，发现化冻自动登记异常并转隔离待处置。
 */
@Service
public class DockQcService {

    private final Db db;
    private final DetectionService detection;

    public DockQcService(Db db, DetectionService detection) {
        this.db = db;
        this.detection = detection;
    }

    @Transactional
    public Map<String, Object> receiveAtDock(String code, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        String status = (String) trip.get("status");
        if (!List.of("AT_GATE").contains(status)) {
            throw ApiException.conflict("仅已入园（AT_GATE）车辆可由月台接车，当前状态=" + status);
        }
        db.enterStage(tripId, "AT_DOCK", "DOCK");
        db.updateTrip(tripId, "unloading_started_at = ?", Timestamp.valueOf(db.now()));
        db.event(tripId, "DOCK_RECEIVED", "DISPATCH", actor, "月台接车，开始等待卸货，卸货计时开始");
        return db.getTripByCode(code);
    }

    /**
     * 上传温控设备原始数据（可多次上传，追加保留）。
     * body: { readings: [ {recordedAt, celsius}, ... ] }
     * 自动标记曲线断点；若任意点超过该温区货品允许上限，登记温度超限异常。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadTemperature(String code, Map<String, Object> body, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        String deviceNo = (String) trip.get("temp_device_no");
        int gapMinutes = db.settingInt("TEMP_GAP_MINUTES", 45);

        List<Map<String, Object>> incoming = (List<Map<String, Object>>) body.getOrDefault("readings", List.of());
        if (incoming.isEmpty()) {
            throw ApiException.badRequest("readings 不能为空，须上传温控设备原始数据点");
        }

        record Point(LocalDateTime t, BigDecimal c) {}
        List<Point> all = new ArrayList<>(db.jdbc().queryForList(
                "SELECT recorded_at, celsius FROM temp_reading WHERE trip_id = ? ORDER BY recorded_at", tripId)
                .stream().map(r -> new Point(((Timestamp) r.get("recorded_at")).toLocalDateTime(),
                        (BigDecimal) r.get("celsius"))).toList());
        List<Point> newPoints = incoming.stream()
                .map(r -> new Point(LocalDateTime.parse(String.valueOf(r.get("recordedAt"))),
                        new BigDecimal(String.valueOf(r.get("celsius")))))
                .sorted(Comparator.comparing(Point::t))
                .toList();

        int inserted = 0;
        for (Point p : newPoints) {
            Integer dup = db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM temp_reading WHERE trip_id=? AND recorded_at=?",
                    Integer.class, tripId, Timestamp.valueOf(p.t()));
            if (dup != null && dup > 0) continue;
            db.jdbc().update(
                    "INSERT INTO temp_reading (trip_id, device_no, recorded_at, celsius) VALUES (?,?,?,?)",
                    tripId, deviceNo, Timestamp.valueOf(p.t()), p.c());
            all.add(p);
            inserted++;
        }

        all.sort(Comparator.comparing(Point::t));
        // 重新计算断点标记
        db.jdbc().update("UPDATE temp_reading SET gap_after = FALSE WHERE trip_id = ?", tripId);
        int gapCount = 0;
        BigDecimal maxReading = null;
        for (int i = 0; i < all.size() - 1; i++) {
            Point a = all.get(i), b = all.get(i + 1);
            long minutes = Duration.between(a.t(), b.t()).toMinutes();
            if (minutes > gapMinutes) {
                db.jdbc().update("UPDATE temp_reading SET gap_after = TRUE WHERE trip_id = ? AND recorded_at = ?",
                        tripId, Timestamp.valueOf(a.t()));
                gapCount++;
            }
            if (maxReading == null || a.c().compareTo(maxReading) > 0) maxReading = a.c();
        }
        if (!all.isEmpty()) {
            BigDecimal last = all.get(all.size() - 1).c();
            if (maxReading == null || last.compareTo(maxReading) > 0) maxReading = last;
        }

        boolean alreadyGap = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id=? AND type='TEMP_GAP' AND status='OPEN'",
                Integer.class, tripId) > 0;
        if (gapCount > 0 && !alreadyGap) {
            db.raiseIncident(tripId, "TEMP_GAP", "HIGH",
                    "温控曲线存在 " + gapCount + " 处断点（相邻数据间隔 > " + gapMinutes + " 分钟），设备原始数据已保留待查",
                    "PENDING", "CARRIER");
            db.event(tripId, "INCIDENT_TEMP_GAP", "CARRIER", actor,
                    "上传温度数据后系统检出曲线断点 " + gapCount + " 处，已通知调度/质检/客服/结算同车处理");
        }

        // 温度超限（开门前/运输途中超过货品允许最高温）
        BigDecimal limit = db.jdbc().queryForObject(
                "SELECT MIN(max_temp_celsius) FROM trip_cargo WHERE trip_id = ?", BigDecimal.class, tripId);
        boolean excursionOpen = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id=? AND type='TEMP_EXCURSION' AND status='OPEN'",
                Integer.class, tripId) > 0;
        if (limit != null && maxReading != null && maxReading.compareTo(limit) > 0 && !excursionOpen) {
            db.raiseIncident(tripId, "TEMP_EXCURSION", "HIGH",
                    "温控记录最高 " + maxReading + "℃ 超过货品允许上限 " + limit + "℃，存在化冻风险",
                    "PENDING", "CARRIER");
            db.event(tripId, "INCIDENT_TEMP_EXCURSION", "CARRIER", actor,
                    "温度超限：实测最高 " + maxReading + "℃，上限 " + limit + "℃");
        }

        db.event(tripId, "TEMP_UPLOADED", "CARRIER", actor,
                "上传温控原始数据 " + inserted + " 点（累计 " + all.size() + " 点），断点 " + gapCount + " 处，最高温 "
                        + (maxReading == null ? "N/A" : maxReading + "℃"));

        // feature-4：超温合并区间/断点 → 自动冻结批次入库结算 + 创建可并行补证的异常工单（幂等）
        List<Map<String, Object>> anomalies = detection.detectTemperatureAnomalies(tripId);
        if (!anomalies.isEmpty()) {
            db.event(tripId, "FREEZE_AUTO", "SYSTEM", "系统",
                    "检出温度类异常区间 " + anomalies.size() + " 个，相关批次入库结算已自动冻结，异常工单待多方补证");
        }
        return db.getTripByCode(code);
    }

    /**
     * 卸货完成登记：开门前温度、卸货照片、每条明细破损件数。
     * body: { doorOpenTempCelsius, unloadPhotos, cargoDamages: [{cargoId, damagedPieces}] }
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> completeUnload(String code, Map<String, Object> body, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        if (!"AT_DOCK".equals(trip.get("status"))) {
            throw ApiException.conflict("车辆必须在月台（AT_DOCK）才能登记卸货");
        }
        BigDecimal doorTemp = new BigDecimal(String.valueOf(require(body, "doorOpenTempCelsius")));
        String photos = body.get("unloadPhotos") == null ? null : String.valueOf(body.get("unloadPhotos"));
        List<Map<String, Object>> damages = (List<Map<String, Object>>) body.getOrDefault("cargoDamages", List.of());

        db.updateTrip(tripId, "unload_photos = ?, unloading_finished_at = ?",
                photos, Timestamp.valueOf(db.now()));
        // 开门前温度记录到每条货主明细
        db.jdbc().update("UPDATE trip_cargo SET door_open_temp = ? WHERE trip_id = ?", doorTemp, tripId);
        db.event(tripId, "UNLOAD_DONE", "QC", actor,
                "卸货完成，开门前温度 " + doorTemp + "℃，卸货照片=" + (photos == null ? "未上传" : photos)
                        + "，卸货时长 " + unloadingMinutes(trip) + " 分钟");

        int totalDamaged = 0;
        for (Map<String, Object> d : damages) {
            long cargoId = ((Number) require(d, "cargoId")).longValue();
            int damaged = ((Number) require(d, "damagedPieces")).intValue();
            int updated = db.jdbc().update(
                    "UPDATE trip_cargo SET damaged_pieces = ? WHERE id = ? AND trip_id = ?",
                    damaged, cargoId, tripId);
            if (updated == 0) {
                throw ApiException.badRequest("货物明细不属于本车次: " + cargoId);
            }
            totalDamaged += damaged;
        }
        db.enterStage(tripId, "IN_QC", "QC");
        db.event(tripId, "QC_STARTED", "QC", actor,
                "进入质检抽样环节" + (totalDamaged > 0 ? "，待质检区分物理破损 " + totalDamaged + " 件与化冻" : ""));
        return db.getTripByCode(code);
    }

    /**
     * 质检抽样结果。body: { samples: [{cargoId, sampleTotal, sampleThawed}] }
     * 任一明细出现化冻：车次转 QUARANTINED（隔离待处置），否则进入 AWAIT_DISPOSITION 等待放行决定。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> qcSample(String code, Map<String, Object> body, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        if (!"IN_QC".equals(trip.get("status"))) {
            throw ApiException.conflict("车辆必须在质检环节（IN_QC）才能提交抽样结果");
        }
        List<Map<String, Object>> samples = (List<Map<String, Object>>) require(body, "samples");
        boolean anyThaw = false;
        int totalThawed = 0, totalDamage = 0;

        for (Map<String, Object> s : samples) {
            long cargoId = ((Number) require(s, "cargoId")).longValue();
            int sampleTotal = ((Number) require(s, "sampleTotal")).intValue();
            int thawed = ((Number) require(s, "sampleThawed")).intValue();
            Map<String, Object> cargo = db.jdbc().queryForMap(
                    "SELECT tc.*, o.name AS owner_name FROM trip_cargo tc " +
                    "JOIN cargo_owner o ON o.id = tc.owner_id WHERE tc.id = ? AND tc.trip_id = ?",
                    cargoId, tripId);
            int damaged = ((Number) cargo.get("damaged_pieces")).intValue();
            String result = thawed > 0 ? "THAWED" : (damaged > 0 ? "DAMAGE_ONLY" : "PASS");
            db.jdbc().update(
                    "UPDATE trip_cargo SET sample_total=?, sample_thawed=?, qc_result=? WHERE id=?",
                    sampleTotal, thawed, result, cargoId);
            if (thawed > 0) {
                anyThaw = true;
                totalThawed += thawed;
                db.event(tripId, "QC_THAW_FOUND", "QC", actor,
                        "货主[" + cargo.get("owner_name") + "] 货品[" + cargo.get("cargo_type") +
                        "] 抽样 " + sampleTotal + " 件，化冻 " + thawed + " 件，判定 THAWED");
            }
            totalDamage += damaged;
        }

        if (anyThaw) {
            db.raiseIncident(tripId, "THAW_FOUND", "HIGH",
                    "质检发现化冻共 " + totalThawed + " 件，车辆转入隔离区等待放行/降级/拒收处置",
                    "PENDING", "QC");
            db.enterStage(tripId, "QUARANTINED", "QUARANTINE");
            db.jdbc().update("UPDATE trip_cargo SET disposition='QUARANTINED', " +
                    "disposition_note='质检化冻，先行隔离' WHERE trip_id=? AND qc_result='THAWED'", tripId);
            db.event(tripId, "QUARANTINED", "QC", actor, "化冻货品已隔离，等待多方处置决定");
        } else {
            if (totalDamage > 0) {
                db.raiseIncident(tripId, "THAW_FOUND", "LOW",
                        "质检未发现化冻，但有物理破损 " + totalDamage + " 件，按物理破损定责",
                        "PENDING", "QC");
            }
            db.enterStage(tripId, "AWAIT_DISPOSITION", "QC");
            db.event(tripId, "QC_PASSED", "QC", actor,
                    "质检通过（无化冻）" + (totalDamage > 0 ? "，物理破损 " + totalDamage + " 件另行定责" : "")
                            + "，等待放行决定");
        }
        return db.getTripByCode(code);
    }

    private long unloadingMinutes(Map<String, Object> trip) {
        Timestamp start = (Timestamp) trip.get("unloading_started_at");
        if (start == null) return 0;
        return Duration.between(start.toLocalDateTime(), db.now()).toMinutes();
    }

    private static Object require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw ApiException.badRequest("缺少必填字段: " + key);
        }
        return v;
    }
}
