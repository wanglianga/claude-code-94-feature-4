package com.logpark.coldchain.web;

import com.logpark.coldchain.service.ColdEventService;
import com.logpark.coldchain.service.EscalationService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** 升级审批链 L1→L2（主管），冲突裁定与驳回重做。 */
@RestController
@RequestMapping("/api")
public class EscalationController {

    private final EscalationService escalations;
    private final ColdEventService events;

    public EscalationController(EscalationService escalations, ColdEventService events) {
        this.escalations = escalations;
        this.events = events;
    }

    /** 主管审批：{decision: APPROVE/REJECT, note, requireL2, bindingChoices:[{batchNo,decision,acceptedPieces}]} */
    @PostMapping("/escalations/{id}/decision")
    public Map<String, Object> decide(@PathVariable long id,
                                      @RequestBody Map<String, Object> body, Principal principal) {
        return escalations.decide(id, body, principal.getName());
    }

    @GetMapping("/escalations/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return escalations.view(id);
    }

    @GetMapping("/cold-events/{eventNo}/escalations")
    public List<Map<String, Object>> byEvent(@PathVariable String eventNo) {
        long ceId = ((Number) events.requireEventByNo(eventNo).get("id")).longValue();
        return escalations.listByEvent(ceId);
    }
}
