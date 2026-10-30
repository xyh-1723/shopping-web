package dev.learning.mall.coupon.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建券模板的请求。
 *
 * <p>{@code ownerType} 对商家账号可以留空：服务端会强制成 {@code MERCHANT}，
 * 店铺号也由登录账号推出，请求里没有可篡改的店铺号。
 */
public record CouponCreateRequest(
        @NotBlank(message = "请填写券码")
        @Pattern(regexp = "[A-Z0-9_-]{3,32}", message = "券码须为 3 至 32 位大写字母、数字、下划线或横线")
        String code,

        @NotBlank(message = "请填写券名")
        @Size(max = 100, message = "券名不能超过 100 字")
        String name,

        @Pattern(regexp = "PLATFORM|MERCHANT", message = "券归属只能是 PLATFORM 或 MERCHANT")
        String ownerType,

        Long merchantId,

        @NotBlank(message = "请选择券类型")
        @Pattern(regexp = "AMOUNT|PERCENT", message = "券类型只能是 AMOUNT 或 PERCENT")
        String discountType,

        @NotNull(message = "请填写券面额或折扣")
        @DecimalMin(value = "0.01", message = "券面额必须大于 0")
        BigDecimal discountValue,

        @DecimalMin(value = "0.01", message = "折扣封顶必须大于 0")
        BigDecimal maxDiscount,

        @NotNull(message = "请填写使用门槛")
        @DecimalMin(value = "0.00", message = "使用门槛不能为负")
        BigDecimal minSpend,

        @Min(value = 0, message = "平台共担比例不能为负")
        @Max(value = 10000, message = "平台共担比例不能超过 10000")
        Integer platformShareBps,

        @Min(value = 1, message = "发行量至少为 1")
        @Max(value = 1_000_000, message = "发行量不能超过 100 万")
        int totalQuantity,

        @Min(value = 1, message = "每人限领至少为 1")
        @Max(value = 100, message = "每人限领不能超过 100")
        int perUserLimit,

        @Min(value = 0, message = "平台预算不能为负")
        Long platformBudgetCents,

        @NotNull(message = "请填写开始时间")
        LocalDateTime startAt,

        @NotNull(message = "请填写结束时间")
        LocalDateTime endAt) {}
