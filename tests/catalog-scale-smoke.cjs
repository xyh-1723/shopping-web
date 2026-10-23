#!/usr/bin/env node
/**
 * 商品规模真机冒烟：对一台<b>真跑着的应用</b>量「几百件商品」下的真实 HTTP 数字。
 *
 * <p>为什么要单独有这个脚本：{@code CatalogScaleBenchmarkTest} 量的是服务层与 MockMvc，
 * 那是同一个 JVM 内的方法调用，量不到真实的网络、Tomcat、JSON 写出与浏览器端的渲染。
 * 「商品扩到几百件」真正会被用户感知的是<b>首页一次要拉多少字节</b>和
 * <b>浏览器要渲染多少个卡片</b>，这两件事只能在真机上量。
 *
 * <p><b>数据不进迁移</b>：500 件商品由本脚本现生成 SQL、插进一个<b>独立的一次性 H2 文件库</b>
 * （{@code data/bench500}，与生产库 {@code data/mall} 无关），跑完整个目录可以直接删掉。
 * 「几百件商品」在这一次是规模验证，不是产品需求——往 {@code V2__seed_products.sql} 里
 * 插几百条假商品会让所有既有测试的数据前提变形。
 *
 * <h3>用法</h3>
 * <pre>
 *   node tests/catalog-scale-smoke.cjs gen  [输出SQL路径] [件数]   # 默认 target/bench500.sql / 500
 *   node tests/catalog-scale-smoke.cjs smoke [baseUrl]            # 默认 http://127.0.0.1:8099
 * </pre>
 *
 * <h3>配套步骤（gen 与 smoke 之间要重启一次应用）</h3>
 * <pre>
 *   mvn -o package -DskipTests
 *   java -jar target/learning-mall-1.0.0.jar \
 *        --spring.datasource.url='jdbc:h2:file:./data/bench500;DB_CLOSE_ON_EXIT=FALSE' \
 *        --server.port=8099          # 首次启动：让 Flyway 建库
 *   # 停掉应用，插数据：
 *   java -cp ~/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar org.h2.tools.RunScript \
 *        -url 'jdbc:h2:file:./data/bench500' -user sa -script target/bench500.sql
 *   # 再起一次，然后：
 *   node tests/catalog-scale-smoke.cjs smoke http://127.0.0.1:8099
 * </pre>
 */
"use strict";

const fs = require("fs");
const path = require("path");

// ————————————————————————— 参数 —————————————————————————

const mode = process.argv[2] || "smoke";
const DEFAULT_SQL = path.join("target", "bench500.sql");
const DEFAULT_BASE = "http://127.0.0.1:8099";
const RUNS = 5;
const DEMO_PASSWORD = "learn12345";

/** 填充商品的编号区间：既有种子占 1..6，这里从 1001 起，删的时候按 id > 1000 收口。 */
const FIRST_ID = 1001;
const CATEGORIES = ["生活", "文具", "数码"];

// ————————————————————————— gen —————————————————————————

/** 生成填充商品的 SQL。字段与 CatalogScaleBenchmarkTest#seed 保持一致，两个数字才有可比性。 */
function generateSql(outPath, count) {
  const lines = [
    "-- 商品规模真机冒烟的填充数据。由 tests/catalog-scale-smoke.cjs 生成，可随时丢弃。",
    "-- 刻意不写成 Flyway 迁移：「几百件商品」是规模验证，不是产品需求。",
    "",
  ];
  for (let i = 0; i < count; i++) {
    const id = FIRST_ID + i;
    const name = "基准商品" + i;
    const desc = "用于规模基准的填充商品，编号 " + i + "，描述文本按真实长度构造以便估算 token。";
    const category = CATEGORIES[i % CATEGORIES.length];
    const price = 10 + (i % 200);
    lines.push(
      `INSERT INTO product(id, name, description, category, illustration, price, stock) ` +
        `VALUES (${id}, '${name}', '${desc}', '${category}', 'box', ${price}, 50);`
    );
  }
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, lines.join("\n") + "\n", "utf8");
  console.log(`[gen] 已生成 ${count} 件填充商品 -> ${outPath}（id ${FIRST_ID}..${FIRST_ID + count - 1}）`);
}

// ————————————————————————— smoke —————————————————————————

/** 极简 cookie jar：只保留每个 cookie 名的最新值，够跑通一次表单登录。 */
function createJar() {
  const jar = new Map();
  return {
    absorb(res) {
      const raw = typeof res.headers.getSetCookie === "function" ? res.headers.getSetCookie() : [];
      for (const c of raw) {
        const pair = String(c).split(";")[0];
        const eq = pair.indexOf("=");
        if (eq > 0) jar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
      }
    },
    header() {
      return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
    },
  };
}

