package dev.learning.mall.coupon.internal;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponApplication;
import dev.learning.mall.coupon.api.CouponCatalog;
import dev.learning.mall.coupon.api.CouponCreateRequest;
import dev.learning.mall.coupon.api.CouponPreview;
import dev.learning.mall.coupon.api.CouponRedemptions;
import dev.learning.mall.coupon.api.CouponShop;
import dev.learning.mall.coupon.api.CouponView;
import dev.learning.mall.coupon.api.UserCouponView;
import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import dev.learning.mall.pricing.api.MoneyUnits;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 券的生命周期：领 → 锁 → 核销 / 释放，以及券模板的创建与查询。
 *
 * <p><b>事务边界是设计的一部分，不是实现细节：</b>
 * <ul>
 *   <li>{@link #claim} 在一个事务里「锁模板行 → 数已领 → 加发行量 → 插入实例」，
 *       任何一步失败都整体回滚，不会出现「计数加了但券没发出去」；</li>
 *   <li>{@link #reserve} 由订单模块在<b>下单事务里</b>调用：算券、占预算、锁券三件事
 *       要么都成功，要么都不发生。</li>
 * </ul>
 *
 * <p><b>为什么读路径也要干写操作？</b>{@link #mine} 会先把「已过期但仍是 UNUSED」的券
 * 改成 EXPIRED。这不是为了写得开心，而是为了防止它们被 {@code lockForOrder} 的
 * {@code expires_at > now} 条件悄悄挡掉之后，用户看到一张「有效」的券却永远用不了。
 * 状态必须在用户能看见的地方与事实一致。
 */
@Service
class CouponService implements CouponCatalog, CouponRedemptions {

    private static final String PLATFORM = "PLATFORM";
    private static final String MERCHANT = "MERCHANT";
    private static final String ACTIVE = "ACTIVE";
    private static final String UNUSED = "UNUSED";

    private final CouponRepository repository;
    private final CouponProperties properties;
    private final MerchantDirectory merchants;
    private final Clock clock;

    CouponService(CouponRepository repository, CouponProperties properties,
                  MerchantDirectory merchants, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.merchants = merchants;
        this.clock = clock;
    }

    // ————————————————————— 领券与查询 —————————————————————

    @Override
    public List<CouponView> available(String username) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Integer> claimed = new HashMap<>();
        for (CouponRepository.ClaimCount count : repository.claimedCounts(username)) {
            claimed.put(count.couponId(), count.n());
        }
        return repository.available(now).stream()
                .map(coupon -> toView(coupon, claimed.getOrDefault(coupon.id(), 0) > 0))
                .toList();
    }

    @Override
    public List<UserCouponView> mine(String username) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.expireLapsed(username, now);
        return repository.mine(username).stream().map(CouponService::toView).toList();
    }

    @Override
    @Transactional
    public UserCouponView claim(String username, String couponId) {
        ensureEnabled();
        LocalDateTime now = LocalDateTime.now(clock);
        CouponRow coupon = repository.lock(couponId)
                .orElseThrow(() -> BusinessException.notFound("券不存在：" + couponId));

        if (!ACTIVE.equals(coupon.status()) || now.isBefore(coupon.startAt()) || !now.isBefore(coupon.endAt())) {
            throw BusinessException.conflict("COUPON_UNAVAILABLE", "这张券当前不可领取");
        }
        int claimed = repository.countClaimed(couponId, username);
        if (claimed >= coupon.perUserLimit()) {
            throw BusinessException.conflict("COUPON_LIMIT_REACHED", "每人最多领取 " + coupon.perUserLimit() + " 张");
        }
        if (repository.increaseIssued(couponId) == 0) {
            throw BusinessException.conflict("COUPON_SOLD_OUT", "这张券已被领完");
        }

        String id = UUID.randomUUID().toString();
        try {
            repository.insertUserCoupon(id, couponId, username, claimed + 1, coupon.endAt());
        } catch (DuplicateKeyException race) {
            // 模板行已锁，正常走不到这里；唯一键是最后一道保险，不能让它以 500 的形式冒出去。
            throw BusinessException.conflict("COUPON_LIMIT_REACHED", "每人最多领取 " + coupon.perUserLimit() + " 张");
        }
        return repository.findUserCoupon(username, id)
                .map(CouponService::toView)
                .orElseThrow(() -> new IllegalStateException("券刚领到就查不到了：" + id));
    }

    @Override
    @Transactional
    public CouponView create(String actor, boolean merchantActor, CouponCreateRequest request) {
        String ownerType;
        Long merchantId;
        if (merchantActor) {
            if (request.ownerType() != null && !MERCHANT.equals(request.ownerType())) {
                throw new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "COUPON_NOT_OWNED", "商家只能创建本店券");
            }
            MerchantView shop = merchants.requireShopOfOwner(actor);
            ownerType = MERCHANT;
            merchantId = shop.id();
        } else {
            ownerType = request.ownerType() == null ? PLATFORM : request.ownerType();
            merchantId = request.merchantId();
            if (MERCHANT.equals(ownerType)) {
                if (merchantId == null) {
                    throw BusinessException.badRequest("COUPON_OWNER_INVALID", "商家券必须指定店铺号");
                }
                boolean active = merchants.byId(merchantId).map(MerchantView::active).orElse(false);
                if (!active) {
                    throw BusinessException.badRequest("COUPON_OWNER_INVALID", "店铺不存在或已停用");
                }
            } else if (!PLATFORM.equals(ownerType)) {
                throw BusinessException.badRequest("COUPON_OWNER_INVALID", "券归属只能是 PLATFORM 或 MERCHANT");
            } else {
                merchantId = null;
            }
        }

        long amountCents = 0;
        long percentBps = 0;
        Long maxDiscountCents = null;
        try {
            if ("AMOUNT".equals(request.discountType())) {
                amountCents = MoneyUnits.toCents(request.discountValue());
            } else if ("PERCENT".equals(request.discountType())) {
                percentBps = MoneyUnits.percentToBps(request.discountValue());
                maxDiscountCents = request.maxDiscount() == null ? null : MoneyUnits.toCents(request.maxDiscount());
            } else {
                throw BusinessException.badRequest("COUPON_TYPE_INVALID", "券类型只能是 AMOUNT 或 PERCENT");
            }
        } catch (IllegalArgumentException invalid) {
            throw BusinessException.badRequest("COUPON_VALUE_INVALID", invalid.getMessage());
        }
        if (amountCents <= 0 && percentBps <= 0) {
            throw BusinessException.badRequest("COUPON_VALUE_INVALID", "券面额必须大于 0");
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw BusinessException.badRequest("COUPON_WINDOW_INVALID", "结束时间必须晚于开始时间");
        }

        int shareBps = PLATFORM.equals(ownerType)
                ? (request.platformShareBps() == null ? 6000 : request.platformShareBps())
                : 0;
        if (shareBps < 0 || shareBps > 10000) {
            throw BusinessException.badRequest("COUPON_SHARE_INVALID", "平台共担比例必须在 0 到 10000 之间");
        }
        long budgetCents = request.platformBudgetCents() == null ? 0 : request.platformBudgetCents();
        if (budgetCents < 0) {
            throw BusinessException.badRequest("COUPON_BUDGET_INVALID", "平台预算不能为负");
        }

        String id = UUID.randomUUID().toString();
        try {
            repository.insert(id, request.code(), request.name(), ownerType, merchantId,
                    request.discountType(), request.discountValue(), request.maxDiscount(),
                    request.minSpend(), shareBps, request.totalQuantity(), request.perUserLimit(),
                    budgetCents, request.startAt(), request.endAt());
        } catch (DuplicateKeyException taken) {
            throw BusinessException.conflict("COUPON_CODE_TAKEN", "券码已被使用：" + request.code());
        }
        return repository.find(id).map(coupon -> toView(coupon, false))
                .orElseThrow(() -> new IllegalStateException("券刚创建就查不到了：" + id));
    }

    @Override
    public CouponPreview preview(String username, String userCouponId, List<CouponShop> shops) {
        ensureEnabled();
        LocalDateTime now = LocalDateTime.now(clock);
        UserCouponDetail userCoupon = repository.findUserCoupon(username, userCouponId)
                .orElseThrow(() -> BusinessException.notFound("券不存在或不属于当前账号：" + userCouponId));
        ensureUsable(userCoupon, now);

        CouponApplication application = CouponPricing.apply(userCoupon.id(), userCoupon.couponId(),
                userCoupon.code(), userCoupon.name(), termsOf(userCoupon), basesOf(shops), properties);
        return new CouponPreview(application.userCouponId(), application.couponCode(), application.couponName(),
                application.discount(), application.merchantDiscount(), application.platformSubsidy(),
                application.shops().stream()
                        .map(shop -> new CouponPreview.ShopPreview(shop.merchantId(), shop.couponDiscount(),
                                shop.merchantDiscount(), shop.platformSubsidy(), shop.platformFee(),
                                shop.merchantIncome(), shop.platformIncome()))
                        .toList());
    }

    // ————————————————————— 订单侧：锁定 / 核销 / 释放 —————————————————————

    @Override
    @Transactional
    public CouponApplication reserve(String username, String userCouponId, String orderId,
                                     List<CouponShop> shops) {
        if (userCouponId == null || userCouponId.isBlank()) {
            return CouponPricing.settle(basesOf(shops), properties);
        }
        ensureEnabled();
        LocalDateTime now = LocalDateTime.now(clock);
        UserCouponDetail userCoupon = repository.lockUserCoupon(username, userCouponId)
                .orElseThrow(() -> BusinessException.notFound("券不存在或不属于当前账号：" + userCouponId));
        ensureUsable(userCoupon, now);

        CouponApplication application = CouponPricing.apply(userCoupon.id(), userCoupon.couponId(),
                userCoupon.code(), userCoupon.name(), termsOf(userCoupon), basesOf(shops), properties);

        long subsidyCents = MoneyUnits.toCents(application.platformSubsidy());
        if (repository.reserveBudget(userCoupon.couponId(), subsidyCents) == 0) {
            throw BusinessException.conflict("COUPON_BUDGET_EXHAUSTED", "这张券的平台补贴预算已用完");
        }
        int locked = repository.lockForOrder(userCoupon.id(), orderId, application.discount(),
                application.merchantDiscount(), application.platformSubsidy(), now);
        if (locked == 0) {
            throw BusinessException.conflict("COUPON_UNAVAILABLE", "这张券已被其他订单占用或已过期");
        }
        return application;
    }

    @Override
    @Transactional
    public void commit(String orderId) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.findLockedByOrder(orderId).ifPresent(locked ->
                repository.markUsed(locked.id(), orderId, now));
    }

    @Override
    @Transactional
    public void release(String orderId) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.findLockedByOrder(orderId).ifPresent(locked -> {
            if (repository.releaseLocked(locked.id(), now) == 0) {
                // 并发的支付/关单已经先动过这张券；此时不能再退预算，否则会重复退。
                return;
            }
            long subsidyCents = MoneyUnits.toCents(locked.platformSubsidy());
            if (repository.refundBudget(locked.couponId(), subsidyCents) == 0) {
                throw new IllegalStateException("券预算回退失败：" + locked.couponId() + " / " + subsidyCents);
            }
        });
    }

    @Override
    public CouponApplication settle(List<CouponShop> shops) {
        return CouponPricing.settle(basesOf(shops), properties);
    }

    // ————————————————————— 映射与校验 —————————————————————

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw BusinessException.conflict("COUPON_DISABLED", "优惠券功能当前已关闭");
        }
    }

    private static void ensureUsable(UserCouponDetail userCoupon, LocalDateTime now) {
        if (!ACTIVE.equals(userCoupon.couponStatus())
                || now.isBefore(userCoupon.startAt()) || !now.isBefore(userCoupon.endAt())) {
            throw BusinessException.conflict("COUPON_UNAVAILABLE", "这张券当前不可用");
        }
        if (!UNUSED.equals(userCoupon.status()) || !now.isBefore(userCoupon.expiresAt())) {
            String reason = switch (userCoupon.status()) {
                case "LOCKED" -> "这张券已被一笔未支付的订单占用";
                case "USED" -> "这张券已经使用过";
                case "EXPIRED" -> "这张券已过期";
                default -> "这张券当前不可用";
            };
            throw BusinessException.conflict("COUPON_UNAVAILABLE", reason);
        }
    }

    private static List<CouponPricing.ShopBase> basesOf(List<CouponShop> shops) {
        Set<Long> seen = new HashSet<>();
        return shops.stream()
                .map(shop -> {
                    if (!seen.add(shop.merchantId())) {
                        throw new IllegalArgumentException("券结算出现重复店铺：" + shop.merchantId());
                    }
                    return new CouponPricing.ShopBase(shop.merchantId(),
                            MoneyUnits.toCents(shop.subtotal()),
                            MoneyUnits.toCents(shop.promotionDiscount()));
                })
                .toList();
    }

    private static CouponPricing.Terms termsOf(UserCouponDetail userCoupon) {
        CouponPricing.DiscountType type = "PERCENT".equals(userCoupon.discountType())
                ? CouponPricing.DiscountType.PERCENT
                : CouponPricing.DiscountType.AMOUNT;
        long amountCents = type == CouponPricing.DiscountType.AMOUNT
                ? MoneyUnits.toCents(userCoupon.discountValue()) : 0;
        long percentBps = type == CouponPricing.DiscountType.PERCENT
                ? MoneyUnits.percentToBps(userCoupon.discountValue()) : 0;
        Long maxDiscountCents = userCoupon.maxDiscount() == null
                ? null : MoneyUnits.toCents(userCoupon.maxDiscount());
        return new CouponPricing.Terms(userCoupon.ownerType(), userCoupon.merchantId(), type,
                amountCents, percentBps, maxDiscountCents,
                MoneyUnits.toCents(userCoupon.minSpend()), userCoupon.platformShareBps());
    }

    private static CouponView toView(CouponRow coupon, boolean claimedByMe) {
        return new CouponView(coupon.id(), coupon.code(), coupon.name(), coupon.ownerType(),
                coupon.merchantId(), coupon.merchantName(), coupon.discountType(), coupon.discountValue(),
                coupon.maxDiscount(), coupon.minSpend(), coupon.platformShareBps(),
                coupon.totalQuantity(), coupon.issuedCount(),
                Math.max(0, coupon.totalQuantity() - coupon.issuedCount()), coupon.perUserLimit(),
                coupon.startAt(), coupon.endAt(), coupon.status(), claimedByMe);
    }

    private static UserCouponView toView(UserCouponDetail userCoupon) {
        return new UserCouponView(userCoupon.id(), userCoupon.couponId(), userCoupon.code(),
                userCoupon.name(), userCoupon.ownerType(), userCoupon.merchantId(),
                userCoupon.merchantName(), userCoupon.discountType(), userCoupon.discountValue(),
                userCoupon.maxDiscount(), userCoupon.minSpend(), userCoupon.status(),
                userCoupon.claimedAt(), userCoupon.expiresAt(), userCoupon.usedOrderId());
    }
}
