package com.logpark.coldchain.service;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.support.ApiException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 赔付候选方案与结算生效（feature-4-b）。
 *
 * 计算输入：责任归属（已闭环工单/区间复盘）、可收数量（客户逐批次决定或主管绑定裁定）、
 *          合同阈值（赔付率上限/降级折扣/起赔额/L2 金额线）、客户选择（继续收/降级/拒收）。
 *
 * 方案生命周期：DRAFT → APPROVING →（审批链完整）→ EFFECTIVE；
 *   审批驳回或证据被替换：未生效方案一律置 SUPERSEDED/REJECTED 并重新计算；
 *   settlement_ledger 对冷链事件唯一，重复提交/并发/重试只可能有一次正式赔付。
 */
@Service
public class SettlementProposalService {

    private static final Set<String> OPEN_TICKET_STATES =
            Set.of("OPEN", "AWAIT_CUSTOMER", "REVIEWING", "ESCALATED");

    private final Db db;
    private final EscalationService escalations;

    public SettlementProposalService(Db db, @Lazy EscalationService escalations) {
        this.db = db;
        this.escalations = escalations;
    }

    // ============================================================
    // 候选方案计算（不落库，供预览）
    // ============================================================

    public Map<String, Object> calculate(long ceId) {
        long tripId = tripIdOf(ceId);
        List<Map<String, Object>> batches = db.jdbc().queryForList(
                "SELECT * FROM inbound_event WHERE cold_event_id = ? ORDER BY id", ceId);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> b : batches) {
            String batchNo = (String) b.get("batch_no");
            long ownerId = b.get("owner_id") == null ? 0L : ((Number) b.get("owner_id")).longValue();
            int expected = ((Number) b.get("expected_pieces")).intValue();
            BigDecimal unit = resolveUnitValue(tripId, b);

            Map<String, Object> choice = effectiveChoice(ceId, batchNo, expected);
            String customerChoice = (String) choice.get("decision"); // ACCEPT/DOWNGRADE/REJECT/null=未决定
            int accepted = ((Number) choice.get("acceptedPieces")).intValue();
            int rejected = Math.max(0, expected - accepted);

            Liability liability = liabilityOfBatch(ceId, batchNo);
            Map<String, Object> contract = contractOf(ownerId);
            BigDecimal downgradeRate = (BigDecimal) contract.get("downgradeRate");

            // 货损：拒收全损 + 降级折扣损失 + 短少（可收<应到）
            BigDecimal loss = BigDecimal.ZERO;
            if (liability.hasAnomaly) {
                loss = switch (customerChoice == null ? "UNDECIDED" : customerChoice) {
                    case "REJECT" -> unit.multiply(BigDecimal.valueOf(expected));
                    case "DOWNGRADE" -> unit.multiply(BigDecimal.valueOf(accepted))
                            .multiply(downgradeRate)
                            .add(unit.multiply(BigDecimal.valueOf(rejected)));
                    case "ACCEPT" -> unit.multiply(BigDecimal.valueOf(rejected));
                    default -> unit.multiply(BigDecimal.valueOf(rejected)); // 未决定先按短少预估
                };
            }
            loss = loss.setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("batchNo", batchNo);
            e.put("ownerId", ownerId);
            e.put("expectedPieces", expected);
            e.put("acceptedPieces", accepted);
            e.put("rejectedPieces", rejected);
            e.put("unitValue", unit);
            e.put("customerChoice", customerChoice);
            e.put("qcConclusion", latestQcForBatch(ceId, batchNo));
            e.put("liabilityParty", liability.party);
            e.put("liabilityBasis", liability.basis);
            e.put("hasAnomaly", liability.hasAnomaly);
            e.put("rawLoss", loss);
            entries.add(e);
        }

