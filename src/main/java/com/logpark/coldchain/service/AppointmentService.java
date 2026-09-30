package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 承运商预约提交与改约。一辆车可装载多个货主的货（多货主混装）；
 * 主温区按货品最高温区要求确定（FROZEN 最严格 < CHILLED < AMBIENT）。
 */
@Service
public class AppointmentService {

    private final Db db;
    private final SchedulingService scheduling;

    public AppointmentService(Db db, SchedulingService scheduling) {
        this.db = db;
        this.scheduling = scheduling;
    }

    private record OwnerInfo(long id, String code, String name, String priority, BigDecimal value) {}

    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> book(Map<String, Object> body, String username) {
        Long carrierId = ((Number) require(body, "carrierId")).longValue();
        Long driverId = ((Number) require(body, "driverId")).longValue();
        String plate = str(require(body, "vehiclePlate")).toUpperCase();
        String deviceNo = str(require(body, "tempDeviceNo"));
        String sealNo = str(require(body, "eSealNo"));
        LocalDateTime requestedAt = LocalDateTime.parse(str(require(body, "requestedAt")));
        boolean urgent = bool(body.getOrDefault("urgent", false));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) require(body, "cargoLines");
        if (lines.isEmpty()) {
            throw ApiException.badRequest("至少填报一条货品明细");
        }

        // 承运商 / 司机校验
        Map<String, Object> carrier = loadById("carrier", carrierId,
                "SELECT code, name, credit_score FROM carrier WHERE id = ?");
        int credit = ((Number) carrier.get("credit_score")).intValue();

        Map<String, Object> driver = loadById("driver", driverId,
                "SELECT name, license_no, license_expire_date, carrier_id FROM driver WHERE id = ?");
        if (((Number) driver.get("carrier_id")).longValue() != carrierId) {
            throw ApiException.badRequest("司机不属于该承运商");
        }
        LocalDate licenseExpire = ((java.sql.Date) driver.get("license_expire_date")).toLocalDate();
        boolean licenseExpired = !licenseExpire.isAfter(db.now().toLocalDate());

        // 解析混装明细，主温区取最严格者
        long distinctOwners = lines.stream().map(l -> l.get("ownerId")).distinct().count();
        boolean mixed = lines.size() > 1 || distinctOwners > 1;
        String mainZone = "AMBIENT";
        int totalPieces = 0;
        int bestOwnerRank = 1;
        for (Map<String, Object> line : lines) {
            String zone = str(line.get("tempZone")).toUpperCase();
            if (!List.of("FROZEN", "CHILLED", "AMBIENT").contains(zone)) {
                throw ApiException.badRequest("温区必须为 FROZEN/CHILLED/AMBIENT");
            }
            mainZone = stricterZone(mainZone, zone);
            totalPieces += ((Number) require(line, "pieces")).intValue();
            OwnerInfo owner = ownerInfo(((Number) require(line, "ownerId")).longValue());
            bestOwnerRank = Math.max(bestOwnerRank, SchedulingService.ownerRank(owner.priority()));
        }

        SchedulingService.WindowPlan plan = scheduling.plan(
                requestedAt, mainZone, urgent, totalPieces, bestOwnerRank, credit, null);

        String code = "T" + db.now().getYear()
                + String.format("%02d", db.now().getMonthValue())
                + String.format("%04d", nextTripSeq());

