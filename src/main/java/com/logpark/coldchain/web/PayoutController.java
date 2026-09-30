package com.logpark.coldchain.web;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.service.PayoutService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 赔付候选方案与审批链：计算/提交/审批/驳回。
 * 审批链完整前赔付不生效、不写入结算；驳回或证据替换自动撤销重算。
 */
@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

    private final PayoutService svc;
    private final Db db;

    public PayoutController(PayoutService svc, Db db) {
        this.svc = svc;
        this.db = db;
    }

    /** 车次的赔付单（含审批链）。 */
    @GetMapping
    public List<Map<String, Object>> ofTrip(@RequestParam String tripCode) {
        return svc.ofTrip(db.tripId(tripCode));
    }

    @GetMapping("/{id}")
    public Map<String, Object> one(@PathVariable long id) {
        return svc.payout(id);
    }

    /** 重新计算车次赔付候选（撤销未生效草稿，生成新版本）。 */
    @PostMapping("/trips/{code}/compute")
    public List<Map<String, Object>> compute(@PathVariable String code, Authentication auth) {
        return svc.computeForTrip(db.tripId(code), auth.getName());
    }

    /** 提交审批（生成审批链；升级单追加质检复核）。 */
    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable long id, Authentication auth) {
        return svc.submit(id, auth.getName());
    }

    /** 当前环节审批通过。 */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body,
                                       Authentication auth) {
        return svc.approve(id, roleOf(auth), auth.getName(),
                body == null ? null : (String) body.get("comment"));
    }

    /** 当前环节驳回（自动撤销未生效草稿并重算）。 */
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body,
                                      Authentication auth) {
        return svc.reject(id, roleOf(auth), auth.getName(),
                body == null ? null : (String) body.get("comment"));
    }

    private static String roleOf(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5))
                .findFirst().orElse("");
    }
}
