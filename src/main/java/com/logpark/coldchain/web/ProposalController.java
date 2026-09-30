package com.logpark.coldchain.web;

import com.logpark.coldchain.service.ColdEventService;
import com.logpark.coldchain.service.SettlementProposalService;
import com.logpark.coldchain.support.ApiException;
import com.logpark.coldchain.support.SecurityUtil;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 赔付候选方案：测算预览 → 生成版本化草稿 → 提交审批链 → 审批链完整后幂等入账。
 * 证据替换/审批驳回会撤销未生效草稿并重新计算；正式赔付全事件唯一。
 */
@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    private final SettlementProposalService proposals;
    private final ColdEventService events;

    public ProposalController(SettlementProposalService proposals, ColdEventService events) {
        this.proposals = proposals;
        this.events = events;
    }

    /** 实时测算候选方案（不落库），含未满足生效条件的 blockers。 */
    @GetMapping("/cold-events/{eventNo}/calculate")
    public Map<String, Object> calculate(@PathVariable String eventNo) {
        return proposals.calculate(proposals.idOf(eventNo));
    }

    /** 生成新版本 DRAFT（旧的未生效方案作废）。 */
    @PostMapping("/cold-events/{eventNo}/generate")
    public Map<String, Object> generate(@PathVariable String eventNo,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        Principal principal) {
        String reason = body == null || body.get("reason") == null
                ? "结算员编制赔付候选方案" : String.valueOf(body.get("reason"));
        return proposals.generate(eventNo, reason, principal.getName());
    }

    @GetMapping("/cold-events/{eventNo}")
    public List<Map<String, Object>> list(@PathVariable String eventNo) {
        return proposals.listByEvent(proposals.idOf(eventNo));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return proposals.proposalView(id);
    }

    /** 提交审批链（金额达阈值自动补 L2）。 */
    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable long id, Principal principal) {
        return proposals.submit(id, principal.getName());
    }

    /** 审批链完整后正式写入结算（幂等，重复/并发不产生重复赔付）。 */
    @PostMapping("/{id}/post")
    public Map<String, Object> post(@PathVariable long id, Principal principal) {
        return proposals.postEffective(id, principal.getName());
    }

    /** 审批驳回：仅主管；撤销未生效草稿，退回补证/重算。 */
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      Principal principal, Authentication auth) {
        if (!SecurityUtil.hasRole(auth, "SUPERVISOR")) {
            throw ApiException.conflict("只有审批主管（SUPERVISOR）可以驳回赔付方案");
        }
        String note = body == null || body.get("note") == null ? "" : String.valueOf(body.get("note"));
        return proposals.reject(id, note, principal.getName());
    }
}
