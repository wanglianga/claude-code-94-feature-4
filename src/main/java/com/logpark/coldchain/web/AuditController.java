package com.logpark.coldchain.web;

import com.logpark.coldchain.service.AuditService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 一页式可审计冷链异常时间线。 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/trips/{code}")
    public Map<String, Object> byTrip(@PathVariable String code) {
        return audit.auditByTripCode(code);
    }

    @GetMapping("/events/{eventNo}")
    public Map<String, Object> byEvent(@PathVariable String eventNo) {
        long ceId = audit.idOfEvent(eventNo);
        return audit.audit(ceId);
    }
}
