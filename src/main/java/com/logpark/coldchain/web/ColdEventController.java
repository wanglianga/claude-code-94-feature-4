package com.logpark.coldchain.web;

import com.logpark.coldchain.service.ColdEventService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * feature-4-b 冷链事件聚合入口与设备回调。
 *
 * - 车辆入园已自动建立冷链事件；也可显式按车次建立/补充舱门传感器号；
 * - 冷机/舱门/铅封回调全部幂等（event_id 去重，重复回调不产生重复区间/工单/冻结）；
 * - 卸货扫描批次与预约批次核对，不一致自动建单并只冻结该批次。
 */
@RestController
@RequestMapping("/api")
public class ColdEventController {

    private final ColdEventService events;

    public ColdEventController(ColdEventService events) {
        this.events = events;
    }

    /** 显式按车次建立冷链事件（通常入园自动完成），可携带 doorSensorNo。 */
    @PostMapping("/cold-events/trips/{code}")
    public Map<String, Object> create(@PathVariable String code,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      Principal principal) {
        return events.createByTripCode(code, body, principal.getName());
    }

    @GetMapping("/cold-events/trips/{code}")
    public Map<String, Object> byTrip(@PathVariable String code) {
        return events.eventViewByTripCode(code);
    }

    @GetMapping("/cold-events/{eventNo}")
    public Map<String, Object> byNo(@PathVariable String eventNo) {
        return events.eventView(((Number) events.requireEventByNo(eventNo).get("id")).longValue());
    }

    /** 冷机设备回调：含温度点与设备状态（RUNNING/DEFROST/OFFLINE/ALARM）。 */
    @PostMapping("/callback/{eventNo}/chiller")
    public Map<String, Object> chiller(@PathVariable String eventNo,
                                       @RequestBody Map<String, Object> body, Principal principal) {
        return events.ingestChiller(eventNo, body, principal.getName());
    }

    /** 舱门传感器回调：OPEN/CLOSE。 */
    @PostMapping("/callback/{eventNo}/door")
    public Map<String, Object> door(@PathVariable String eventNo,
                                    @RequestBody Map<String, Object> body, Principal principal) {
        return events.ingestDoor(eventNo, body, principal.getName());
    }

    /** 电子铅封回调：SEALED/UNSEALED/ALARM。 */
    @PostMapping("/callback/{eventNo}/seal")
    public Map<String, Object> seal(@PathVariable String eventNo,
                                    @RequestBody Map<String, Object> body, Principal principal) {
        return events.ingestSeal(eventNo, body, principal.getName());
    }

    /** 月台卸货扫描批次（调度/月台作业）。 */
    @PostMapping("/events/trips/{code}/unload-scan")
    public Map<String, Object> unloadScan(@PathVariable String code,
                                          @RequestBody Map<String, Object> body, Principal principal) {
        return events.unloadScan(code, body, principal.getName());
    }
}
