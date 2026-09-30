package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 异常检测与自动冻结：
 * 温度超阈、数据断点、铅封异常、卸货扫描与预约批次不一致 →
 * 自动冻结对应批次的正常入库结算（settlement_freeze，批次+原因唯一，重复回调/重试不重复冻结），
 * 并创建可并行补证的异常工单（work_order，dedup_key 唯一，同一异常区间只建一单）。
 *
 * 复盘规则之一「多个异常区间合并」在检测时完成：
 * 相邻超温区间间隔 ≤ MERGE_WINDOW_MINUTES（默认 15 分钟）合并为一个异常区间。
 */
@Service
public class DetectionService {

    private final Db db;

    public DetectionService(Db db) {
        this.db = db;
    }

    // ---------------- 批次 ----------------

    /** 幂等：确保车次每条货主明细至少有一个预约批次（兼容历史无批次车次）。 */
    public void ensureBatches(long tripId) {
        List<Map<String, Object>> cargos = db.jdbc().queryForList(
                "SELECT id, pieces FROM trip_cargo WHERE trip_id = ? ORDER BY id", tripId);
        for (Map<String, Object> c : cargos) {
            long cargoId = ((Number) c.get("id")).longValue();
            db.jdbc().update("""
                    INSERT INTO trip_batch (trip_id, cargo_id, batch_code, expected_qty)
                    SELECT ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM trip_batch WHERE cargo_id = ?)
                    """, tripId, cargoId, "B" + cargoId + "-01",
                    ((Number) c.get("pieces")).intValue(), cargoId);
        }
    }

    public List<Map<String, Object>> batchesOfTrip(long tripId) {
        return db.jdbc().queryForList("""
                SELECT b.*, o.name AS owner_name, o.id AS owner_id, tc.cargo_type, tc.value_per_piece
                FROM trip_batch b
                JOIN trip_cargo tc ON tc.id = b.cargo_id
                JOIN cargo_owner o ON o.id = tc.owner_id
                WHERE b.trip_id = ? ORDER BY b.id
                """, tripId);
    }

    // ---------------- 冻结（幂等） ----------------

    /**
     * 冻结某批次某原因的入库结算。批次+原因唯一：
     * 重复回调/并发重试不会生成第二条冻结；已解冻后再次异常则重新冻结。
     */
    public void freezeBatch(long tripId, long batchId, String reason, Long workOrderId) {
        db.jdbc().update("""
                INSERT INTO settlement_freeze (trip_id, batch_id, reason, work_order_id, status, created_at)
                VALUES (?,?,?,?, 'FROZEN', ?)
                ON CONFLICT (batch_id, reason) DO UPDATE
                    SET status = 'FROZEN', released_at = NULL, release_note = NULL,
                        work_order_id = EXCLUDED.work_order_id
                    WHERE settlement_freeze.status = 'RELEASED'
                """, tripId, batchId, reason, workOrderId, Timestamp.valueOf(db.now()));
    }

    /** 车辆级异常（超温/断点/铅封）冻结该车全部批次。 */
    public void freezeTripBatches(long tripId, String reason, Long workOrderId) {
        ensureBatches(tripId);
        for (Map<String, Object> b : batchesOfTrip(tripId)) {
            freezeBatch(tripId, ((Number) b.get("id")).longValue(), reason, workOrderId);
        }
    }

    // ---------------- 工单（幂等） ----------------

    /**
     * 建工单：dedup_key 唯一，同一车次同一异常区间只建一单。
     * 已存在且未结案时更新区间终点/合并计数（温度数据补传导致区间延长）。
     * @return 工单 id
     */
    public long ensureWorkOrder(long tripId, Long batchId, String type, String dedupKey,
                                LocalDateTime start, LocalDateTime end, int mergedCount, String note) {
        List<Long> ids = db.jdbc().queryForList("""
                INSERT INTO work_order (trip_id, batch_id, type, status, anomaly_start, anomaly_end,
                                        merged_count, review_note, dedup_key, created_at)
                VALUES (?,?,?, 'OPEN', ?,?,?,?,?,?)
                ON CONFLICT (dedup_key) DO UPDATE
                    SET anomaly_end = EXCLUDED.anomaly_end,
                        merged_count = EXCLUDED.merged_count
                    WHERE work_order.status IN ('OPEN','IN_REVIEW')
                RETURNING id
                """, Long.class, tripId, batchId, type,
                start == null ? null : Timestamp.valueOf(start),
                end == null ? null : Timestamp.valueOf(end),
                mergedCount, note, dedupKey, Timestamp.valueOf(db.now()));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        return db.jdbc().queryForObject("SELECT id FROM work_order WHERE dedup_key = ?", Long.class, dedupKey);
    }

