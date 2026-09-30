package com.logpark.coldchain.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;

/**
 * 六类角色：CARRIER（承运商）、DISPATCH（园区调度）、QC（质检）、
 * CS（客户客服）、SETTLE（结算）、SUPERVISOR（升级审批：运营主管/质量经理）。
 * 使用 HTTP Basic，无状态会话。
 *
 * feature-4-b 的工单补证接口由四方共用同一组 URL，角色-证据类型约束在业务层强制。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(DataSource dataSource) {
        JdbcUserDetailsManager users = new JdbcUserDetailsManager(dataSource);
        users.setUsersByUsernameQuery(
                "SELECT username, password, enabled FROM app_user WHERE username = ?");
        users.setAuthoritiesByUsernameQuery(
                "SELECT username, 'ROLE_' || role FROM app_user WHERE username = ?");
        return users;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> {})
            .authorizeHttpRequests(reg -> reg
                // 探活、OpenAPI 3 / Swagger UI 免认证
                .requestMatchers("/api/health", "/error").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                 "/swagger-ui.html", "/swagger-resources/**").permitAll()

                // ---- 基线业务 ----
                .requestMatchers("/api/appointments/**", "/api/my/trips/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "SUPERVISOR")
                .requestMatchers("/api/gate/**").hasRole("DISPATCH")
                .requestMatchers("/api/dispatch/**").hasRole("DISPATCH")
                .requestMatchers("/api/dock/trips/*/receive").hasRole("DISPATCH")
                .requestMatchers("/api/dock/trips/*/temperature").hasRole("CARRIER")
                .requestMatchers("/api/dock/trips/*/unload").hasRole("QC")
                .requestMatchers("/api/qc/**").hasRole("QC")
                .requestMatchers("/api/disposition/**").hasAnyRole("QC", "DISPATCH")
                .requestMatchers("/api/signoff/**").hasRole("CS")
                .requestMatchers("/api/settle/**").hasRole("SETTLE")
                .requestMatchers("/api/board/**").hasAnyRole("DISPATCH", "QC", "SETTLE", "SUPERVISOR")
                .requestMatchers("/api/cs/**").hasRole("CS")
                .requestMatchers("/api/trace/**").hasAnyRole("CS", "CARRIER", "DISPATCH", "SETTLE", "SUPERVISOR")
                .requestMatchers("/api/incidents/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "SUPERVISOR")

                // ---- feature-4-b ----
                // 创建冷链事件（预约入园聚合）：承运商发起
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/cold-events/**").hasAnyRole("CARRIER", "DISPATCH")
                .requestMatchers("/api/cold-events/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "SUPERVISOR")
                // 设备回调（冷机/舱门/铅封）由设备平台经承运集成账号推送
                .requestMatchers("/api/callback/**").hasRole("CARRIER")
                // 卸货扫描批次
                .requestMatchers("/api/events/trips/*/unload-scan").hasRole("DISPATCH")
                // 工单补证/客户决定：四方共用，证据类型角色约束在业务层
                .requestMatchers("/api/tickets/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "SUPERVISOR")
                // 复盘：质检/调度/主管；客户时限延期仅主管
                .requestMatchers("/api/review/tickets/*/extend-due").hasRole("SUPERVISOR")
                .requestMatchers("/api/review/**").hasAnyRole("QC", "DISPATCH", "SUPERVISOR")
                // 升级审批链：主管（L1/L2），结算/调度只读
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/escalations/**").hasRole("SUPERVISOR")
                .requestMatchers("/api/escalations/**").hasAnyRole("SUPERVISOR", "SETTLE", "DISPATCH", "QC", "CS")
                // 赔付候选方案：结算编制，主管审批
                .requestMatchers("/api/proposals/**").hasAnyRole("SETTLE", "SUPERVISOR", "DISPATCH")
                // 一页式审计时间线：全部内部角色可读
                .requestMatchers("/api/audit/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "SUPERVISOR")

                .requestMatchers("/api/whoami", "/api/clock").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"error\":\"未认证或账号/口令错误\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"error\":\"当前角色无权执行该操作\"}");
                }));
        return http.build();
    }
}
