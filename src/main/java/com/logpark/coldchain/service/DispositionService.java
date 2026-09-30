package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 货物处置与责任结算。
 *
 * 处置：放行 RELEASED / 隔离 QUARANTINED / 降级入库 DOWNGRADED / 拒收 REJECTED。
 * 定责：依据车次异常类型与闭环责任，归集 CARRIER / PARK / OWNER，多方有责记 SHARED。
 * 结算：货损金额按处置方式与破损/化冻件数估算；
 *   - CARRIER 全责：承运商按货损赔付客户 + 定额扣罚款，信用分下调（直接影响后续预约 priorityScore）；
 *   - PARK 责任：园区按货损赔付客户；
 *   - SHARED：承运商承担定额扣罚，客户按货损 50% 获赔；
 *   - OWNER 责任（改仓/加急导致）：货主自担，无赔付扣罚。
 */
@Service
public class DispositionService {

    private final Db db;

    public DispositionService(Db db) {
        this.db = db;
    }

    /** 默认责任归属（异常闭环责任优先，否则按类型推定）。 */
    public static String defaultResponsibility(String type) {
        return switch (type) {
            case "LATE_ARRIVAL", "TEMP_GAP", "SEAL_ABNORMAL", "DOCS_EXPIRED",
                 "TEMP_EXCURSION", "THAW_FOUND" -> "CARRIER";
            case "DOCK_CONGESTION", "POWER_LIMIT" -> "PARK";
            case "WAREHOUSE_CHANGE", "URGENT_INSERT" -> "OWNER";
            default -> "PARK";
        };
    }

    /**
     * 处置决定。body: { decisions: [ {cargoId, disposition, note} ], note }
     * 车次主处置取最严厉者，随后进入 SETTLING（责任判断与结算）。
     */
    @Transactional
    public Map<String, Object> decide(String code, Map<String, Object> body, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        if (!List.of("AWAIT_DISPOSITION", "QUARANTINED", "IN_QC").contains(trip.get("status"))) {
            throw ApiException.conflict("仅质检后（隔离/待处置）车次可做处置决定，当前=" + trip.get("status"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions =
                (List<Map<String, Object>>) body.getOrDefault("decisions", List.of());
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));

        List<Map<String, Object>> lines = db.jdbc().queryForList(
                "SELECT id FROM trip_cargo WHERE trip_id = ?", tripId);
        if (decisions.isEmpty()) {
            throw ApiException.badRequest("必须对至少一条货主明细给出处置决定");
        }
        Set<Long> decided = new LinkedHashSet<>();
        for (Map<String, Object> d : decisions) {
            long cargoId = ((Number) d.get("cargoId")).longValue();
            String disposition = String.valueOf(d.get("disposition")).toUpperCase();
            if (!List.of("RELEASED", "QUARANTINED", "DOWNGRADED", "REJECTED").contains(disposition)) {
                throw ApiException.badRequest("处置必须为 RELEASED/QUARANTINED/DOWNGRADED/REJECTED");
            }
            String dNote = d.get("note") == null ? null : String.valueOf(d.get("note"));
            int n = db.jdbc().update(
                    "UPDATE trip_cargo SET disposition = ?, disposition_note = ? WHERE id = ? AND trip_id = ?",
                    disposition, dNote, cargoId, tripId);
            if (n == 0) {
                throw ApiException.badRequest("货物明细不属于本车次: " + cargoId);
            }
            decided.add(cargoId);
        }
        // 未显式决定的明细：质检通过的默认放行，化冻的默认隔离
        for (Map<String, Object> line : lines) {
            long cargoId = ((Number) line.get("id")).longValue();
            if (decided.contains(cargoId)) continue;
            db.jdbc().update("""
                    UPDATE trip_cargo SET disposition =
                        CASE WHEN qc_result = 'THAWED' THEN 'QUARANTINED' ELSE 'RELEASED' END,
                        disposition_note = COALESCE(disposition_note, '系统按质检结果默认处置')
                    WHERE id = ?
                    """, cargoId);
        }

        String main = db.jdbc().queryForObject("""
                SELECT disposition FROM trip_cargo WHERE trip_id = ?
                ORDER BY CASE disposition
                    WHEN 'REJECTED' THEN 4 WHEN 'QUARANTINED' THEN 3
                    WHEN 'DOWNGRADED' THEN 2 ELSE 1 END DESC
                LIMIT 1
                """, String.class, tripId);

        db.updateTrip(tripId, "status = 'SETTLING', current_stage = 'SETTLE', disposition = ?, disposition_note = ?",
                main, note);
        db.event(tripId, "DISPOSITION_DECIDED", "QC", actor,
                "处置完成，车次主处置=" + main + "（明细可分别放行/隔离/降级/拒收）。备注=" + note
                        + "，进入责任判断与结算环节");
        return db.getTripByCode(code);
    }

    /** 责任与金额测算（结算员可先预览）。 */
    public Map<String, Object> previewSettlement(String code) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        return compute(tripId);
    }