    // ---------------- 温度异常检测（超温区间合并 + 断点） ----------------

    private record Point(LocalDateTime t, BigDecimal c) {}
    private record Interval(LocalDateTime start, LocalDateTime end, int merged, BigDecimal maxC) {}

    /**
     * 重新扫描车次温度曲线：
     * 1) 超温区间：连续超阈点构成原始区间，相邻区间间隔 ≤ 合并窗口则合并（复盘规则：多区间合并）；
     * 2) 数据断点：相邻点间隔 > TEMP_GAP_MINUTES。
     * 每个区间建工单并冻结全部批次（幂等）。
     * @return 本次扫描出的工单摘要
     */
    public List<Map<String, Object>> detectTemperatureAnomalies(long tripId) {
        int gapMinutes = db.settingInt("TEMP_GAP_MINUTES", 45);
        int mergeWindow = db.settingInt("MERGE_WINDOW_MINUTES", 15);
        BigDecimal limit = db.jdbc().queryForObject(
                "SELECT MIN(max_temp_celsius) FROM trip_cargo WHERE trip_id = ?", BigDecimal.class, tripId);

        List<Point> pts = db.jdbc().queryForList(
                        "SELECT recorded_at, celsius FROM temp_reading WHERE trip_id = ? ORDER BY recorded_at",
                        tripId)
                .stream().map(r -> new Point(((Timestamp) r.get("recorded_at")).toLocalDateTime(),
                        (BigDecimal) r.get("celsius"))).toList();

        List<Map<String, Object>> raised = new ArrayList<>();
        if (pts.isEmpty()) {
            return raised;
        }

        // ---- 超温原始区间 ----
        List<Interval> raw = new ArrayList<>();
        if (limit != null) {
            LocalDateTime curStart = null, curEnd = null;
            BigDecimal curMax = null;
            for (Point p : pts) {
                boolean over = p.c().compareTo(limit) > 0;
                if (over) {
                    if (curStart == null) {
                        curStart = p.t();
                        curMax = p.c();
                    }
                    curEnd = p.t();
                    if (p.c().compareTo(curMax) > 0) curMax = p.c();
                } else if (curStart != null) {
                    raw.add(new Interval(curStart, curEnd, 1, curMax));
                    curStart = null;
                    curMax = null;
                }
            }
            if (curStart != null) {
                raw.add(new Interval(curStart, curEnd, 1, curMax));
            }
        }
        // ---- 合并相邻区间（间隔 ≤ 合并窗口） ----
        List<Interval> merged = new ArrayList<>();
        for (Interval iv : raw) {
            if (!merged.isEmpty()) {
                Interval prev = merged.get(merged.size() - 1);
                long gap = Duration.between(prev.end(), iv.start()).toMinutes();
                if (gap <= mergeWindow) {
                    merged.set(merged.size() - 1, new Interval(prev.start(), iv.end(),
                            prev.merged() + iv.merged(),
                            prev.maxC().compareTo(iv.maxC()) > 0 ? prev.maxC() : iv.maxC()));
                    continue;
                }
            }
            merged.add(iv);
        }
        for (Interval iv : merged) {
            String dedup = "TEMP_EXCURSION:" + tripId + ":" + iv.start();
            long woId = ensureWorkOrder(tripId, null, "TEMP_EXCURSION", dedup, iv.start(), iv.end(),
                    iv.merged(), "超温区间 " + iv.start() + " ~ " + iv.end()
                            + "，峰值 " + iv.maxC() + "℃（上限 " + limit + "℃），合并原始区间 " + iv.merged() + " 个");
            freezeTripBatches(tripId, "TEMP_EXCURSION", woId);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("workOrderId", woId);
            m.put("type", "TEMP_EXCURSION");
            m.put("start", iv.start().toString());
            m.put("end", iv.end().toString());
            m.put("mergedCount", iv.merged());
            m.put("maxCelsius", iv.maxC());
            raised.add(m);
        }

        // ---- 数据断点 ----
        for (int i = 0; i < pts.size() - 1; i++) {
            long minutes = Duration.between(pts.get(i).t(), pts.get(i + 1).t()).toMinutes();
            if (minutes > gapMinutes) {
                String dedup = "DATA_GAP:" + tripId + ":" + pts.get(i).t();
                long woId = ensureWorkOrder(tripId, null, "DATA_GAP", dedup,
                        pts.get(i).t(), pts.get(i + 1).t(), 1,
                        "温度曲线断点 " + minutes + " 分钟（阈值 " + gapMinutes + " 分钟），"
                                + "区间 " + pts.get(i).t() + " ~ " + pts.get(i + 1).t()
                                + "，待区分传感器离线/真实超温");
                freezeTripBatches(tripId, "DATA_GAP", woId);
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("workOrderId", woId);
                m.put("type", "DATA_GAP");
                m.put("start", pts.get(i).t().toString());
                m.put("end", pts.get(i + 1).t().toString());
                m.put("gapMinutes", minutes);
                raised.add(m);
            }
        }
        return raised;
    }

