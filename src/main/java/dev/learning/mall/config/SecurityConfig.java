package dev.learning.mall.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * 账号从内存迁到数据库（阶段 A3）：用 {@link JdbcUserDetailsManager} 读写
     * {@code users}/{@code authorities} 表。这样改密码、增删账号都不用重启，
     * 多实例部署也能共享同一份账号。
     *
     * <p>账号本身不在这个 bean 里创建——{@code UserAccountProvisioner} 在启动时
     * 幂等地把演示账号和商家账号同步进表。这里只负责给出「用哪张表存账号」。
     *
     * <p>商家账号与店的绑定仍走 {@code merchant.owner_username}，这个绑定点没变，
     * 变的只是「账号存在哪」。
     */
    @Bean
    UserDetailsService users(DataSource dataSource) {
        // 改密（阶段 A4）在 Controller 里用 PasswordEncoder + updateUser 手动完成，
        // 不走 JdbcUserDetailsManager.changePassword（它需要 setPasswordEncoder，
        // 而当前版本的 API 不直接暴露这个 setter）。
        return new JdbcUserDetailsManager(dataSource);
    }

    /**
     * 会话注册表：{@code maximumSessions(1)} 靠它知道「这个账号当前有几个活跃会话」。
     * 第二个登录会把第一个标记为过期，下次请求时被踢下线。
     */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 把 Servlet 容器的 session 销毁事件转给 Spring Security，
     * 否则用户关浏览器后 session 过期了，注册表还以为他在线——
     * 下次登录就会被「最大会话数」拦住。
     */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
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
                // 创建优惠券是经营侧动作：店员可建平台券 / 任意店券，商家只能建本店券。
                // 第二层「这张券是不是你的店」在 CouponService 里，由登录账号反查店铺号——
                // 请求体里没有店铺号可填，所以这里的 URL 限制已经是边界的一半，另一半是归属查询。
                .requestMatchers(HttpMethod.POST, "/api/coupons").hasAnyRole("STAFF", "MERCHANT")
                // 领券、看券、用券是买家动作，落到 anyRequest().authenticated()。
                // 商家中心（本店商品与库存、改密）只对商家开放。这里是第一层「你是不是商家」；
                // 第二层「那家店是不是你的」在 Controller/Service 里，由登录账号推出店铺号——
                // 请求参数里根本没有可篡改的店铺号。
                .requestMatchers("/api/merchant/**").hasRole("MERCHANT")
                // 发货是经营侧动作：店员能发任何人的，商家只能发自己那家店的
                // （第二层「是不是你的店」在 FulfillmentService 里，需要读库才知道，
                //  URL 匹配器做不到，所以这里只做第一层「你是不是经营侧的人」）。
                // 用 hasAnyRole 而不是 hasRole("STAFF")：商家不发货，那它的子订单就永远
                // 停在 NONE，自动收货与退款分流都跟着一起卡住。
                .requestMatchers(HttpMethod.POST, "/api/orders/subs/*/ship").hasAnyRole("STAFF", "MERCHANT")
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
            // 单账号单会话：同账号第二个登录顶掉第一个。
            // 商家账号不该被多人同时使用——出了问题连「谁操作的」都查不清。
            // 被顶掉的会话给 401 + JSON，与未登录入口同一口径（默认是 200 + 一句英文文本，
            // 对 API 调用方等于把「掉线」伪装成「成功」）。
            .sessionManagement(s -> s.maximumSessions(1)
                    .sessionRegistry(sessionRegistry())
                    .expiredSessionStrategy(event -> {
                        var res = event.getResponse();
                        res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write("{\"code\":\"SESSION_EXPIRED\",\"message\":\"账号已在别处登录，当前会话已失效\"}");
                    }))
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
                }))
        // 保留默认 CSRF 防护，页面把 token 带入每个修改请求。
        // 但纯 API（/api/**）的 CSRF 关掉：前端是 fetch + JSON，不是表单提交，
        // 让它带 CSRF token 反而增加复杂度。防 CSRF 的职责转交给 SameSite=Lax 的会话 Cookie
        // （见 application.yml）——Lax 会挡住跨站 POST，那正是 CSRF 攻击的形态；
        // 而用户从邮件/聊天里点链接进来（顶层 GET 导航）仍能带 Cookie，不破坏体验。
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        return http.build();
    }
}
