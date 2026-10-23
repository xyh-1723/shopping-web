package dev.learning.mall.config;

import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * 账号清单。
     *
     * <p><b>商家账号是从 {@code merchant} 表生成的，不是在这里手写出来的</b>（阶段 3a）。
     * 手写 N 个商家账号的做法有个致命问题：账号与「哪一家店」之间没有绑定，
     * 于是每一个商家账号都天然拥有全部店铺的数据——造 200 个账号等于造 200 个越权账号。
     * 现在 {@code merchant.owner_username} 是唯一绑定点，账号由表推导，
     * 「有多少家店就有多少个商家账号」这句话才成立。
     *
     * <p><b>同一份口令只做一次 BCrypt</b>：BCrypt(cost=10) 一次约 60-100ms，
     * 200 个商家账号各编码一遍会把启动时间拖到十几秒，而它们用的是同一个配置值。
     * {@code User.withUsername(...).password(...)} 收的本来就是<b>已编码</b>的哈希串，
     * 复用同一个串不改变任何语义，只省掉 199 次毫无意义的重复计算。
     */
    @Bean
    UserDetailsService users(PasswordEncoder encoder, MerchantDirectory merchants,
                             @Value("${mall.demo-password}") String password,
                             @Value("${mall.merchant-password}") String merchantPassword) {
        String demoHash = encoder.encode(password);
        String merchantHash = encoder.encode(merchantPassword);

        List<UserDetails> accounts = new ArrayList<>();
        accounts.add(User.withUsername("student").password(demoHash).roles("USER").build());
        accounts.add(User.withUsername("classmate").password(demoHash).roles("USER").build());
        // 店员账号：演示「同一助手、不同权限」。密码与演示账号共用一份 ${mall.demo-password}。
        accounts.add(User.withUsername("admin").password(demoHash).roles("USER", "STAFF").build());
        // 商家账号：一家店一个，店主账号就是 merchant.owner_username。
        // 角色刻意只有 MERCHANT、不带 STAFF——它能做什么由这一个角色说了算，
        // 日常运营执行权（改价 / 改库存 / 发货）不下发给经营决策方。
        // 1 号店的店主沿用既有 merchant 账号：它在既有测试与文档里就是「那个商家」。
        for (MerchantView shop : merchants.all()) {
            accounts.add(User.withUsername(shop.ownerUsername()).password(merchantHash)
                    .roles("USER", "MERCHANT").build());
        }
        return new InMemoryUserDetailsManager(accounts);
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
                // 退款审批只对商家开放：钱的决定权与运营执行权分离（设计 §3.4）。
                // 这里刻意用 hasRole("MERCHANT") 而不是 hasAnyRole("STAFF","MERCHANT")：
                // 让店员能批退款，等于把「店员自己申请退款自己批准」这条路打开。
                // 审批同时也是 RefundService 内部二次校验的<b>外层</b>——少一层都不算够。
                .requestMatchers(HttpMethod.POST, "/api/refunds/*/approve", "/api/refunds/*/reject")
                    .hasRole("MERCHANT")
                // 待审批队列是经营侧可见性（只读）：店员与商家都能看，但普通买家不能。
                .requestMatchers(HttpMethod.GET, "/api/refunds/pending").hasAnyRole("STAFF", "MERCHANT")
                // 注意：这条 GET 的 /api/refunds/mine 是买家看自己的退款，落到 anyRequest().authenticated()，
                // 由 RefundService 按订单归属再收一层，不需要在这里特别放行。
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/"))
            .exceptionHandling(errors -> errors
                // 未登录给 401 + JSON，而不是重定向到登录页：302 + HTML 对这些机器读的
                // 端点毫无意义，还会让监控脚本把「登录页 200」误判成「接口正常」。
                //
                // ⚠️ 别把 /actuator/** 的 401 归功于下面这个 OrRequestMatcher——它对
                // /actuator/** 而言是**空操作**，加不加行为完全一样。
                // 实测原因：Spring Security 的 ExceptionHandlingConfigurer
                // .createDefaultEntryPoint() 在 defaultAuthenticationEntryPointFor
                // **只注册了 1 条**时会完全不看 matcher，直接把它当全局默认入口返回；
                // 只有 ≥2 条才构造 DelegatingAuthenticationEntryPoint 逐个匹配。
                // 所以把 /actuator/** 这一半撤掉后，匿名访问 /cart、/orders、
                // /some/unknown/path 依然全部返回 401 + 下面这段 JSON——也就是说
                // /actuator/** 在加这行之前就已经被这个全局默认覆盖了。
                //
                // 既然是空操作为什么还留着：它是**显式防御**。一旦将来有人再加第二个
                // defaultAuthenticationEntryPointFor，delegation 就会真正生效，届时缺了这行的
                // /actuator/** 会掉回 formLogin 的 302 跳登录页。留着它，那条路才不会退化。
                //
                // 真正让 /actuator/** 需要登录的是上面的 anyRequest().authenticated()
                // （授权规则）。这里只换**入口**（怎么拒绝），不改变「要不要登录」。
                .defaultAuthenticationEntryPointFor((req, res, ex) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");
                }, new OrRequestMatcher(new AntPathRequestMatcher("/api/**"),
                        new AntPathRequestMatcher("/actuator/**")))
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"请求校验失败，请刷新页面后重试\"}");
                }));
        // 保留默认 CSRF 防护，页面把 token 带入每个修改请求。
        return http.build();
    }
}
