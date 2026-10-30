package dev.learning.mall.config;

import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Component;

/**
 * 启动时把账号同步进 {@code users}/{@code authorities} 表。
 *
 * <p><b>幂等</b>：只在账号不存在时创建，已存在的不动——所以改密码（阶段 A4）
 * UPDATE 出来的新哈希不会被这里覆盖，重启也不会把密码重置回默认值。
 *
 * <p><b>同步范围</b>：
 * <ul>
 *   <li>演示账号：{@code student}、{@code classmate}、{@code admin}（店员），
 *       密码用 {@code mall.demo-password}。</li>
 *   <li>商家账号：遍历 {@code merchant} 表，每个 {@code owner_username} 建一个账号，
 *       密码用 {@code mall.merchant-password}，角色只有 MERCHANT（不带 STAFF）。</li>
 * </ul>
 *
 * <p><b>同一份口令只做一次 BCrypt</b>：200 个商家共用同一个配置值，
 * 编码一次后复用哈希串，省掉 199 次无意义的 60-100ms 计算。
 */
@Component
public class UserAccountProvisioner implements ApplicationRunner {

    private final UserDetailsManager users;
    private final MerchantDirectory merchants;
    private final String demoHash;
    private final String merchantHash;

    public UserAccountProvisioner(UserDetailsManager users, MerchantDirectory merchants,
                                  org.springframework.security.crypto.password.PasswordEncoder encoder,
                                  @Value("${mall.demo-password}") String demoPassword,
                                  @Value("${mall.merchant-password}") String merchantPassword) {
        this.users = users;
        this.merchants = merchants;
        this.demoHash = encoder.encode(demoPassword);
        this.merchantHash = encoder.encode(merchantPassword);
    }

    @Override
    public void run(ApplicationArguments args) {
        createIfAbsent(User.withUsername("student").password(demoHash).roles("USER").build());
        createIfAbsent(User.withUsername("classmate").password(demoHash).roles("USER").build());
        // 店员账号：演示「同一助手、不同权限」。密码与演示账号共用一份。
        createIfAbsent(User.withUsername("admin").password(demoHash).roles("USER", "STAFF").build());

        // 商家账号：一家店一个，店主账号就是 merchant.owner_username。
        // 角色刻意只有 MERCHANT、不带 STAFF——它能做什么由这一个角色说了算，
        // 日常运营执行权（改价 / 改库存 / 发货）不下发给经营决策方。
        for (MerchantView shop : merchants.all()) {
            createIfAbsent(User.withUsername(shop.ownerUsername()).password(merchantHash)
                    .roles("USER", "MERCHANT").build());
        }
    }

    private void createIfAbsent(UserDetails account) {
        if (!users.userExists(account.getUsername())) {
            users.createUser(account);
        }
    }
}