        long tripId = db.jdbc().queryForObject("""
                INSERT INTO trip (code, carrier_id, driver_id, vehicle_plate, temp_device_no, e_seal_no,
                    requested_at, window_start, window_end, dock_id, temp_zone, estimated_pieces,
                    urgent, mixed_loading, priority_score, status, created_by, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'BOOKED', ?, ?, ?)
                RETURNING id
                """, Long.class,
                code, carrierId, driverId, plate, deviceNo, sealNo,
                Timestamp.valueOf(requestedAt), Timestamp.valueOf(plan.start()), Timestamp.valueOf(plan.end()),
                plan.dockId(), mainZone, totalPieces, urgent, mixed, plan.priorityScore(),
                username, Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()));

        for (Map<String, Object> line : lines) {
            OwnerInfo owner = ownerInfo(((Number) require(line, "ownerId")).longValue());
            long cargoId = db.jdbc().queryForObject("""
                    INSERT INTO trip_cargo (trip_id, owner_id, cargo_type, temp_zone, max_temp_celsius,
                        pieces, value_per_piece, target_warehouse)
                    VALUES (?,?,?,?,?,?,?,?)
                    RETURNING id
                    """, Long.class, tripId, owner.id(),
                    str(require(line, "cargoType")),
                    str(line.get("tempZone")).toUpperCase(),
                    new BigDecimal(str(require(line, "maxTempCelsius"))),
                    ((Number) require(line, "pieces")).intValue(),
                    owner.value(),
                    line.get("targetWarehouse") == null ? null : str(line.get("targetWarehouse")));
            // 预约批次：承运商可按货主明细声明批次（卸货扫描比对基准）；未声明则自动生成单批次
            int pieces = ((Number) require(line, "pieces")).intValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> batches =
                    (List<Map<String, Object>>) line.getOrDefault("batches", List.of());
            if (batches.isEmpty()) {
                db.jdbc().update("""
                        INSERT INTO trip_batch (trip_id, cargo_id, batch_code, expected_qty)
                        VALUES (?,?,?,?) ON CONFLICT (trip_id, batch_code) DO NOTHING
                        """, tripId, cargoId, "B" + cargoId + "-01", pieces);
            } else {
                for (Map<String, Object> b : batches) {
                    db.jdbc().update("""
                            INSERT INTO trip_batch (trip_id, cargo_id, batch_code, expected_qty)
                            VALUES (?,?,?,?) ON CONFLICT (trip_id, batch_code) DO NOTHING
                            """, tripId, cargoId, str(require(b, "batchCode")),
                            ((Number) require(b, "expectedQty")).intValue());
                }
            }
        }

        db.event(tripId, "BOOKED", "CARRIER", username,
                String.format("承运商提交预约，货主数=%d，多货主混装=%s，件数=%d，预约时间=%s，系统生成窗口 %s~%s（月台%s）",
                        distinctOwners, mixed ? "是" : "否", totalPieces, requestedAt,
                        plan.start(), plan.end(), plan.dockCode()));
        if (licenseExpired) {
            db.raiseIncident(tripId, "DOCS_EXPIRED", "HIGH",
                    "司机 " + driver.get("name") + " 证件已于 " + licenseExpire + " 过期，闸口将不予放行",
                    "CARRIER", "CARRIER");
            db.event(tripId, "INCIDENT_DOCS_EXPIRED", "CARRIER", username,
                    "系统检出司机证件过期，已登记异常并通知调度/质检/客服/结算");
        }
        if (urgent) {
            db.raiseIncident(tripId, "URGENT_INSERT", "MED",
                    "客户临时加急，窗口按优先级插队生成", "OWNER", "CARRIER");
        }
        for (String n : plan.notes()) {
            db.event(tripId, "WINDOW_NOTE", "DISPATCH", "系统", n);
        }

        return tripView(code);
    }

    /** 调度改约（拥堵/限电/货主改仓等后重新寻窗）。 */
    @Transactional
    public Map<String, Object> rebook(String code, LocalDateTime requestedAt, Boolean urgent, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        String status = (String) trip.get("status");
        if (List.of("AT_DOCK", "IN_QC", "QUARANTINED", "SETTLING", "DONE").contains(status)) {
            throw ApiException.conflict("车次已进入 " + status + "，不能改约");
        }
        int credit = db.jdbc().queryForObject(
                "SELECT credit_score FROM carrier WHERE id = ?",
                Integer.class, ((Number) trip.get("carrier_id")).longValue());
        List<Map<String, Object>> owners = db.jdbc().queryForList("""
                SELECT DISTINCT o.priority_level FROM trip_cargo tc
                JOIN cargo_owner o ON o.id = tc.owner_id WHERE tc.trip_id = ?
                """, tripId);
        int rank = owners.stream()
                .mapToInt(o -> SchedulingService.ownerRank((String) o.get("priority_level")))
                .max().orElse(1);
        boolean u = urgent != null ? urgent : Boolean.TRUE.equals(trip.get("urgent"));

        SchedulingService.WindowPlan plan = scheduling.plan(requestedAt, (String) trip.get("temp_zone"),
                u, ((Number) trip.get("estimated_pieces")).intValue(), rank, credit, tripId);

        db.updateTrip(tripId,
                "requested_at = ?, window_start = ?, window_end = ?, dock_id = ?, urgent = ?",
                Timestamp.valueOf(requestedAt), Timestamp.valueOf(plan.start()), Timestamp.valueOf(plan.end()),
                plan.dockId(), u);
        // 改约即对“晚到”的调度处置：闭环晚到异常，责任默认承运商（结算环节可改判）
        db.jdbc().update("""
                UPDATE incident SET status = 'RESOLVED', responsibility = 'CARRIER',
                    resolution = '调度已改约至新窗口', resolved_by = ?, resolved_at = ?
                WHERE trip_id = ? AND type = 'LATE_ARRIVAL' AND status = 'OPEN'
                """, actor, Timestamp.valueOf(db.now()), tripId);
        // 新月台具备自备发电时，闭环限电异常
        Boolean backup = db.jdbc().queryForObject(
                "SELECT backup_power FROM dock WHERE id = ?", Boolean.class, plan.dockId());
        if (Boolean.TRUE.equals(backup)) {
            db.jdbc().update("""
                    UPDATE incident SET status = 'RESOLVED', responsibility = 'PARK',
                        resolution = '已改约至自备发电月台 ' || ?, resolved_by = ?, resolved_at = ?
                    WHERE trip_id = ? AND type = 'POWER_LIMIT' AND status = 'OPEN'
                    """, plan.dockCode(), actor, Timestamp.valueOf(db.now()), tripId);
        }
        db.event(tripId, "REBOOKED", "DISPATCH", actor,
                "调度改约，新窗口 " + plan.start() + "~" + plan.end() + "（月台" + plan.dockCode() + "）");
        return tripView(code);
    }

    private String stricterZone(String a, String b) {
        return zoneRank(a) >= zoneRank(b) ? a : b;
    }

    private int zoneRank(String z) {
        return switch (z) {
            case "FROZEN" -> 3;
            case "CHILLED" -> 2;
            default -> 1;
        };
    }

    private long nextTripSeq() {
        return db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(id),0) + 1 FROM trip", Long.class);
    }

    private OwnerInfo ownerInfo(long ownerId) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT id, code, name, priority_level, default_value FROM cargo_owner WHERE id = ?", ownerId);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("货主不存在: " + ownerId);
        }
        Map<String, Object> r = rows.get(0);
        return new OwnerInfo(((Number) r.get("id")).longValue(), (String) r.get("code"),
                (String) r.get("name"), (String) r.get("priority_level"),
                (BigDecimal) r.get("default_value"));
    }

    private Map<String, Object> loadById(String label, long id, String sql) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(sql, id);
        if (rows.isEmpty()) {
            throw ApiException.badRequest(label + " 不存在: " + id);
        }
        return rows.get(0);
    }

    public Map<String, Object> tripView(String code) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        trip.put("cargoLines", db.jdbc().queryForList("SELECT * FROM trip_cargo WHERE trip_id = ?", tripId));
        trip.put("incidents", db.incidentsOfTrip(tripId));
        trip.put("timeline", db.jdbc().queryForList(
                "SELECT event_type, actor_role, actor_name, detail, created_at " +
                "FROM trip_event WHERE trip_id = ? ORDER BY created_at, id", tripId));
        trip.put("tempReadings", db.jdbc().queryForList(
                "SELECT device_no, recorded_at, celsius, gap_after FROM temp_reading " +
                "WHERE trip_id = ? ORDER BY recorded_at", tripId));
        trip.put("batches", db.jdbc().queryForList(
                "SELECT id, cargo_id, batch_code, expected_qty, scanned_qty, scan_status, accept_qty, reject_qty " +
                "FROM trip_batch WHERE trip_id = ? ORDER BY id", tripId));
        return trip;
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

    private static boolean bool(Object o) {
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
