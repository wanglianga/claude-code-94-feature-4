package com.logpark.coldchain.web;

import com.logpark.coldchain.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 一页可审计异常时间线。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService svc;

    public AuditController(AuditService svc) {
        this.svc = svc;
    }

    /** 某车次的一页可审计异常时间线（证据/处置/责任/赔付/后续预约影响）。 */
    @GetMapping("/trips/{code}")
    public Map<String, Object> audit(@PathVariable String code) {
        return svc.audit(code);
    }
}
