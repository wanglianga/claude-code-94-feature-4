package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 闸口核验：预约有效性、车牌、电子封签、司机证件、晚到、月台拥堵、限电。
 * 不满足条件的车辆进入 QUEUED_AT_GATE（排队）并在同一车次登记异常，
 * 由调度处置后再放行入园。
 */
@Service
public class GateService {

    private final Db db;
    private final SchedulingService scheduling;
    private final ColdEventService coldEvents;

    public GateService(Db db, SchedulingService scheduling, ColdEventService coldEvents) {
        this.db = db;
        this.scheduling = scheduling;
        this.coldEvents = coldEvents;
    }

    @Transactional
    public Map<String, Object> checkIn(Map<String, Object> body, String actor) {
        String code = String.valueOf(body.get("code")).trim();
        String plate = String.valueOf(body.getOrDefault("vehiclePlate", "")).trim().toUpperCase();
        String seal = String.valueOf(body.getOrDefault("eSealNo", "")).trim();

        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        String status = (String) trip.get("status");
        LocalDateTime now = db.now();

        if (List.of("AT_DOCK", "IN_QC", "QUARANTINED", "AWAIT_DISPOSITION", "SETTLING", "DONE").contains(status)) {
            throw ApiException.conflict("车辆已在园内（状态 " + status + "），无需重复刷卡入园");
        }
        if ("CANCELLED".equals(status)) {
            throw ApiException.conflict("预约已取消");
        }

        // 1) 车牌核验
        boolean plateOk = plate.equals(trip.get("vehicle_plate"));
        // 2) 封签核验
        boolean sealOk = seal.equalsIgnoreCase((String) trip.get("e_seal_no"));

        // 3) 晚到判定（超过窗口结束 + 容忍分钟）。早到不拦截，可在等待区等候。
        int tolerance = db.settingInt("LATE_TOLERANCE_MINUTES", 30);
        LocalDateTime windowEnd = ((Timestamp) trip.get("window_end")).toLocalDateTime();
        LocalDateTime windowStart = ((Timestamp) trip.get("window_start")).toLocalDateTime();
        boolean late = now.isAfter(windowEnd.plusMinutes(tolerance));

        boolean docsBlocked = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id = ? AND type = 'DOCS_EXPIRED' AND status = 'OPEN'",
                Integer.class, tripId) > 0;
        boolean sealIncidentOpen = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id = ? AND type = 'SEAL_ABNORMAL' AND status = 'OPEN'",
                Integer.class, tripId) > 0;
        boolean lateIncidentOpen = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id = ? AND type = 'LATE_ARRIVAL' AND status = 'OPEN'",
                Integer.class, tripId) > 0;

        if (!plateOk) {
            db.raiseIncident(tripId, "SEAL_ABNORMAL", "HIGH",
                    "闸口车牌不符：预约车牌 " + trip.get("vehicle_plate") + "，到场车牌 " + plate,
                    "CARRIER", "DISPATCH");
            db.event(tripId, "GATE_REJECT_PLATE", "DISPATCH", actor, "闸口核验失败：车牌不符，拦截在园外");
            throw ApiException.unprocessable("车牌与预约不符，已拦截并登记异常，请调度核验");
        }
        if (!sealOk && !sealIncidentOpen) {
            db.raiseIncident(tripId, "SEAL_ABNORMAL", "HIGH",
                    "闸口电子封签不符：预约封签 " + trip.get("e_seal_no") + "，到场封签 " + seal,
                    "PENDING", "DISPATCH");
            db.event(tripId, "GATE_REJECT_SEAL", "DISPATCH", actor,
                    "闸口核验失败：电子封签异常，车辆隔离等待，调度可现场核验后解除");
        }
        if (late && !lateIncidentOpen) {
            db.raiseIncident(tripId, "LATE_ARRIVAL", "MED",
                    "车辆晚到：窗口 " + windowStart + "~" + windowEnd +
                    "，到场 " + now + "，超出容忍 " + tolerance + " 分钟，需调度重新排窗",
                    "CARRIER", "DISPATCH");
            db.event(tripId, "GATE_LATE", "DISPATCH", actor, "车辆晚到，需调度改约后入园");
        }

        // 4) 限电：若当前限电且分配月台无自备发电，需调度改月台
        boolean powerLimited = db.settingBool("POWER_LIMIT", false);
        Long dockId = ((Number) trip.get("dock_id")).longValue();
        Map<String, Object> dock = db.jdbc().queryForMap(
                "SELECT code, night_open, backup_power FROM dock WHERE id = ?", dockId);
        boolean powerBlocked = powerLimited && !Boolean.TRUE.equals(dock.get("backup_power"));

        // 5) 月台拥堵：月台当前有在园车
        boolean dockBusy = scheduling.dockOccupiedNow(dockId, tripId);
        if (dockBusy) {
            boolean congestionOpen = db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM incident WHERE trip_id = ? AND type = 'DOCK_CONGESTION' AND status = 'OPEN'",
                    Integer.class, tripId) > 0;
            if (!congestionOpen) {
                db.raiseIncident(tripId, "DOCK_CONGESTION", "LOW",
                        "月台 " + dock.get("code") + " 被占用，车辆在闸口排队等待", "PARK", "DISPATCH");
            }
            db.event(tripId, "GATE_QUEUE", "DISPATCH", actor,
                    "月台 " + dock.get("code") + " 拥堵，车辆进入闸口夜间/日间排队序列");
        }

        boolean blocked = !sealOk || docsBlocked || late || powerBlocked || dockBusy;

        if (!blocked) {
            return admit(tripId, actor, sealOk);
        }

        // 进入排队/等待状态
        db.updateTrip(tripId,
                "status = 'QUEUED_AT_GATE', queued = TRUE, current_stage = 'GATE', arrived_at = COALESCE(arrived_at, ?), " +
                "gate_plate_ok = ?, gate_seal_ok = ?, late = ?, stage_entered_at = ?",
                Timestamp.valueOf(now), plateOk, sealOk, late, Timestamp.valueOf(now));

        Map<String, Object> view = db.getTripByCode(code);
        view.put("admitted", false);
        view.put("reasons", java.util.stream.Stream.of(
                !sealOk ? "封签异常待调度核验" : null,
                docsBlocked ? "司机证件过期" : null,
                late ? "晚到需调度改约" : null,
                powerBlocked ? "园区限电，当前月台无自备发电，需改月台" : null,
                dockBusy ? "月台拥堵排队中" : null
        ).filter(java.util.Objects::nonNull).toList());
        view.put("queuePosition", queuePosition(tripId));
        return view;
    }

    private Map<String, Object> admit(long tripId, String actor, boolean sealOk) {
        String code = db.jdbc().queryForObject("SELECT code FROM trip WHERE id = ?", String.class, tripId);
        db.updateTrip(tripId,
                "status = 'AT_GATE', queued = FALSE, current_stage = 'GATE', gate_plate_ok = TRUE, " +
                "gate_seal_ok = ?, arrived_at = COALESCE(arrived_at, ?), stage_entered_at = ?",
                sealOk, Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()));
        // 入园即解除月台拥堵排队异常
        db.jdbc().update("""
                UPDATE incident SET status = 'RESOLVED', responsibility = 'PARK',
                    resolution = '月台已空闲，车辆放行入园', resolved_by = ?, resolved_at = ?
                WHERE trip_id = ? AND type = 'DOCK_CONGESTION' AND status = 'OPEN'
                """, actor, Timestamp.valueOf(db.now()), tripId);
        db.event(tripId, "GATE_ADMITTED", "DISPATCH", actor, "闸口核验通过（预约/车牌/封签一致），放行入园");
        // feature-4-b：车辆入园后自动聚合冷链事件时间线（预约单/车辆司机/冷机/铅封/温度/卸货批次）
        coldEvents.ensureForTrip(tripId, actor);
        Map<String, Object> view = db.getTripByCode(code);
        view.put("admitted", true);
        view.put("reasons", List.of());
        return view;
    }

    /** 调度处置完封签/晚到等异常、月台空闲后，尝试让排队车辆入园。 */
    @Transactional
    public Map<String, Object> admitFromQueue(String code, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        if (!"QUEUED_AT_GATE".equals(trip.get("status"))) {
            throw ApiException.conflict("车辆不在闸口排队状态");
        }
        boolean sealOpen = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id = ? AND type = 'SEAL_ABNORMAL' AND status='OPEN'",
                Integer.class, tripId) > 0;
        boolean docsOpen = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id = ? AND type = 'DOCS_EXPIRED' AND status='OPEN'",
                Integer.class, tripId) > 0;
        boolean lateOpen = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident WHERE trip_id = ? AND type = 'LATE_ARRIVAL' AND status='OPEN'",
                Integer.class, tripId) > 0;
        if (sealOpen || docsOpen || lateOpen) {
            throw ApiException.unprocessable("仍有未闭环异常（封签/证件/晚到），请先处置或改约");
        }
        boolean powerLimited = db.settingBool("POWER_LIMIT", false);
        Map<String, Object> dock = db.jdbc().queryForMap(
                "SELECT backup_power FROM dock WHERE id = ?", ((Number) trip.get("dock_id")).longValue());
        if (powerLimited && !Boolean.TRUE.equals(dock.get("backup_power"))) {
            throw ApiException.unprocessable("园区限电中，需先改约到自备发电月台");
        }
        if (scheduling.dockOccupiedNow(((Number) trip.get("dock_id")).longValue(), tripId)) {
            throw ApiException.unprocessable("月台仍被占用，保持排队");
        }
        return admit(tripId, actor, Boolean.TRUE.equals(trip.get("gate_seal_ok")) || !sealOpen);
    }

    public List<Map<String, Object>> queue() {
        return db.jdbc().queryForList("""
                SELECT t.code, t.vehicle_plate, t.temp_zone, t.window_start, t.urgent, t.priority_score,
                       d.code AS dock_code,
                       (SELECT STRING_AGG(i.type, ',') FROM incident i
                        WHERE i.trip_id = t.id AND i.status = 'OPEN') AS open_incidents
                FROM trip t LEFT JOIN dock d ON d.id = t.dock_id
                WHERE t.status = 'QUEUED_AT_GATE'
                ORDER BY t.urgent DESC, t.priority_score DESC, t.window_start
                """);
    }

    private int queuePosition(long tripId) {
        Integer p = db.jdbc().queryForObject("""
                SELECT COUNT(*) + 1 FROM trip
                WHERE status = 'QUEUED_AT_GATE'
                  AND (urgent = TRUE OR priority_score >= (SELECT priority_score FROM trip WHERE id = ?))
                  AND window_start <= (SELECT window_start FROM trip WHERE id = ?)
                  AND id <> ?
                """, Integer.class, tripId, tripId, tripId);
        return p == null ? 1 : p;
    }
}
