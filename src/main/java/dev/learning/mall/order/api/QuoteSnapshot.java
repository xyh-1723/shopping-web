package dev.learning.mall.order.api;

import dev.learning.mall.pricing.api.Pricer;
import java.util.List;
import java.util.Optional;

/**
 * 下单金额快照端口。
 *
 * <p>调用方只提交商品与数量，价格始终由服务端读取商品表并交给唯一的
 * {@link Pricer} 计算，避免客户端价格进入可信链路。
 */
public interface QuoteSnapshot {

    /** 为这批行拍一张金额快照，不接受调用方传入价格。 */
    Snapshot capture(String username, List<CreateOrderRequest.Line> lines);

    /**
     * 取出本人未过期的快照。
     *
     * <p>他人的、过期的、不存在的快照都返回空；快照是用户保护，不是下单权限边界。
     */
    Optional<Snapshot> find(String id, String username);

    /** 一张由服务端计算并持久化的金额快照。 */
    record Snapshot(String id, List<CreateOrderRequest.Line> lines, Pricer.Quote quote) {}
}