        // 逐货主应用合同：赔付率上限、起赔额
        Map<Long, List<Map<String, Object>>> byOwner = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            byOwner.computeIfAbsent(((Number) e.get("ownerId")).longValue(), k -> new ArrayList<>()).add(e);
        }
        BigDecimal totalLoss = BigDecimal.ZERO, totalComp = BigDecimal.ZERO;
        for (var en : byOwner.entrySet()) {
            Map<String, Object> c = contractOf(en.getKey());
            BigDecimal rateMax = (BigDecimal) c.get("rateMax");
            BigDecimal minClaim = (BigDecimal) c.get("minClaim");
            BigDecimal ownerLoss = en.getValue().stream()
                    .map(x -> (BigDecimal) x.get("rawLoss")).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ownerComp = ownerLoss.compareTo(minClaim) < 0 ? BigDecimal.ZERO
                    : ownerLoss.multiply(rateMax).setScale(2, RoundingMode.HALF_UP);
            // 按货损占比摊回到批次
            for (Map<String, Object> e : en.getValue()) {
                BigDecimal share = ownerLoss.signum() == 0 ? BigDecimal.ZERO
                        : ((BigDecimal) e.get("rawLoss")).divide(ownerLoss, 6, RoundingMode.HALF_UP);
                BigDecimal comp = ownerComp.multiply(share).setScale(2, RoundingMode.HALF_UP);
                e.put("lossAmount", e.get("rawLoss"));
                e.put("compensation", comp);
            }
            totalLoss = totalLoss.add(ownerLoss);
            totalComp = totalComp.add(ownerComp);
        }

        // 责任全局归集与定额扣罚
        Set<String> parties = new LinkedHashSet<>();
        BigDecimal fines = BigDecimal.ZERO;
        int creditDelta = 0;
        List<String> basis = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            String p = (String) e.get("liabilityParty");
            if (p != null && !"NONE".equals(p)) parties.add(p);
            basis.add(e.get("batchNo") + "→" + p + "(" + e.get("customerChoice") + ")");
        }
        List<Map<String, Object>> respTickets = db.jdbc().queryForList("""
                SELECT DISTINCT category, responsibility FROM anomaly_ticket
                WHERE cold_event_id = ? AND responsibility IN ('CARRIER','PARK','OWNER','SHARED')
                """, ceId);
        boolean carrierLiable = parties.contains("CARRIER") || parties.contains("SHARED");
        for (Map<String, Object> t : respTickets) {
            if (!"CARRIER".equals(t.get("responsibility")) && !"SHARED".equals(t.get("responsibility"))) continue;
            String cat = String.valueOf(t.get("category"));
            fines = fines.add(switch (cat) {
                case "SEAL_ABNORMAL" -> BigDecimal.valueOf(1000);
                case "TEMP_EXCURSION", "DATA_GAP" -> BigDecimal.valueOf(800);
                default -> BigDecimal.valueOf(500);
            });
            creditDelta -= switch (cat) {
                case "SEAL_ABNORMAL", "TEMP_EXCURSION", "DATA_GAP" -> 15;
                default -> 8;
            };
        }

        String party = parties.isEmpty() ? "NONE"
                : parties.size() == 1 ? parties.iterator().next() : "SHARED";
        if ("SHARED".equals(party)) {
            // 多方责任：客户赔付按 50% 分担
            for (Map<String, Object> e : entries) {
                e.put("compensation", ((BigDecimal) e.get("compensation"))
                        .multiply(BigDecimal.valueOf(0.5)).setScale(2, RoundingMode.HALF_UP));
            }
            totalComp = entries.stream().map(x -> (BigDecimal) x.get("compensation"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        if ("OWNER".equals(party) || "NONE".equals(party)) {
            for (Map<String, Object> e : entries) e.put("compensation", BigDecimal.ZERO.setScale(2));
            totalComp = BigDecimal.ZERO;
            fines = BigDecimal.ZERO;
        }
        BigDecimal penalty = carrierLiable && !"OWNER".equals(party) ? totalComp.add(fines) : BigDecimal.ZERO;
        penalty = penalty.setScale(2, RoundingMode.HALF_UP);

        int acceptable = entries.stream().mapToInt(e -> ((Number) e.get("acceptedPieces")).intValue()).sum();
        int rejectedTotal = entries.stream().mapToInt(e -> ((Number) e.get("rejectedPieces")).intValue()).sum();

        // 生效门控（为何现在还不能写入结算）
        List<String> blockers = settlementBlockers(ceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("liabilityParty", party);
        result.put("parties", List.copyOf(parties));
        result.put("acceptablePieces", acceptable);
        result.put("rejectedPieces", rejectedTotal);
        result.put("lossAmount", totalLoss.setScale(2, RoundingMode.HALF_UP));
        result.put("fixedFines", fines);
        result.put("penaltyAmount", penalty);
        result.put("compensationAmount", totalComp.setScale(2, RoundingMode.HALF_UP));
        result.put("creditDelta", creditDelta);
        result.put("entries", entries);
        result.put("basis", String.join("; ", basis));
        result.put("blockers", blockers);
        result.put("readyToPost", blockers.isEmpty());
        return result;
    }

    // ============================================================
    // 方案版本化
    // ============================================================

    /** 生成新版本 DRAFT；旧的未生效方案全部作废（SUPERSEDED）。 */
    @Transactional
    public Map<String, Object> generate(String eventNo, String reason, String actor) {
        long ceId = idOf(eventNo);
        voidNonEffective(ceId, reason == null ? "重新计算赔付候选方案" : reason, actor);
        Map<String, Object> calc = calculate(ceId);
        int version = nextVersion(ceId);
        long id = db.jdbc().queryForObject("""
                INSERT INTO settlement_proposal (cold_event_id, version, status, liability_party,
                    acceptable_pieces, rejected_pieces, loss_amount, penalty_amount, compensation_amount,
                    calc_detail, created_reason, created_by, created_at)
                VALUES (?,?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, ceId, version, calc.get("liabilityParty"),
                calc.get("acceptablePieces"), calc.get("rejectedPieces"),
                calc.get("lossAmount"), calc.get("penaltyAmount"), calc.get("compensationAmount"),
                com.logpark.coldchain.support.Json.write(calc),
                reason, actor, Timestamp.valueOf(db.now()));
        for (Object o : (List<?>) calc.get("entries")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) o;
            db.jdbc().update("""
                    INSERT INTO settlement_entry (proposal_id, batch_no, owner_id, expected_pieces,
                        accepted_pieces, rejected_pieces, unit_value, customer_choice, qc_conclusion,
                        liability_party, loss_amount, compensation)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, e.get("batchNo"), e.get("ownerId"), e.get("expectedPieces"),
                    e.get("acceptedPieces"), e.get("rejectedPieces"), e.get("unitValue"),
                    e.get("customerChoice"), e.get("qcConclusion"), e.get("liabilityParty"),
                    e.get("lossAmount"), e.get("compensation"));
        }
        long tripId = tripIdOf(ceId);
        db.event(tripId, "PROPOSAL_DRAFT", "SETTLE", actor,
                "赔付候选方案 v" + version + " 已生成：责任=" + calc.get("liabilityParty")
                        + "，可收 " + calc.get("acceptablePieces") + " / 拒收 " + calc.get("rejectedPieces")
                        + "，货损 " + calc.get("lossAmount") + "，赔付 " + calc.get("compensationAmount")
                        + "，扣罚 " + calc.get("penaltyAmount")
                        + (((List<?>) calc.get("blockers")).isEmpty() ? ""
                        : "；未满足生效条件：" + String.join("、", blockersString(calc))));
        ensureL2ForAmount(ceId, (BigDecimal) calc.get("compensationAmount"));
        return proposalView(id);
    }

    /** 赔付额达到合同 L2 阈值即创建/保留 L2 审批（不依赖是否点了 submit，防止绕过）。 */
    private void ensureL2ForAmount(long ceId, BigDecimal comp) {
        BigDecimal threshold = l2Threshold(ceId);
        if (comp.compareTo(threshold) < 0) {
            return;
        }
        Integer exists = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM escalation WHERE cold_event_id=? AND level=2", Integer.class, ceId);
        if (exists != null && exists > 0) {
            return;
        }
        escalations.createIfAbsent(ceId, null, 2,
                "赔付候选金额 " + comp + " 达到合同 L2 审批阈值 " + threshold + "，需质量与商务经理终审");
    }

    private BigDecimal l2Threshold(long ceId) {
        BigDecimal threshold = db.jdbc().queryForObject("""
                SELECT MIN(c.require_l2_threshold) FROM owner_contract c
                JOIN inbound_event ie ON ie.owner_id = c.owner_id WHERE ie.cold_event_id = ?
                """, BigDecimal.class, ceId);
        if (threshold == null) {
            threshold = BigDecimal.valueOf(db.settingInt("L2_COMPENSATION_THRESHOLD", 5000));
        }
        return threshold;
    }

    /** 提交审批链；金额超合同阈值自动补 L2。 */
    @Transactional
    public Map<String, Object> submit(long proposalId, String actor) {
        Map<String, Object> p = requireProposal(proposalId);
        long ceId = ((Number) p.get("cold_event_id")).longValue();
        if (!"DRAFT".equals(p.get("status"))) {
            throw ApiException.conflict("仅 DRAFT 方案可提交审批，当前=" + p.get("status"));
        }
        BigDecimal comp = (BigDecimal) p.get("compensation_amount");
        ensureL2ForAmount(ceId, comp);
        db.jdbc().update("UPDATE settlement_proposal SET status='APPROVING', decided_by=?, decided_at=? WHERE id=?",
                actor, Timestamp.valueOf(db.now()), proposalId);
        db.event(tripIdOf(ceId), "PROPOSAL_SUBMITTED", "SETTLE", actor,
                "赔付方案 #" + proposalId + " v" + p.get("version") + " 提交审批链，赔付=" + comp);
        return proposalView(proposalId);
    }

    /**
     * 审批链完整后写入正式结算（幂等）。并发/重试/重复提交不会产生重复赔付。
     */
    @Transactional
    public Map<String, Object> postEffective(long proposalId, String actor) {
        Map<String, Object> p = requireProposal(proposalId);
        long ceId = ((Number) p.get("cold_event_id")).longValue();
        long tripId = tripIdOf(ceId);

        // 已生效：幂等返回，不重复入账
        List<Map<String, Object>> ledger = db.jdbc().queryForList(
                "SELECT * FROM settlement_ledger WHERE cold_event_id = ?", ceId);
        if (!ledger.isEmpty()) {
            Map<String, Object> view = proposalView(proposalId);
            view.put("alreadyEffective", true);
            view.put("ledger", ledger.get(0));
            return view;
        }

        List<String> blockers = settlementBlockers(ceId);
        if (!blockers.isEmpty()) {
            throw ApiException.unprocessable("审批链/补证尚未完整，不能写入结算：" + String.join("；", blockers));
        }
        if (!List.of("DRAFT", "APPROVING", "APPROVED").contains(p.get("status"))) {
            throw ApiException.conflict("方案状态 " + p.get("status") + " 不能生效");
        }

        // 事件级行锁 + ledger 唯一约束双重保障并发
        db.jdbc().queryForObject("SELECT id FROM cold_event WHERE id = ? FOR UPDATE", Long.class, ceId);
        Integer dup = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM settlement_ledger WHERE cold_event_id = ?", Integer.class, ceId);
        if (dup != null && dup > 0) {
            Map<String, Object> view = proposalView(proposalId);
            view.put("alreadyEffective", true);
            return view;
        }

        BigDecimal penalty = (BigDecimal) p.get("penalty_amount");
        BigDecimal comp = (BigDecimal) p.get("compensation_amount");
        String party = (String) p.get("liability_party");

        db.jdbc().update("""
                INSERT INTO settlement_ledger (cold_event_id, proposal_id, penalty_amount,
                    compensation_amount, posted_by, posted_at)
                VALUES (?,?,?,?,?,?)
                """, ceId, proposalId, penalty, comp, actor, Timestamp.valueOf(db.now()));
        db.jdbc().update("UPDATE settlement_proposal SET status='EFFECTIVE', decided_by=?, decided_at=? WHERE id=?",
                actor, Timestamp.valueOf(db.now()), proposalId);

        // 承运商信用分调整
        int creditDelta = ((Number) calculate(ceId).get("creditDelta")).intValue();
        Long carrierId = db.jdbc().queryForObject(
                "SELECT carrier_id FROM cold_event WHERE id = ?", Long.class, ceId);
        if (creditDelta != 0) {
            db.jdbc().update(
                    "UPDATE carrier SET credit_score = GREATEST(0, LEAST(100, credit_score + ?)) WHERE id = ?",
                    creditDelta, carrierId);
        }
        Integer newScore = db.jdbc().queryForObject(
                "SELECT credit_score FROM carrier WHERE id = ?", Integer.class, carrierId);

        String note = "冷链异常赔付正式入账（方案 #" + proposalId + " v" + p.get("version") + "）：责任="
                + party + "，可收 " + p.get("acceptable_pieces") + " / 拒收 " + p.get("rejected_pieces")
                + "，货损 " + p.get("loss_amount") + "，承运商扣罚 " + penalty + "，客户赔付 " + comp
                + "，信用分调整 " + creditDelta + "→" + newScore + "。审批链完整且证据齐备。";
        db.jdbc().update("""
                UPDATE trip SET liability_party=?, penalty_amount=?, compensation_amount=?, liability_note=?,
                    settled_at=?, status='DONE', current_stage='DONE', updated_at=?
                WHERE id=?
                """, "NONE".equals(party) ? null : party, penalty, comp, note,
                Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()), tripId);

        // 解冻冷链事件
        db.jdbc().update("""
                UPDATE cold_event SET frozen=FALSE, released_at=?, updated_at=? WHERE id=?
                """, Timestamp.valueOf(db.now()), Timestamp.valueOf(db.now()), ceId);
        db.jdbc().update("UPDATE settlement_freeze SET status='RELEASED', released_by=?, released_at=? " +
                "WHERE cold_event_id=? AND status='FROZEN'", actor, Timestamp.valueOf(db.now()), ceId);
        db.event(tripId, "SETTLEMENT_EFFECTIVE", "SETTLE", actor, note);

        Map<String, Object> view = proposalView(proposalId);
        view.put("alreadyEffective", false);
        return view;
    }

    /** 审批驳回方案（结算复核不通过）：置 REJECTED，需补证后重新计算。 */
    @Transactional
    public Map<String, Object> reject(long proposalId, String note, String actor) {
        Map<String, Object> p = requireProposal(proposalId);
        long ceId = ((Number) p.get("cold_event_id")).longValue();
        if ("EFFECTIVE".equals(p.get("status"))) {
            throw ApiException.conflict("已生效结算不能驳回，须走冲正流程");
        }
        db.jdbc().update("UPDATE settlement_proposal SET status='REJECTED', decided_by=?, decided_at=? WHERE id=?",
                actor, Timestamp.valueOf(db.now()), proposalId);
        db.event(tripIdOf(ceId), "PROPOSAL_REJECTED", "SUPERVISOR", actor,
                "赔付方案 #" + proposalId + " 被驳回：" + note + "，撤销未生效草稿并退回补证/重算");
        return proposalView(proposalId);
    }

    /**
     * 撤销某事件下所有未生效方案（证据替换/审批驳回/客户改判时调用）。
     * EFFECTIVE（已写 ledger）永不在此处被改动。
     */
    @Transactional
    public int voidNonEffective(long ceId, String reason, String actor) {
        List<Map<String, Object>> ids = db.jdbc().queryForList(
                "SELECT id, version FROM settlement_proposal " +
                "WHERE cold_event_id = ? AND status IN ('DRAFT','APPROVING','APPROVED')", ceId);
        for (Map<String, Object> r : ids) {
            long id = ((Number) r.get("id")).longValue();
            db.jdbc().update(
                    "UPDATE settlement_proposal SET status='SUPERSEDED', created_reason = created_reason || ? WHERE id=?",
                    " || 作废原因：" + reason + "（" + actor + "）", id);
        }
        return ids.size();
    }

    // ============================================================
    // 门控与读模型
    // ============================================================

    /** 写入正式结算前必须全部解除的阻塞项。 */
    public List<String> settlementBlockers(long ceId) {
        List<String> blockers = new ArrayList<>();
        if (!escalations.chainComplete(ceId)) {
            blockers.add("升级审批链未完整（仍有 PENDING/REJECTED 审批）");
        }
        // 大额赔付须有通过的 L2 终审
        List<Map<String, Object>> latest = db.jdbc().queryForList("""
                SELECT compensation_amount FROM settlement_proposal
                WHERE cold_event_id=? AND status IN ('DRAFT','APPROVING','APPROVED')
                ORDER BY version DESC LIMIT 1
                """, ceId);
        if (!latest.isEmpty()) {
            BigDecimal comp = (BigDecimal) latest.get(0).get("compensation_amount");
            if (comp.compareTo(l2Threshold(ceId)) >= 0 && !escalations.hasApprovedL2(ceId)) {
                blockers.add("赔付金额 " + comp + " 达到 L2 阈值，尚缺质量与商务经理 L2 终审");
            }
        }
        Integer openTickets = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM anomaly_ticket WHERE cold_event_id = ? AND status IN "
                        + "('OPEN','AWAIT_CUSTOMER','REVIEWING','ESCALATED')", Integer.class, ceId);
        if (openTickets != null && openTickets > 0) {
            blockers.add("仍有 " + openTickets + " 张异常工单未闭环");
        }
        Integer pendingResp = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM anomaly_ticket WHERE cold_event_id = ? AND responsibility='PENDING'",
                Integer.class, ceId);
        if (pendingResp != null && pendingResp > 0) {
            blockers.add(pendingResp + " 张工单责任尚未判定");
        }
        Integer frozen = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM settlement_freeze WHERE cold_event_id=? AND status='FROZEN'",
                Integer.class, ceId);
        if (frozen != null && frozen > 0) {
            blockers.add(frozen + " 个批次结算仍处冻结状态");
        }
        Integer undecided = db.jdbc().queryForList("""
                SELECT ie.batch_no FROM inbound_event ie
                WHERE ie.cold_event_id = ?
                  AND EXISTS (SELECT 1 FROM anomaly_ticket t WHERE t.cold_event_id=?
                              AND (t.batch_no = ie.batch_no OR t.batch_no IS NULL))
                  AND NOT EXISTS (SELECT 1 FROM customer_decision cd JOIN anomaly_ticket t2 ON t2.id=cd.ticket_id
                                  WHERE t2.cold_event_id=? AND cd.batch_no=ie.batch_no)
                """, ceId, ceId, ceId).size();
        if (undecided > 0) {
            blockers.add(undecided + " 个异常批次尚无客户收货决定");
        }
        return blockers;
    }

    @SuppressWarnings("unchecked")
    private static List<String> blockersString(Map<String, Object> calc) {
        return (List<String>) calc.get("blockers");
    }

    public Map<String, Object> requireProposal(long id) {
        List<Map<String, Object>> rows = db.jdbc().queryForList(
                "SELECT * FROM settlement_proposal WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("赔付方案不存在: " + id);
        }
        return rows.get(0);
    }

    public Map<String, Object> proposalView(long id) {
        Map<String, Object> p = requireProposal(id);
        p.put("entries", db.jdbc().queryForList(
                "SELECT * FROM settlement_entry WHERE proposal_id = ? ORDER BY id", id));
        p.put("blockers", settlementBlockers(((Number) p.get("cold_event_id")).longValue()));
        p.put("readyToPost", ((List<?>) p.get("blockers")).isEmpty());
        return p;
    }

    public List<Map<String, Object>> listByEvent(long ceId) {
        return db.jdbc().queryForList(
                "SELECT id, version, status, liability_party, acceptable_pieces, rejected_pieces, " +
                "loss_amount, penalty_amount, compensation_amount, created_reason, created_by, created_at, " +
                "decided_by, decided_at FROM settlement_proposal WHERE cold_event_id=? ORDER BY version", ceId);
    }

    public long idOf(String eventNo) {
        List<Map<String, Object>> r = db.jdbc().queryForList(
                "SELECT id FROM cold_event WHERE event_no = ?", eventNo);
        if (r.isEmpty()) {
            throw ApiException.notFound("冷链事件不存在: " + eventNo);
        }
        return ((Number) r.get(0).get("id")).longValue();
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 批次的有效收货结论：主管绑定裁定 > 最新客户决定。 */
    private Map<String, Object> effectiveChoice(long ceId, String batchNo, int expected) {
        List<Map<String, Object>> decisions = db.jdbc().queryForList("""
                SELECT cd.decision, cd.accepted_pieces, cd.decided_by, cd.decided_at
                FROM customer_decision cd JOIN anomaly_ticket t ON t.id = cd.ticket_id
                WHERE t.cold_event_id = ? AND cd.batch_no = ?
                ORDER BY (cd.decided_by LIKE 'SUPERVISOR:%') DESC, cd.decided_at DESC
                """, ceId, batchNo);
        if (decisions.isEmpty()) {
            return Map.of("decision", null, "acceptedPieces", expected, "decidedBy", null);
        }
        Map<String, Object> d = decisions.get(0);
        int accepted = ((Number) d.get("accepted_pieces")).intValue();
        String decision = (String) d.get("decision");
        if ("REJECT".equals(decision)) accepted = 0;
        return Map.of("decision", decision, "acceptedPieces", accepted,
                "decidedBy", d.get("decided_by"));
    }

    private String latestQcForBatch(long ceId, String batchNo) {
        // 从最新质检证据中取该批次结论（best-effort）
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                SELECT e.content FROM ticket_evidence e JOIN anomaly_ticket t ON t.id=e.ticket_id
                WHERE t.cold_event_id=? AND e.evidence_type='QC_RECHECK'
                ORDER BY e.submitted_at DESC
                """, ceId);
        for (Map<String, Object> row : rows) {
            String c = String.valueOf(row.get("content"));
            int idx = c.indexOf("conclusions=");
            if (idx < 0) continue;
            try {
                List<Map<String, Object>> list =
                        com.logpark.coldchain.support.Json.parseList(c.substring(idx + "conclusions=".length()));
                for (Map<String, Object> m : list) {
                    if (batchNo.equals(String.valueOf(m.get("batchNo")))) {
                        return String.valueOf(m.get("conclusion")).toUpperCase();
                    }
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private record Liability(boolean hasAnomaly, String party, List<String> basis) {}

    private Liability liabilityOfBatch(long ceId, String batchNo) {
        List<Map<String, Object>> tickets = db.jdbc().queryForList("""
                SELECT category, responsibility, status FROM anomaly_ticket
                WHERE cold_event_id = ? AND (batch_no = ? OR batch_no IS NULL)
                ORDER BY id
                """, ceId, batchNo);
        if (tickets.isEmpty()) {
            return new Liability(false, "NONE", List.of());
        }
        Set<String> parties = new LinkedHashSet<>();
        List<String> basis = new ArrayList<>();
        boolean anyPending = false;
        for (Map<String, Object> t : tickets) {
            String r = String.valueOf(t.get("responsibility"));
            basis.add(t.get("category") + ":" + r + "(" + t.get("status") + ")");
            if ("PENDING".equals(r)) { anyPending = true; continue; }
            if (!"NONE".equals(r)) parties.add(r);
        }
        String party = parties.isEmpty() ? (anyPending ? "PENDING" : "NONE")
                : parties.size() == 1 ? parties.iterator().next() : "SHARED";
        return new Liability(true, party, basis);
    }

    private Map<String, Object> contractOf(long ownerId) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                SELECT compensation_rate_max, downgrade_rate, min_claim_amount, require_l2_threshold
                FROM owner_contract WHERE owner_id = ?
                """, ownerId);
        if (rows.isEmpty()) {
            return Map.of("rateMax", BigDecimal.ONE, "downgradeRate", BigDecimal.valueOf(0.5),
                    "minClaim", BigDecimal.ZERO, "l2Threshold", BigDecimal.valueOf(5000));
        }
        Map<String, Object> r = rows.get(0);
        return Map.of("rateMax", r.get("compensation_rate_max"),
                "downgradeRate", r.get("downgrade_rate"),
                "minClaim", r.get("min_claim_amount"),
                "l2Threshold", r.get("require_l2_threshold"));
    }

    private BigDecimal resolveUnitValue(long tripId, Map<String, Object> batch) {
        List<Map<String, Object>> r = db.jdbc().queryForList("""
                SELECT tc.value_per_piece FROM trip_cargo tc
                WHERE tc.trip_id = ? AND tc.cargo_type = ?
                  AND (?::bigint IS NULL OR tc.owner_id = ?)
                ORDER BY tc.id LIMIT 1
                """, tripId, batch.get("cargo_type"), batch.get("owner_id"), batch.get("owner_id"));
        if (!r.isEmpty()) {
            return (BigDecimal) r.get(0).get("value_per_piece");
        }
        return db.jdbc().queryForObject(
                "SELECT default_value FROM cargo_owner WHERE id = ?",
                BigDecimal.class, ((Number) batch.get("owner_id")).longValue());
    }

    private int nextVersion(long ceId) {
        Integer v = db.jdbc().queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM settlement_proposal WHERE cold_event_id=?",
                Integer.class, ceId);
        return (v == null ? 0 : v) + 1;
    }

    private long tripIdOf(long ceId) {
        return db.jdbc().queryForObject("SELECT trip_id FROM cold_event WHERE id=?", Long.class, ceId);
    }
}
