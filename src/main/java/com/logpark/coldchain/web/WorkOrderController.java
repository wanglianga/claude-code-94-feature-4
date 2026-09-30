package com.logpark.coldchain.web;

import com.logpark.coldchain.repo.Db;
import com.logpark.coldchain.service.EvidenceService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 异常工单：查询、角色补证、复盘分类、客户收货决定、结案解冻。
 */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final EvidenceService svc;
    private final Db db;

    public WorkOrderController(EvidenceService svc, Db db) {
        this.svc = svc;
        this.db = db;
    }

    /** 工单列表（可按车次/状态过滤）。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String tripCode,
                                          @RequestParam(required = false) String status) {
        Long tripId = tripCode == null ? null : db.tripId(tripCode);
        return svc.list(tripId, status);
    }

    /** 工单详情：补证版本链、客户决定、冻结记录。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return svc.detail(id);
    }

    /** 角色补证（承运商=设备日志/停靠说明；园区=闸口/卸货记录；质检=复检结论）。 */
    @PostMapping("/{id}/evidence")
    public Map<String, Object> evidence(@PathVariable long id, @RequestBody Map<String, Object> body,
                                        Authentication auth) {
        return svc.submitEvidence(id, body, auth.getName());
    }

    /** 人工复盘分类（传感器离线/真实超温等）。 */
    @PostMapping("/{id}/classify")
    public Map<String, Object> classify(@PathVariable long id, @RequestBody Map<String, Object> body,
                                        Authentication auth) {
        return svc.classify(id, String.valueOf(body.get("classification")).toUpperCase(),
                body.get("note") == null ? null : String.valueOf(body.get("note")), auth.getName());
    }

    /** 客户收货决定（时限内；继续收货/降级收货/拒收；部分可收部分拒收）。 */
    @PostMapping("/{id}/customer-decision")
    public Map<String, Object> customerDecision(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                Authentication auth) {
        return svc.customerDecision(id, body, auth.getName());
    }

    /** 结案：责任定版，解冻批次，触发赔付候选重算。 */
    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable long id, @RequestBody Map<String, Object> body,
                                       Authentication auth) {
        return svc.resolve(id, body.get("responsibility") == null ? null : String.valueOf(body.get("responsibility")),
                body.get("note") == null ? null : String.valueOf(body.get("note")), auth.getName());
    }
}
