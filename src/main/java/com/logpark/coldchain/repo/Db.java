package com.logpark.coldchain.repo;

import com.logpark.coldchain.support.ApiException;
import com.logpark.coldchain.support.ParkClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 通用数据访问与领域小工具：车次读取、事件/异常登记、参数读取。
 */
@Service
public class Db {

    private final JdbcTemplate jdbc;
    private final ParkClock clock;

    public Db(JdbcTemplate jdbc, ParkClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public LocalDateTime now() {
        return clock.now();
    }

    // ---------------- 车次 ----------------

    public Map<String, Object> getTripByCode(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM trip WHERE code = ?", code);
        if (rows.isEmpty()) {
            throw ApiException.notFound("车次不存在: " + code);
        }
        return rows.get(0);
    }

    public Long tripId(String code) {
        return ((Number) getTripByCode(code).get("id")).longValue();
    }

    public void updateTrip(long tripId, String setClause, Object... args) {
        Object[] all = new Object[args.length + 2];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = now();
        all[args.length + 1] = tripId;
        int n = jdbc.update("UPDATE trip SET " + setClause + ", updated_at = ? WHERE id = ?", all);
        if (n == 0) {
            throw ApiException.notFound("车次不存在");
        }
    }

    public void enterStage(long tripId, String status, String stage) {
        updateTrip(tripId,
                "status = ?, current_stage = ?, stage_entered_at = ?",
                status, stage, Timestamp.valueOf(now()));
    }

    // ---------------- 事件时间线 ----------------

    public void event(long tripId, String type, String actorRole, String actorName, String detail) {
        jdbc.update("""
                INSERT INTO trip_event (trip_id, event_type, actor_role, actor_name, detail, created_at)
                VALUES (?,?,?,?,?,?)
                """, tripId, type, actorRole, actorName, detail, Timestamp.valueOf(now()));
    }

    // ---------------- 异常 ----------------

    public long raiseIncident(long tripId, String type, String severity, String description,
                              String responsibility, String actorRole) {
        return jdbc.queryForObject("""
                INSERT INTO incident (trip_id, type, severity, description, status, responsibility,
                                      raised_by_role, created_at)
                VALUES (?,?,?,?,'OPEN',?,?,?)
                RETURNING id
                """, Long.class, tripId, type, severity, description,
                responsibility == null ? "PENDING" : responsibility, actorRole, Timestamp.valueOf(now()));
    }

    public List<Map<String, Object>> openIncidents(long tripId) {
        return jdbc.queryForList(
                "SELECT * FROM incident WHERE trip_id = ? AND status = 'OPEN' ORDER BY id", tripId);
    }

    public List<Map<String, Object>> incidentsOfTrip(long tripId) {
        return jdbc.queryForList(
                "SELECT * FROM incident WHERE trip_id = ? ORDER BY created_at, id", tripId);
    }

    // ---------------- 参数 ----------------

    public String setting(String key, String def) {
        List<String> v = jdbc.queryForList(
                "SELECT value FROM park_setting WHERE key = ?", String.class, key);
        return v.isEmpty() ? def : v.get(0);
    }

    public int settingInt(String key, int def) {
        try {
            return Integer.parseInt(setting(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    public boolean settingBool(String key, boolean def) {
        return Boolean.parseBoolean(setting(key, String.valueOf(def)));
    }

    public void setSetting(String key, String value) {
        jdbc.update("""
                INSERT INTO park_setting (key, value) VALUES (?,?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, key, value);
    }

    // ---------------- 杂项 ----------------

    /** PostgreSQL 事务级咨询锁：串行化同一 key 的并发写（版本号/重算等临界区）。 */
    public void lock(String key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", String.class, key);
    }

    public static BigDecimal bd(Object o) {
        return o == null ? null : (BigDecimal) o;
    }
}