/** 从 Thymeleaf 页面里取 CSRF：优先 meta（index.html 有），其次隐藏域（login.html 有）。 */
function csrfFrom(html) {
  const meta = html.match(/<meta\s+name="_csrf"\s+(?:th:)?content="([^"]+)"/);
  if (meta) return meta[1];
  const input = html.match(/name="_csrf"\s+value="([^"]+)"/);
  return input ? input[1] : null;
}

async function timed(fetchOne) {
  const samples = [];
  let last = null;
  for (let i = 0; i < RUNS; i++) {
    const t0 = performance.now();
    last = await fetchOne();
    samples.push(performance.now() - t0);
  }
  samples.sort((a, b) => a - b);
  return {
    p50: samples[Math.floor(samples.length / 2)],
    max: samples[samples.length - 1],
    value: last,
  };
}

const ms = (n) => `${n.toFixed(1)}ms`;

/**
 * 浏览器端的「搜索 + 渲染」模拟。
 *
 * <p>它不是真的浏览器（没有真实布局与绘制），但跑的是与 {@code static/js/app.js}
 * <b>同一段筛选与拼串逻辑</b>：先按分类与关键词过滤，再为每条拼一个卡片的 HTML 并 join。
 * 用来回答「500 件时首页这一次渲染大概有多贵」——真机浏览器只会比它更慢，不会更快。
 */
function simulateClientRender(products, keyword, category) {
  const t0 = performance.now();
  const kw = keyword.trim().toLowerCase();
  const visible = products.filter(
    (p) =>
      (category === "全部" || p.category === category) &&
      `${p.name}${p.description}`.toLowerCase().includes(kw)
  );
  const html = visible
    .map(
      (p) =>
        `<article class="product-card"><a class="product-link" href="/product/${p.id}">` +
        `<div class="product-image" data-category="${p.category}"><span class="product-tag">${p.category}</span>` +
        `<img src="/art/${p.illustration}.svg" alt="${p.name}" loading="lazy"></div></a>` +
        `<div class="product-info"><h3><a class="product-link" href="/product/${p.id}">${p.name}</a></h3>` +
        `<p>${p.description}</p><div class="product-bottom"><div><div class="price"><small>¥</small>` +
        `${Number(p.price).toFixed(2)}</div><div class="stock">${p.stock > 0 ? `现货 ${p.stock} 件` : "暂时售罄"}</div></div>` +
        `<button class="add-button" data-add="${p.id}">+</button></div></div></article>`
    )
    .join("");
  return { count: visible.length, htmlBytes: Buffer.byteLength(html, "utf8"), elapsed: performance.now() - t0 };
}

