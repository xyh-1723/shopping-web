package dev.learning.mall.merchant.internal;

import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * {@link MerchantDirectory} 的实现。
 *
 * <p>这里没有业务规则，只有一次转发——<b>这是刻意的</b>：店的语义（归属）很小，
 * 但它被 {@code SecurityConfig}（生成账号）与 {@code RefundService}（按店收窄）
 * 两处同时使用，两个模块各自写一条 SQL 就会长出两份「账号怎么对应到店」的真相，
 * 而那正是这次要堵的洞的成因（只不过洞从「一个账号拥有全部」变成「两处算法不一致」）。
 *
 * <p>不做缓存：200 家店的这张表只有 200 行，而 {@code all()} 只在启动时被调用，
 * {@code ofOwner} 走的是主键级唯一索引。在这里加一层本地缓存，
 * 换来的收益是零，付出的代价是「改了库里的数据，进程里还是旧的」。
 */
@Service
public class MerchantService implements MerchantDirectory {

    private final MerchantRepository merchants;

    public MerchantService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    public List<MerchantView> all() {
        return merchants.all();
    }

    @Override
    public Optional<MerchantView> byId(long id) {
        return merchants.byId(id);
    }

    @Override
    public Optional<MerchantView> ofOwner(String username) {
        return merchants.ofOwner(username);
    }
}
