package dev.learning.mall.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService users(PasswordEncoder encoder, @Value("${mall.demo-password}") String password) {
        return new InMemoryUserDetailsManager(
                User.withUsername("student").password(encoder.encode(password)).roles("USER").build(),
                User.withUsername("classmate").password(encoder.encode(password)).roles("USER").build(),
                // 店员账号：演示「同一助手、不同权限」。密码默认与演示账号一致，可用 MALL_STAFF_PASSWORD 覆盖。
                User.withUsername("admin").password(encoder.encode(password)).roles("USER", "STAFF").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login", "/css/**", "/js/**", "/art/**", "/error", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/products/**", "/product/**").permitAll()
                // 改价 / 改库存属于经营动作，只有店员能碰。注意这条必须排在 anyRequest() 之前：
                // 上面那条 GET 是方法限定的，不会把它放行，但顺序仍然是这类配置最容易出错的地方。
                .requestMatchers(HttpMethod.POST, "/api/products/*/price", "/api/products/*/stock")
                    .hasRole("STAFF")
                // 助手安全审计只对店员开放：URL 级限制与 Controller/Service 的显式校验形成双重防线。
                .requestMatchers("/api/assistant/audit").hasRole("STAFF")
                // Agent 运行轨迹同样只对店员开放：里面含工具参数与回答摘要，属于运营侧数据。
                .requestMatchers("/api/assistant/runs", "/api/assistant/runs/*/steps").hasRole("STAFF")
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/"))
            .exceptionHandling(errors -> errors
                .defaultAuthenticationEntryPointFor((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");
                }, new AntPathRequestMatcher("/api/**"))
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"请求校验失败，请刷新页面后重试\"}");
                }));
        // 保留默认 CSRF 防护，页面把 token 带入每个修改请求。
        return http.build();
    }
}