    /** 结算确认：写入责任、扣罚、赔付，调整承运商信用分，车次完成。 */
    @Transactional
    public Map<String, Object> confirmSettlement(String code, String overrideParty, String actor) {
        Map<String, Object> trip = db.getTripByCode(code);
        long tripId = ((Number) trip.get("id")).longValue();
        if (!"SETTLING".equals(trip.get("status"))) {
            throw ApiException.conflict("仅 SETTLING 环节可确认结算，当前=" + trip.get("status"));
        }
        // feature-4：存在未解冻批次 → 禁止结算（异常工单未闭环）
        List<Map<String, Object>> frozen = db.jdbc().queryForList("""
                SELECT f.reason, b.batch_code FROM settlement_freeze f
                JOIN trip_batch b ON b.id = f.batch_id
                WHERE f.trip_id = ? AND f.status = 'FROZEN'
                """, tripId);
        if (!frozen.isEmpty()) {
            throw ApiException.conflict("存在 " + frozen.size() + " 条未解冻批次（"
                    + frozen.get(0).get("batch_code") + " 等），异常工单闭环前禁止入库结算");
        }
        // feature-4：存在未生效赔付候选 → 须先走完审批链
        Integer pendingPayouts = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM payout WHERE trip_id = ? AND status IN ('DRAFT','IN_APPROVAL')",
                Integer.class, tripId);
        if (pendingPayouts != null && pendingPayouts > 0) {
            throw ApiException.conflict("存在 " + pendingPayouts
                    + " 条未生效赔付候选（DRAFT/IN_APPROVAL），审批链完整前不能写入结算");
        }
        Map<String, Object> calc = compute(tripId);
        String party = overrideParty != null && !overrideParty.isBlank()
                ? overrideParty.toUpperCase() : (String) calc.get("liabilityParty");
        if (party != null && !party.isBlank()
                && !List.of("CARRIER", "PARK", "OWNER", "SHARED").contains(party)) {
            throw ApiException.badRequest("责任方必须为 CARRIER/PARK/OWNER/SHARED，或留空表示无责正常放行");
        }
        boolean noLiability = party == null || party.isBlank();

