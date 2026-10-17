// 可选浏览器验收。先启动商城，再 npm install 和 npm run test:browser。
const { chromium, expect } = require(
  process.env.PLAYWRIGHT_MODULE || "@playwright/test",
);
const fs = require("node:fs");
const path = require("node:path");
const base = process.env.MALL_URL || "http://localhost:8080";
const output = path.resolve(__dirname, "../artifacts");
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.MALL_BROWSER_CHANNEL
      ? { channel: process.env.MALL_BROWSER_CHANNEL }
      : {}),
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1050 },
    deviceScaleFactor: 1,
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  try {
    await page.goto(base);
    await expect(page.locator(".product-card")).toHaveCount(6);
    await expect(page.locator("#checkout")).toBeDisabled();
    await page.screenshot({
      path: path.join(output, "商城首页.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "灵感文具", exact: true }).click();
    await expect(page.locator(".product-card")).toHaveCount(2);
    await page.getByRole("button", { name: "全部好物", exact: true }).click();
    await page.getByRole("searchbox").fill("马克杯");
    await expect(page.locator(".product-card")).toHaveCount(1);
    await page.getByRole("searchbox").clear();
    await page
      .getByRole("button", { name: "将云朵马克杯加入购物车", exact: true })
      .click();
    await page.locator("#checkout").click();
    await expect(page).toHaveURL(/login/);
    await page.getByLabel("密码", { exact: true }).fill("learn12345");
    await page.getByRole("button", { name: "登录商城" }).click();
    await expect(page.locator("#cart-count")).toHaveText("1");
    const beforeStock = await page.request
      .get(base + "/api/products/1")
      .then((r) => r.json());
    const beforeOrders = await page.request
      .get(base + "/api/orders")
      .then((r) => r.json());
    await page.locator("#checkout").click();
    await expect(page.locator("#orders-view")).toBeVisible();
    await expect(page.locator(".order-card")).toHaveCount(
      beforeOrders.length + 1,
    );
    await page.locator("#replay").click();
    await expect(page.locator("#toast")).toContainText("幂等生效");
    await expect(page.locator(".order-card")).toHaveCount(
      beforeOrders.length + 1,
    );
    const afterStock = await page.request
      .get(base + "/api/products/1")
      .then((r) => r.json());
    expect(afterStock.stock).toBe(beforeStock.stock - 1);
    await page
      .locator(".order-card")
      .first()
      .getByRole("button", { name: "模拟支付", exact: true })
      .click();
    await expect(
      page.locator(".order-card").first().locator(".badge"),
    ).toHaveText("已支付");
    await page.screenshot({
      path: path.join(output, "订单页面.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "消息中心", exact: true }).click();
    await expect(page.locator("#notifications .notice")).not.toHaveCount(0, {
      timeout: 15000,
    });
    await expect(page.locator("#events .badge.DELIVERED").first()).toBeVisible({
      timeout: 15000,
    });
    await page.screenshot({
      path: path.join(output, "消息中心.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "逛逛商品", exact: true }).click();
    await page
      .getByRole("button", { name: "将灵感笔记本加入购物车", exact: true })
      .click();
    const beforeCancel = await page.request
      .get(base + "/api/products/2")
      .then((r) => r.json());
    await page.locator("#checkout").click();
    await page
      .locator(".order-card")
      .first()
      .getByRole("button", { name: "取消订单", exact: true })
      .click();
    await expect(
      page.locator(".order-card").first().locator(".badge"),
    ).toHaveText("已取消");
    expect(
      (await page.request.get(base + "/api/products/2").then((r) => r.json()))
        .stock,
    ).toBe(beforeCancel.stock);
    await page.getByRole("link", { name: "学习地图" }).click();
    await expect(page.locator(".learning-grid article")).toHaveCount(6);
    await page.goto(base);
    await page.getByRole("button", { name: "退出", exact: true }).click();
    await expect(page.getByRole("link", { name: "登录体验" })).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base);
    await expect(page.locator(".product-card")).toHaveCount(6);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
    await page.screenshot({
      path: path.join(output, "手机商城.png"),
      fullPage: true,
    });
    expect(errors).toEqual([]);
    fs.writeFileSync(
      path.join(output, "browser-result.json"),
      JSON.stringify(
        {
          passed: true,
          browser: "Chromium",
          desktop: [1440, 1050],
          mobile: [390, 844],
          pageErrors: errors,
          checks: [
            "catalog",
            "filter",
            "search",
            "guest-cart-login",
            "checkout",
            "replay",
            "pay",
            "notification",
            "cancel-stock",
            "learning-map",
            "logout",
            "mobile-overflow",
          ],
        },
        null,
        2,
      ),
    );
    console.log(
      "Browser flow passed: catalog, login, checkout, replay, pay, notifications, cancellation, mobile.",
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
