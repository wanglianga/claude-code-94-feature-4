package com.logpark.coldchain.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 五类角色：CARRIER（承运商）、DISPATCH（园区调度）、QC（质检）、
 * CS（客户客服）、SETTLE（结算）。使用 HTTP Basic，无状态会话。
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
                .requestMatchers("/api/health", "/error").permitAll()
                .requestMatchers("/audit.html", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/appointments/**", "/api/my/trips/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE")
                .requestMatchers("/api/gate/**").hasRole("DISPATCH")
                .requestMatchers("/api/dispatch/**").hasRole("DISPATCH")
                .requestMatchers("/api/dock/trips/*/receive").hasRole("DISPATCH")
                .requestMatchers("/api/dock/trips/*/temperature").hasRole("CARRIER")
                .requestMatchers("/api/dock/trips/*/unload").hasRole("QC")
                .requestMatchers("/api/qc/**").hasRole("QC")
                .requestMatchers("/api/disposition/**").hasAnyRole("QC", "DISPATCH")
                .requestMatchers("/api/signoff/**").hasRole("CS")
                .requestMatchers("/api/settle/**").hasRole("SETTLE")
                .requestMatchers("/api/board/**").hasAnyRole("DISPATCH", "QC", "SETTLE")
                .requestMatchers("/api/cs/**").hasRole("CS")
                .requestMatchers("/api/trace/**").hasAnyRole("CS", "CARRIER", "DISPATCH", "SETTLE")
                .requestMatchers("/api/incidents/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE")
                // feature-4：回调（设备/闸口/卸货扫描）
                .requestMatchers("/api/callbacks/temperature", "/api/callbacks/heartbeat",
                        "/api/callbacks/door", "/api/callbacks/seal").hasAnyRole("CARRIER", "DISPATCH")
                .requestMatchers("/api/callbacks/unload-scan").hasAnyRole("QC", "DISPATCH")
                // feature-4：工单与补证
                .requestMatchers("/api/work-orders/*/evidence").hasAnyRole("CARRIER", "DISPATCH", "QC")
                .requestMatchers("/api/work-orders/*/customer-decision").hasRole("CUSTOMER")
                .requestMatchers("/api/work-orders/*/classify").hasAnyRole("QC", "DISPATCH")
                .requestMatchers("/api/work-orders/*/resolve").hasRole("DISPATCH")
                .requestMatchers("/api/work-orders/**").hasAnyRole(
                        "CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "CUSTOMER")
                // feature-4：赔付与审批
                .requestMatchers("/api/payouts/*/submit").hasRole("SETTLE")
                .requestMatchers("/api/payouts/*/approve", "/api/payouts/*/reject").hasAnyRole("QC", "DISPATCH", "SETTLE")
                .requestMatchers("/api/payouts/trips/*/compute").hasAnyRole("SETTLE", "DISPATCH")
                .requestMatchers("/api/payouts/**").hasAnyRole("CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "CUSTOMER")
                // feature-4：一页可审计时间线
                .requestMatchers("/api/audit/**").hasAnyRole(
                        "CARRIER", "DISPATCH", "QC", "CS", "SETTLE", "CUSTOMER")
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
