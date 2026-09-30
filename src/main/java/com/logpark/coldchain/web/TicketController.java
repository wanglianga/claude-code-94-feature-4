package com.logpark.coldchain.web;

import com.logpark.coldchain.service.ColdEventService;
import com.logpark.coldchain.service.TicketService;
import com.logpark.coldchain.support.SecurityUtil;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 异常工单：四方并行补证（角色证据类型在业务层强约束）、客户逐批次决定、
 * 复盘结论（离线 vs 超温）、工单闭环、客户时限延期。
 */
@RestController
@RequestMapping("/api")
public class TicketController {

    private final TicketService tickets;
    private final ColdEventService events;

    public TicketController(TicketService tickets, ColdEventService events) {
        this.tickets = tickets;
        this.events = events;
    }

    /** 提交本角色证据（自动版本化、只追加，旧版本保留）。 */
    @PostMapping("/tickets/{ticketNo}/evidence")
    public Map<String, Object> evidence(@PathVariable String ticketNo,
                                        @RequestBody Map<String, Object> body, Principal principal) {
        return tickets.addEvidence(ticketNo, body, SecurityUtil.domainRole((Authentication) principal),
                principal.getName());
    }

    /** 客户在时限内逐批次选择 继续收/降级/拒收（可部分收部分拒）。 */
    @PostMapping("/tickets/{ticketNo}/customer-decision")
    public Map<String, Object> customerDecision(@PathVariable String ticketNo,
                                                @RequestBody Map<String, Object> body,
                                                Principal principal, Authentication auth) {
        if (!SecurityUtil.hasRole(auth, "CS")) {
            throw com.logpark.coldchain.support.ApiException.conflict(
                    "只有客户客服（CS）可以代为提交客户收货决定");
        }
        return tickets.customerDecision(ticketNo, body, principal.getName());
    }

    /** 复盘结论：SENSOR_OFFLINE / REAL_EXCURSION / INCONCLUSIVE + 责任方。 */
    @PostMapping("/review/tickets/{ticketNo}/conclusion")
    public Map<String, Object> review(@PathVariable String ticketNo,
                                      @RequestBody Map<String, Object> body, Principal principal) {
        return tickets.reviewConclusion(ticketNo, body, principal.getName());
    }

    /** 闭环工单（责任已定、非升级中），释放关联批次结算冻结。 */
    @PostMapping("/review/tickets/{ticketNo}/close")
    public Map<String, Object> close(@PathVariable String ticketNo,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     Principal principal) {
        String note = body == null || body.get("note") == null ? "" : String.valueOf(body.get("note"));
        return tickets.closeTicket(ticketNo, note, principal.getName());
    }

    /** 主管延长客户决定时限。 */
    @PostMapping("/review/tickets/{ticketNo}/extend-due")
    public Map<String, Object> extendDue(@PathVariable String ticketNo,
                                         @RequestBody Map<String, Object> body, Principal principal,
                                         Authentication auth) {
        if (!SecurityUtil.hasRole(auth, "SUPERVISOR")) {
            throw com.logpark.coldchain.support.ApiException.conflict("只有审批主管可以延长客户决定时限");
        }
        int hours = ((Number) body.getOrDefault("hours", 24)).intValue();
        return tickets.extendDue(ticketNo, hours, principal.getName());
    }

    @GetMapping("/tickets/{ticketNo}")
    public Map<String, Object> get(@PathVariable String ticketNo) {
        return tickets.ticketView(ticketNo);
    }

    /** 列出某冷链事件下的工单（可按 status 过滤）。 */
    @GetMapping("/tickets/cold-events/{eventNo}")
    public List<Map<String, Object>> byEvent(@PathVariable String eventNo,
                                             @RequestParam(required = false) String status) {
        long ceId = ((Number) events.requireEventByNo(eventNo).get("id")).longValue();
        return tickets.listByEvent(ceId, status);
    }
}
