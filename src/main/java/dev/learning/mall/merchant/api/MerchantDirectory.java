package dev.learning.mall.merchant.api;

import java.util.List;
import java.util.Optional;

/**
 * 「店」这个维度的唯一查询入口。
 *
 * <p>它解决的是一件在加这张表之前根本问不出来的问题：<b>这个账号是哪一家店</b>。
 * 加表之前全系统只有一个 {@code merchant} 账号，它天然「拥有」所有商品与所有退款——
 * 那不是归属，那是一顶帽子。有了这个入口之后，退款队列与审批才第一次有可能按店收窄
 * （见 {@code RefundService}），「造 200 个商家账号」才不是「造 200 个越权账号」。
 *
 * <p><b>只读。</b>开店 / 关店目前没有任何真实入口（没有招商流程，也没有后台表单），
 * 所以这里刻意不提供 {@code create} / {@code suspend}：给一个没人调用的方法，
 * 等于宣称「支持开店」而实际上连一条能走通的路径都没有——
 * 空承诺与谎报能力是同一个毛病的两面。
 */
public interface MerchantDirectory {

    /** 全部店。启动时用它生成商家账号；运营侧看全站的场合也用它。 */
    List<MerchantView> all();

    /** 按 id 查。 */
    Optional<MerchantView> byId(long id);

    /**
     * 按店主账号查。<b>归属校验一律走这里</b>，不要拿店铺名或 code 去匹配——
     * 那两个字段是展示用的，不是身份。
     */
    Optional<MerchantView> ofOwner(String username);
}
