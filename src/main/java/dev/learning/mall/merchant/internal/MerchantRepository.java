package dev.learning.mall.merchant.internal;

import dev.learning.mall.merchant.api.MerchantView;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code merchant} 表的唯一通道，包可见。
 *
 * <p><b>只读</b>：与 {@link dev.learning.mall.merchant.api.MerchantDirectory} 同一条理由——
 * 目前没有任何开店 / 关店的真实入口，写方法先写出来就是空承诺。
 */
@Repository
class MerchantRepository {

    private static final String SELECT = "SELECT id, code, name, owner_username, status FROM merchant ";

    private final JdbcClient jdbc;

    MerchantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<MerchantView> all() {
        return jdbc.sql(SELECT + "ORDER BY id").query(MerchantView.class).list();
    }

    Optional<MerchantView> byId(long id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(MerchantView.class).optional();
    }

    /** 店主账号 → 店。查不到返回空，绝不返回「默认那家店」。 */
    Optional<MerchantView> ofOwner(String username) {
        return jdbc.sql(SELECT + "WHERE owner_username = :u")
                .param("u", username).query(MerchantView.class).optional();
    }
}