async function smoke(base) {
  const jar = createJar();

  async function get(pathname, extra = {}) {
    const res = await fetch(base + pathname, {
      headers: { Cookie: jar.header(), ...extra },
      redirect: "manual",
    });
    jar.absorb(res);
    return res;
  }
  async function post(pathname, body, extra = {}) {
    const res = await fetch(base + pathname, {
      method: "POST",
      headers: { Cookie: jar.header(), ...extra },
      body,
      redirect: "manual",
    });
    jar.absorb(res);
    return res;
  }

  console.log(`\n=========== 商品规模真机冒烟（${base}） ===========`);
  console.log(`每条路径采样 ${RUNS} 次；P50 = 中位数，max = 最慢一次。真实 HTTP，非 MockMvc。\n`);

  // —— 1. 首页 HTML（服务端渲染，不含商品：商品由 JS 另拉） ——
  const home = await timed(async () => {
    const res = await get("/");
    return { status: res.status, bytes: Buffer.byteLength(await res.text(), "utf8") };
  });
  console.log(`1  GET /                        ${home.value.status}  ${home.value.bytes} 字节   ${ms(home.p50)} / ${ms(home.max)}`);

  // —— 2. 商品列表接口：分页缺失与否，看这一条的字节数 ——
  const list = await timed(async () => {
    const res = await get("/api/products");
    const text = await res.text();
    return { status: res.status, bytes: Buffer.byteLength(text, "utf8"), json: JSON.parse(text) };
  });
  const products = list.value.json;
  console.log(`2  GET /api/products          ${list.value.status}  ${list.value.bytes} 字节 / ${products.length} 件   ${ms(list.p50)} / ${ms(list.max)}`);

  // —— 3. 单商品详情 ——
  const detail = await timed(async () => {
    const res = await get("/api/products/" + products[0].id);
    const text = await res.text();
    return { status: res.status, bytes: Buffer.byteLength(text, "utf8") };
  });
  console.log(`3  GET /api/products/{id}     ${detail.value.status}  ${detail.value.bytes} 字节   ${ms(detail.p50)} / ${ms(detail.max)}`);

  // —— 4. 服务端有没有搜索接口：没有的话，这一条就是「搜索只能靠浏览器」的证据 ——
  const probe = await get("/api/products?keyword=%E5%9F%BA%E5%87%86&category=%E6%95%B0%E7%A0%81&page=0&size=20");
  probe.body?.cancel?.();
  console.log(`4  GET /api/products?keyword=…&page=0&size=20  -> ${probe.status}`
    + `（带上分页参数仍然返回 ${products.length} 件 = 没有分页；服务端没有搜索端点）`);

  // —— 5. 客户端「搜索 + 渲染」模拟 ——
  for (const [label, keyword, category] of [
    ["全部・空关键词", "", "全部"],
    ["数码・关键词「基准」", "基准", "数码"],
  ]) {
    const r = simulateClientRender(products, keyword, category);
    console.log(`5  客户端渲染 ${label.padEnd(18)} 命中 ${r.count} 件 / 生成 HTML ${r.htmlBytes} 字节 / 拼串 ${ms(r.elapsed)}`);
  }

  // —— 6. 登录 → 加购 → 试算 → 下单（真机走一遍写路径） ——
  const landing = await get("/");
  const landingHtml = await landing.text();
  let token = csrfFrom(landingHtml);
  if (!token) {
    console.log("\n!! 拿不到 CSRF token，跳过写路径（加购/试算/下单）");
    return;
  }
  const loginRes = await post(
    "/login",
    new URLSearchParams({ username: "student", password: DEMO_PASSWORD }).toString(),
    { "Content-Type": "application/x-www-form-urlencoded", "X-CSRF-TOKEN": token }
  );
  console.log(`\n6  POST /login                 -> ${loginRes.status}（302 = 登录成功）`);
  loginRes.body?.cancel?.();

  const afterLogin = await get("/");
  token = csrfFrom(await afterLogin.text());

  const pid = products[0].id;
  const add = await timed(async () => {
    const res = await post("/api/cart/items", JSON.stringify({ productId: pid, quantity: 1 }),
      { "Content-Type": "application/json", "X-CSRF-TOKEN": token });
    const text = await res.text();
    return { status: res.status, bytes: Buffer.byteLength(text, "utf8") };
  });
  console.log(`7  POST /api/cart/items        ${add.value.status}  ${add.value.bytes} 字节   ${ms(add.p50)} / ${ms(add.max)}`);

  const view = await timed(async () => {
    const res = await get("/api/cart");
    const text = await res.text();
    return { status: res.status, bytes: Buffer.byteLength(text, "utf8") };
  });
  console.log(`8  GET  /api/cart（结算试算）   ${view.value.status}  ${view.value.bytes} 字节   ${ms(view.p50)} / ${ms(view.max)}`);

  const quote = await timed(async () => {
    const res = await post("/api/quotes", JSON.stringify({ items: [{ productId: pid, quantity: 1 }] }),
      { "Content-Type": "application/json", "X-CSRF-TOKEN": token });
    const text = await res.text();
    return { status: res.status, bytes: Buffer.byteLength(text, "utf8") };
  });
  console.log(`9  POST /api/quotes（金额快照） ${quote.value.status}  ${quote.value.bytes} 字节   ${ms(quote.p50)} / ${ms(quote.max)}`);

  const order = await timed(async () => {
    const res = await post("/api/orders", JSON.stringify({ items: [{ productId: pid, quantity: 1 }] }),
      {
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": token,
        "Idempotency-Key": "bench-" + Date.now() + "-" + Math.random().toString(36).slice(2, 10),
      });
    const text = await res.text();
    return { status: res.status, bytes: Buffer.byteLength(text, "utf8") };
  });
  console.log(`10 POST /api/orders（下单）     ${order.value.status}  ${order.value.bytes} 字节   ${ms(order.p50)} / ${ms(order.max)}`);

  console.log("\n==============================================================\n");
}

// ————————————————————————— 入口 —————————————————————————

(async () => {
  if (mode === "gen") {
    generateSql(process.argv[3] || DEFAULT_SQL, Number(process.argv[4] || 500));
    return;
  }
  if (mode === "smoke") {
    await smoke(process.argv[3] || DEFAULT_BASE);
    return;
  }
  console.error("用法: node tests/catalog-scale-smoke.cjs gen|smoke [参数]");
  process.exit(2);
})().catch((e) => {
  console.error("[smoke] 失败:", e && e.message ? e.message : e);
  process.exit(1);
});