        BigDecimal loss = (BigDecimal) calc.get("lossAmount");
        BigDecimal penalty = noLiability ? BigDecimal.ZERO : switch (party) {
            case "CARRIER" -> loss.add((BigDecimal) calc.get("fixedFines"));
            case "SHARED" -> (BigDecimal) calc.get("fixedFines");
            default -> BigDecimal.ZERO;
        };
        // feature-4：已有生效赔付单时，客户赔付以审批链确认的赔付为准（审批链完整后才能生效）
        BigDecimal payoutSum = db.jdbc().queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM payout WHERE trip_id = ? AND status = 'EFFECTIVE'",
                BigDecimal.class, tripId);
        boolean hasPayouts = payoutSum != null && db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM payout WHERE trip_id = ? AND status = 'EFFECTIVE'",
                Integer.class, tripId) > 0;
        BigDecimal compensation = hasPayouts ? payoutSum
                : noLiability ? BigDecimal.ZERO : switch (party) {
                    case "CARRIER", "PARK" -> loss;
                    case "SHARED" -> loss.multiply(BigDecimal.valueOf(0.5));
                    default -> BigDecimal.ZERO;
                };

        long carrierId = ((Number) trip.get("carrier_id")).longValue();
        int creditDelta = (Integer) calc.get("creditDelta");
        db.jdbc().update(
                "UPDATE carrier SET credit_score = GREATEST(0, LEAST(100, credit_score + ?)) WHERE id = ?",
                creditDelta, carrierId);
        Integer newScore = db.jdbc().queryForObject(
                "SELECT credit_score FROM carrier WHERE id = ?", Integer.class, carrierId);

        String note = String.format("结算确认：责任=%s，货损估算=%.2f，承运商扣罚=%.2f，客户赔付=%.2f，承运商信用分调整 %d→%d（后续预约优先级相应变化）。依据：%s%s",
                noLiability ? "无（正常放行）" : party, loss, penalty, compensation,
                newScore - creditDelta, newScore, calc.get("basis"),
                hasPayouts ? "；客户赔付取审批链生效赔付单合计 ¥" + payoutSum : "");

        db.updateTrip(tripId,
                "liability_party = ?, penalty_amount = ?, compensation_amount = ?, liability_note = ?, " +
                "settled_at = ?, status = 'DONE', current_stage = 'DONE'",
                noLiability ? null : party, penalty, compensation, note, Timestamp.valueOf(db.now()));
        db.event(tripId, "SETTLED", "SETTLE", actor, note);
        return db.getTripByCode(code);
    }

    // ---------------- 测算内部 ----------------

    private Map<String, Object> compute(long tripId) {
        List<Map<String, Object>> lines = db.jdbc().queryForList(
                "SELECT tc.*, o.name AS owner_name FROM trip_cargo tc " +
                "JOIN cargo_owner o ON o.id = tc.owner_id WHERE tc.trip_id = ?", tripId);

        BigDecimal loss = BigDecimal.ZERO;
        List<Map<String, Object>> lossDetails = new ArrayList<>();
        for (Map<String, Object> c : lines) {
            int pieces = ((Number) c.get("pieces")).intValue();
            int damaged = ((Number) c.get("damaged_pieces")).intValue();
            int thawed = ((Number) c.get("sample_thawed")).intValue();
            // 化冻比例外推到本批
            Integer sampleTotal = c.get("sample_total") == null ? null : ((Number) c.get("sample_total")).intValue();
            int estThawedPieces = 0;
            if (sampleTotal != null && sampleTotal > 0) {
                estThawedPieces = (int) Math.round(pieces * (thawed / (double) sampleTotal));
            }
            BigDecimal vpp = (BigDecimal) c.get("value_per_piece");
            String disposition = (String) c.get("disposition");
            if (disposition == null) disposition = "QUARANTINED";

            BigDecimal lineLoss = switch (disposition) {
                case "REJECTED" -> vpp.multiply(BigDecimal.valueOf(pieces));
                case "DOWNGRADED" -> vpp.multiply(BigDecimal.valueOf(pieces))
                        .multiply(BigDecimal.valueOf(0.5));
                case "RELEASED" -> vpp.multiply(BigDecimal.valueOf(damaged + estThawedPieces));
                default -> vpp.multiply(BigDecimal.valueOf(damaged + estThawedPieces)); // QUARANTINED
            };
            lineLoss = lineLoss.setScale(2, RoundingMode.HALF_UP);
            loss = loss.add(lineLoss);
            lossDetails.add(Map.of(
                    "owner", c.get("owner_name"),
                    "cargoType", c.get("cargo_type"),
                    "pieces", pieces,
                    "damaged", damaged,
                    "estimatedThawedPieces", estThawedPieces,
                    "disposition", disposition,
                    "lossAmount", lineLoss));
        }

        // 责任归集：已闭环的责任为准，否则按类型推定
        List<Map<String, Object>> incidents = db.incidentsOfTrip(tripId);
        Set<String> parties = new LinkedHashSet<>();
        BigDecimal fixedFines = BigDecimal.ZERO;
        int creditDelta = 0;
        List<String> basis = new ArrayList<>();
        for (Map<String, Object> inc : incidents) {
            String type = (String) inc.get("type");
            String resp = (String) inc.get("responsibility");
            if (resp == null || "PENDING".equals(resp)) {
                resp = defaultResponsibility(type);
            }
            // 改仓/加急属货主侧诱因，但温度类硬异常仍归承运商
            parties.add(resp);
            basis.add(type + "→" + resp);
            if ("CARRIER".equals(resp)) {
                creditDelta -= switch (type) {
                    case "SEAL_ABNORMAL", "DOCS_EXPIRED", "THAW_FOUND", "TEMP_EXCURSION", "TEMP_GAP" -> 15;
                    case "LATE_ARRIVAL" -> 8;
                    default -> 5;
                };
                fixedFines = fixedFines.add(switch (type) {
                    case "SEAL_ABNORMAL", "DOCS_EXPIRED" -> BigDecimal.valueOf(1000);
                    case "THAW_FOUND", "TEMP_EXCURSION", "TEMP_GAP" -> BigDecimal.valueOf(800);
                    case "LATE_ARRIVAL" -> BigDecimal.valueOf(500);
                    default -> BigDecimal.valueOf(200);
                });
            }
        }
        String party = parties.isEmpty() ? ""
                : parties.size() == 1 ? parties.iterator().next() : "SHARED";
        if (parties.isEmpty()) {
            basis.add("无温控/封签/晚到等责任异常，按正常放行结算（无责任方）");
        }

        return Map.of(
                "liabilityParty", party,
                "lossAmount", loss,
                "fixedFines", fixedFines,
                "creditDelta", creditDelta,
                "parties", List.copyOf(parties),
                "lossDetails", lossDetails,
                "basis", String.join("; ", basis));
    }
}
