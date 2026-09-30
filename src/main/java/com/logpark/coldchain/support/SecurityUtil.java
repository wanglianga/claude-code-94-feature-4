package com.logpark.coldchain.support;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * 认证工具：把 Spring Security 角色映射为补证业务角色，并读取登录名。
 *
 * 补证四方与系统角色的映射：
 *  CARRIER   → 承运商（设备原始日志/停靠说明）
 *  DISPATCH  → PARK（园区：闸口/卸货记录）
 *  QC        → QC（质检复检结论）
 *  CS        → CUSTOMER（客户：继续收/降级/拒收）
 */
public final class SecurityUtil {

    private SecurityUtil() {}

    /** 返回业务角色 CARRIER/PARK/QC/CUSTOMER/SETTLE/SUPERVISOR。 */
    public static String domainRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .map(r -> switch (r) {
                    case "DISPATCH" -> "PARK";
                    case "CS" -> "CUSTOMER";
                    default -> r;
                })
                .orElseThrow(() -> ApiException.badRequest("无法识别当前角色"));
    }

    public static boolean hasRole(Authentication auth, String role) {
        String prefix = "ROLE_" + role;
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(prefix));
    }
}
