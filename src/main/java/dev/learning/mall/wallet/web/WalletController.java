package dev.learning.mall.wallet.web;

import dev.learning.mall.wallet.api.WalletAccounts;
import dev.learning.mall.wallet.api.WalletBalance;
import dev.learning.mall.wallet.api.WalletLedgerEntry;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家的钱包端点：余额 + 最近流水 + 演示充值。
 *
 * <p><b>存在的理由</b>：钱包里的钱在此之前只能从数据库里看到——页面上没有余额、也没有
 * 「这笔钱去哪了」的痕迹，于是「支付真的从钱包扣了款」这件事对用户完全不可见。
 * 一个用户看不见的余额，等于一个不存在的余额。
 *
 * <p><b>依赖只有 {@link WalletAccounts}</b>（模块公开边界）：控制器不认识
 * {@code WalletService}，更不认识任何 Repository。跨模块只依赖 {@code api} 包是硬规则，
 * 破了它，「钱包内部怎么排锁」就会渗到 Web 层。
 *
 * <p><b>用户名只取自 {@link Principal}</b>：请求体、查询参数、路径里都没有
 * 「查谁的钱包」这个输入，于是「查别人的余额」在接口层面没有表达方式。
 * 端点本身不需要角色——{@code SecurityConfig} 的 {@code anyRequest().authenticated()}
 * 已经把匿名挡在门外（未登录是 401，不是 500，见 {@code WalletControllerTest}）。
 *
 * <p><b>这里刻意不做金额换算</b>：金额一律以「分」从 {@link WalletAccounts} 出来，
 * {@code 元} 的形态由 {@code MoneyUnits} 在装配视图时产出（{@link WalletBalance} /
 * {@link WalletLedgerEntry} 各自带一份），页面只负责显示。
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    /**
     * 一次返回的流水条数。
     *
     * <p>不做成配置：它由「一个人一次想看多少条」这份产品判断决定，
     * 而到目前为止没有人提出要调它（做成配置 = 一个改了也不起作用的旋钮）。
     */
    private static final int LEDGER_LIMIT = 20;

    private final WalletAccounts accounts;

    public WalletController(WalletAccounts accounts) {
        this.accounts = accounts;
    }

    /**
     * 我的钱包：余额 + 最近 {@value #LEDGER_LIMIT} 条流水（新的在前）。
     *
     * <p>顺带完成懒开户：首次调用时会按配置授予初始额度并留一条 {@code OPENING} 流水，
     * 所以返回的 {@code balance} 一定有值——「查余额」本身就是一次触达。
     */
    @GetMapping
    public WalletView me(Principal principal) {
        return viewOf(principal.getName());
    }

    /**
     * 演示充值：固定档位、累计有上限，<b>不接受金额入参</b>。
     *
     * <p>金额不可自定义是刻意的：它能被自定义，就等于把「上限」当成倍率而不是天花板。
     *
     * <p>成功后把新的余额与流水一并回给调用方，页面<b>不需要再查一次</b>——
     * 「充值完立刻刷新」这种两步写法会有一个中间态，而那个中间态在网络抖动时
     * 会显示成「充了但没到账」。
     *
     * @throws dev.learning.mall.common.BusinessException
     *         {@code TOPUP_DISABLED}（总开关关闭）/ {@code TOPUP_LIMIT_EXCEEDED}（累计已达上限），
     *         两者都由统一异常处理器翻译成 409 + code，页面据此给中文提示
     */
    @PostMapping("/top-up")
    public WalletView topUp(Principal principal) {
        String username = principal.getName();
        accounts.topUp(username);
        return viewOf(username);
    }

    private WalletView viewOf(String username) {
        return new WalletView(accounts.balanceOf(username), accounts.recentLedger(username, LEDGER_LIMIT));
    }

    /**
     * 钱包页一屏所需的全部内容。
     *
     * <p>两个字段都来自同一次查询：余额是「现在有多少」，流水是「为什么变成这样」，
     * 缺了后者，余额就是一个凭空出现的数字（库存流水那一课在这里同样成立）。
     */
    public record WalletView(WalletBalance balance, List<WalletLedgerEntry> recent) {}
}
