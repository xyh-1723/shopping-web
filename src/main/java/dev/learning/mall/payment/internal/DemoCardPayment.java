package dev.learning.mall.payment.internal;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.payment.api.PayRequest;
import dev.learning.mall.payment.api.PaymentGateway;
import dev.learning.mall.payment.api.RefundRequest;
import org.springframework.stereotype.Component;

/**
 * 外部卡通道：只跟签名，不动钱包。
 *
 * <p><b>它保留下来不是因为「删起来麻烦」</b>（钱包方案 §6.4）：
 * 它有真实消费者（页面支付方式下拉、助手 HITL 卡片文案、助手工具的示例），
 * 删掉它，{@code PaymentRouter} 的「装进 Map 按名字取」就退化成语焉不详的单例注入。
 *
 * <p><b>它碰钱包这件事是被一条测试钉死的</b>：
 * {@code WalletPaymentTest#cardPaymentWritesNoWalletRows}。
 * 保留一个无害的邻居，代价是必须持续证明它无害——这句话光靠注释不成立。
 *
 * <p><b>为什么 {@link #refund} 直接抛异常，而不是返回一张模拟凭证</b>：
 * 这笔钱压根没进过钱包，退款到这里<b>没有可以是真的地方</b>。
 * 返回 {@code "SIM-REFUND-..."} 会让调用方以为钱已经退回去了，
 * 而唯一的真相（余额）纹丝不动——那正是本项目一直在消灭的「假接口」：
 * 它不报错、不失败，只在对账时才被人发现存在成百上千张从来没有对应资金的凭证。
 * 宁可让这条路在第一次被调用时就红，也不要让它安静地通过。
 */
@Component
public class DemoCardPayment implements PaymentGateway {

    @Override
    public String method() {
        return "DEMO_CARD";
    }

    /**
     * 收款：仍然是本地无副作用的模拟（不连银行、不动钱包）。行为与改造前一致。
     *
     * <p>刻意<b>不</b>在这里校验金额：银行卡这条路径本来就不处理「够不够」这个问题，
     * 加一句校验只会让人误以为这里有一层余额保障。
     */
    @Override
    public String pay(PayRequest request) {
        return "SIM-CARD-" + request.orderId();
    }

    /**
     * 放款：<b>不支持，且不支持得很大声</b>。
     *
     * <p>{@code 400 REFUND_UNSUPPORTED} 而不是凭空返回凭证的理由见类注释。
     * 真要走这条路（把越过站内钱包的钱退回银行卡），那是一个新的支付域概念
     * （退款单 + 渠道回调），不是一个返回值能假装出来的。
     *
     * @throws BusinessException 总是抛出 {@code REFUND_UNSUPPORTED}（400）
     */
    @Override
    public String refund(RefundRequest request) {
        throw BusinessException.badRequest("REFUND_UNSUPPORTED",
                "支付方式 DEMO_CARD 不支持退款到钱包：这笔钱从未进入站内钱包，没有可以退的落点。"
                        + "（订单 " + request.orderId() + "）");
    }
}
