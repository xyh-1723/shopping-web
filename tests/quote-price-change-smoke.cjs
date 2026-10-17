const { chromium, expect } = require(
  process.env.PLAYWRIGHT_MODULE || "@playwright/test",
);
const fs = require("node:fs");
const path = require("node:path");

const base = process.env.MALL_URL || "http://localhost:8099";
const browserChannel = process.env.MALL_BROWSER_CHANNEL || "msedge";
const output = path.resolve(__dirname, "../artifacts");
const resultPath = path.join(output, "t03-quote-price-change-result.json");

fs.mkdirSync(output, { recursive: true });

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function apiResponse(pathname, method = "POST") {
  return (response) => {
    const url = new URL(response.url());
    return (
      url.pathname === pathname && response.request().method() === method
    );
  };
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(browserChannel ? { channel: browserChannel } : {}),
  });
  const customer = await browser.newContext();
  const staff = await browser.newContext();
  const page = await customer.newPage();
  const staffPage = await staff.newPage();
  const results = [];
  const evidence = {};
  const pageErrors = [];
  let currentCheck = "1/2";

  page.on("pageerror", (error) => pageErrors.push(error.message));

  const record = (number, status, detail) => {
    const row = { number, status, evidence: detail };
    results.push(row);
    console.log(`${status} ${number} ${detail}`);
  };

  const login = async (target, username) => {
    await target.goto(base + "/login");
    await target.getByLabel("账号", { exact: true }).fill(username);
    await target
      .getByLabel("密码", { exact: true })
      .fill(process.env.MALL_PASSWORD || "learn12345");
    await target.getByRole("button", { name: "登录商城" }).click();
    await expect(target).toHaveURL(base + "/");
  };

  try {
    await login(page, "student");
    await login(staffPage, "admin");
    // 多次重跑同一实例时购物袋会累积，先清空，保证「数量 1→2」这条断言的前提成立。
    await page.evaluate(() => api("/api/cart", { method: "DELETE" }).catch(() => {}));

    const initialOrders = await page.request
      .get(base + "/api/orders")
      .then((response) => response.json());
    evidence.initialOrderCount = initialOrders.length;

    const firstQuotePromise = page.waitForResponse(
      apiResponse("/api/quotes"),
      { timeout: 10000 },
    );
    await page
      .getByRole("button", {
        name: "将云朵马克杯加入购物车",
        exact: true,
      })
      .click();
    const firstQuoteResponse = await firstQuotePromise;
    const firstQuote = await firstQuoteResponse.json();
    const firstQuoteRequest = firstQuoteResponse.request().postDataJSON();
    const displayedAmount = await page.locator("#cart-total").textContent();
    const expectedAmount = `¥${Number(firstQuote.payAmount).toFixed(2)}`;
    const firstCurrentQuoteId = await page.evaluate(() => currentQuoteId);

    assert(
      displayedAmount === expectedAmount,
      `页面金额 ${displayedAmount} != 快照金额 ${expectedAmount}`,
    );
    record(
      1,
      "PASS",
      `页面 #cart-total=${displayedAmount}，POST /api/quotes payAmount=${firstQuote.payAmount}`,
    );

    assert(firstQuoteResponse.status() === 200, `快照状态=${firstQuoteResponse.status()}`);
    assert(firstQuote.id, "快照响应没有 id");
    assert(
      firstCurrentQuoteId === firstQuote.id,
      `页面 currentQuoteId=${firstCurrentQuoteId}，响应 id=${firstQuote.id}`,
    );
    record(
      2,
      "PASS",
      `点击加购后立即 POST /api/quotes，id=${firstQuote.id}，items=${JSON.stringify(firstQuoteRequest.items)}`,
    );
    evidence.firstQuote = firstQuote;
    evidence.firstCurrentQuoteId = firstCurrentQuoteId;

    currentCheck = "3";
    const productBefore = await page.request
      .get(base + "/api/products/1")
      .then((response) => response.json());
    const changedPrice = (Number(productBefore.price) + 1).toFixed(2);
    const staffChange = await staffPage.evaluate(
      async ({ productId, price }) => {
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
        const csrfToken = document.querySelector('meta[name="_csrf"]').content;
        const response = await fetch(`/api/products/${productId}/price`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            [csrfHeader]: csrfToken,
          },
          body: JSON.stringify({ price }),
        });
        return { status: response.status, body: await response.text() };
      },
      { productId: 1, price: changedPrice },
    );
    assert(staffChange.status === 200, `店员改价失败：${JSON.stringify(staffChange)}`);

    const failedOrderPromise = page.waitForResponse(
      apiResponse("/api/orders"),
      { timeout: 10000 },
    );
    await page.locator("#checkout").click();
    const failedOrderResponse = await failedOrderPromise;
    const failedOrderBody = await failedOrderResponse.json();
    const failedOrderRequest = failedOrderResponse.request().postDataJSON();
    await expect(page.locator("#toast")).toContainText("商品价格已变动");
    const toastText = await page.locator("#toast").textContent();
    const ordersAfterReject = await page.request
      .get(base + "/api/orders")
      .then((response) => response.json());

    assert(
      failedOrderRequest.quoteId === firstQuote.id,
      `下单 quoteId=${failedOrderRequest.quoteId}，快照 id=${firstQuote.id}`,
    );
    assert(
      failedOrderResponse.status() === 409,
      `预期 409，实际 ${failedOrderResponse.status()} ${JSON.stringify(failedOrderBody)}`,
    );
    assert(
      failedOrderBody.code === "PRICE_CHANGED",
      `预期 PRICE_CHANGED，实际 ${JSON.stringify(failedOrderBody)}`,
    );
    assert(
      ordersAfterReject.length === initialOrders.length,
      `拒绝前订单=${initialOrders.length}，拒绝后订单=${ordersAfterReject.length}`,
    );
    await page.screenshot({
      path: path.join(output, "t03-price-changed-toast.png"),
      fullPage: true,
    });
    record(
      3,
      "PASS",
      `POST /api/orders 携带 quoteId=${failedOrderRequest.quoteId}；HTTP 409 ${JSON.stringify(failedOrderBody)}；页面提示“${toastText}”；订单数 ${initialOrders.length}→${ordersAfterReject.length}`,
    );
    evidence.priceChange = {
      oldPrice: productBefore.price,
      newPrice: changedPrice,
      staffChange,
      request: failedOrderRequest,
      responseStatus: failedOrderResponse.status(),
      responseBody: failedOrderBody,
      toastText,
      orderCountBefore: initialOrders.length,
      orderCountAfter: ordersAfterReject.length,
    };

    currentCheck = "4";
    const secondQuotePromise = page.waitForResponse(
      apiResponse("/api/quotes"),
      { timeout: 10000 },
    );
    const quantityBefore = await page.evaluate(() =>
      cartView.items.find((item) => item.productId === 1)?.quantity,
    );
    await page
      .getByRole("button", {
        name: "增加云朵马克杯数量",
        exact: true,
      })
      .click();
    const secondQuoteResponse = await secondQuotePromise;
    const secondQuote = await secondQuoteResponse.json();
    const secondQuoteRequest = secondQuoteResponse.request().postDataJSON();
    const secondCurrentQuoteId = await page.evaluate(() => currentQuoteId);

    assert(secondQuote.id !== firstQuote.id, `重拍 id 仍为 ${secondQuote.id}`);
    assert(
      secondCurrentQuoteId === secondQuote.id,
      `页面 currentQuoteId=${secondCurrentQuoteId}，新响应 id=${secondQuote.id}`,
    );
    const expectedQuantity = Number(quantityBefore) + 1;
    assert(
      Number(secondQuoteRequest.items?.[0]?.quantity) === expectedQuantity,
      `新快照未包含数量 ${expectedQuantity}：${JSON.stringify(secondQuoteRequest)}`,
    );
    record(
      4,
      "PASS",
      `数量 ${quantityBefore}→${expectedQuantity} 后重拍：${firstQuote.id}→${secondQuote.id}，页面 currentQuoteId=${secondCurrentQuoteId}`,
    );
    evidence.secondQuote = secondQuote;
    evidence.secondCurrentQuoteId = secondCurrentQuoteId;

    currentCheck = "5";
    const ttlWaitMs = Number(process.env.MALL_QUOTE_WAIT_MS || 62000);
    console.log(`INFO 5 等待 ${ttlWaitMs}ms 使快照 ${secondQuote.id} 过期（expiresAt=${secondQuote.expiresAt}）`);
    await page.waitForTimeout(ttlWaitMs);

    const expiredOrderPromise = page.waitForResponse(
      apiResponse("/api/orders"),
      { timeout: 10000 },
    );
    await page.locator("#checkout").click();
    const expiredOrderResponse = await expiredOrderPromise;
    const expiredOrderBody = await expiredOrderResponse.json();
    const expiredOrderRequest = expiredOrderResponse.request().postDataJSON();

    assert(
      expiredOrderRequest.quoteId === secondQuote.id,
      `过期测试提交 quoteId=${expiredOrderRequest.quoteId}，预期 ${secondQuote.id}`,
    );
    assert(
      expiredOrderResponse.status() === 200,
      `过期快照应放行，实际 ${expiredOrderResponse.status()} ${JSON.stringify(expiredOrderBody)}`,
    );
    await expect(page.locator("#orders-view")).toBeVisible();
    const ordersAfterExpired = await page.request
      .get(base + "/api/orders")
      .then((response) => response.json());
    assert(
      ordersAfterExpired.length === initialOrders.length + 1,
      `过期下单前订单=${initialOrders.length}，下单后订单=${ordersAfterExpired.length}`,
    );
    record(
      5,
      "PASS",
      `等待 ${ttlWaitMs}ms 后仍携带过期 quoteId=${secondQuote.id}；HTTP 200，订单 ${initialOrders.length}→${ordersAfterExpired.length}，orderId=${expiredOrderBody.id}`,
    );
    evidence.expiredQuote = {
      waitedMs: ttlWaitMs,
      expiresAt: secondQuote.expiresAt,
      request: expiredOrderRequest,
      responseStatus: expiredOrderResponse.status(),
      orderId: expiredOrderBody.id,
      orderCountAfter: ordersAfterExpired.length,
    };

    assert(pageErrors.length === 0, `页面 JavaScript 错误：${pageErrors.join(" | ")}`);
  } catch (error) {
    record(currentCheck, "FAIL", error.stack || error.message);
    for (const number of [1, 2, 3, 4, 5]) {
      if (!results.some((row) => String(row.number) === String(number))) {
        record(number, "SKIP", `前置检查 ${currentCheck} 失败`);
      }
    }
    process.exitCode = 1;
  } finally {
    const passed = results.filter((row) => row.status === "PASS").length;
    fs.writeFileSync(
      resultPath,
      JSON.stringify(
        {
          passed: passed === 5 && !process.exitCode,
          base,
          browserChannel,
          pageErrors,
          results,
          evidence,
        },
        null,
        2,
      ),
    );
    await customer.close();
    await staff.close();
    await browser.close();
  }
})();
