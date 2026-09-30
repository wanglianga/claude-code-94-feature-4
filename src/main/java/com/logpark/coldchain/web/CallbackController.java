package com.logpark.coldchain.web;

import com.logpark.coldchain.service.CallbackService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备/闸口/卸货回调：温度、心跳、舱门、铅封、卸货扫描。
 * 全部幂等：重复回调/重试不会产生重复事件、重复冻结或重复工单。
 */
@RestController
@RequestMapping("/api/callbacks")
public class CallbackController {

    private final CallbackService svc;

    public CallbackController(CallbackService svc) {
        this.svc = svc;
    }

    /** 冷机设备直推温度（自动检测超温合并区间/断点 → 冻结+工单）。 */
    @PostMapping("/temperature")
    public Map<String, Object> temperature(@RequestBody Map<String, Object> body, Authentication auth) {
        return svc.temperature(body, auth.getName());
    }

    /** 设备心跳（区分传感器离线/真实超温的依据）。 */
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestBody Map<String, Object> body, Authentication auth) {
        return svc.heartbeat(body, auth.getName());
    }

    /** 舱门传感器事件。 */
    @PostMapping("/door")
    public Map<String, Object> door(@RequestBody Map<String, Object> body, Authentication auth) {
        return svc.door(body, auth.getName());
    }

    /** 电子铅封事件（TAMPERED/BROKEN 自动冻结全车批次）。 */
    @PostMapping("/seal")
    public Map<String, Object> seal(@RequestBody Map<String, Object> body, Authentication auth) {
        return svc.seal(body, auth.getName());
    }

    /** 卸货扫描（与预约批次比对，不一致自动冻结）。 */
    @PostMapping("/unload-scan")
    public Map<String, Object> unloadScan(@RequestBody Map<String, Object> body, Authentication auth) {
        return svc.unloadScan(body, auth.getName());
    }
}