    // ---------------- 铅封异常 ----------------

    /** 铅封 TAMPERED/BROKEN → 工单 + 冻结全部批次（幂等：按事件 uid 去重）。 */
    public long raiseSealAbnormal(long tripId, String eventUid, String event, LocalDateTime occurredAt) {
        String dedup = "SEAL_ABNORMAL:" + tripId + ":" + eventUid;
        long woId = ensureWorkOrder(tripId, null, "SEAL_ABNORMAL", dedup, occurredAt, occurredAt, 1,
                "电子铅封上报 " + event + "（" + occurredAt + "），运输途中封签完整性存疑");
        freezeTripBatches(tripId, "SEAL_ABNORMAL", woId);
        return woId;
    }

    // ---------------- 卸货扫描比对 ----------------

    /**
     * 扫描批次与预约批次比对。
     * @return "MATCHED" / "MISMATCH"（数量不符）/ "EXTRA"（预约外批次）
     */
    public String checkScan(long tripId, String batchCode, int qty) {
        ensureBatches(tripId);
        List<Map<String, Object>> found = db.jdbc().queryForList(
                "SELECT * FROM trip_batch WHERE trip_id = ? AND batch_code = ?", tripId, batchCode);
        if (found.isEmpty()) {
            // 预约外批次：货物身份存疑，冻结全部批次
            long woId = ensureWorkOrder(tripId, null, "SCAN_MISMATCH",
                    "SCAN_MISMATCH:" + tripId + ":" + batchCode, db.now(), db.now(), 1,
                    "卸货扫描到预约外批次 " + batchCode + "（数量 " + qty + "），与预约单批次不一致");
            freezeTripBatches(tripId, "SCAN_MISMATCH", woId);
            return "EXTRA";
        }
        Map<String, Object> batch = found.get(0);
        long batchId = ((Number) batch.get("id")).longValue();
        int scanned = ((Number) batch.get("scanned_qty")).intValue() + qty;
        int expected = ((Number) batch.get("expected_qty")).intValue();
        db.jdbc().update("UPDATE trip_batch SET scanned_qty = ? WHERE id = ?", scanned, batchId);
        if (scanned == expected) {
            db.jdbc().update("UPDATE trip_batch SET scan_status = 'MATCHED' WHERE id = ?", batchId);
            return "MATCHED";
        }
        db.jdbc().update("UPDATE trip_batch SET scan_status = 'MISMATCH' WHERE id = ?", batchId);
        long woId = ensureWorkOrder(tripId, batchId, "SCAN_MISMATCH",
                "SCAN_MISMATCH:" + tripId + ":" + batchCode, db.now(), db.now(), 1,
                "批次 " + batchCode + " 扫描数量 " + scanned + " 与预约数量 " + expected + " 不一致");
        freezeBatch(tripId, batchId, "SCAN_MISMATCH", woId);
        return "MISMATCH";
    }
}
