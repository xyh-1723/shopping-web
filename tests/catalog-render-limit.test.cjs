// 前端货架渲染上限的守护测试（B4）。
//
// 为什么需要它：商品从 6 件扩到 500 件后，renderProducts() 会把筛选后的**全部**商品
// 一次 innerHTML 渲染——506 件就是 324,863 字节的 DOM 字符串。更糟的是搜索框每次
// input 事件都会把整个货架重建一遍，等于每敲一个字符重排 32 万字节。
//
// 怎么测：不启浏览器。用 node 的 vm 沙箱加载真实的 app.js，注入一个最小假 DOM，
// 让首屏那个 async IIFE 真的跑到 renderProducts()，然后数 #products 里的卡片。
// 这样断言落在真实渲染产物上，而不是落在源码文本上。
//
// 运行：node --test tests/catalog-render-limit.test.cjs
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const APP_JS = fs.readFileSync(
  path.resolve(__dirname, "../src/main/resources/static/js/app.js"),
  "utf8",
);
/** 与 app.js 里的 RENDER_LIMIT 保持一致：改了实现而这里没跟着改，测试会先红。 */
const RENDER_LIMIT = 60;

/** 最小假节点：只实现 app.js 真正用到的那几个成员。 */
function makeNode() {
  return {
    textContent: "",
    innerHTML: "",
    value: "",
    dataset: {},
    handlers: {},
    classList: { add() {}, remove() {}, toggle() {} },
    addEventListener(type, fn) {
      (this.handlers[type] ||= []).push(fn);
    },
    querySelectorAll: () => [],
    closest() {
      return this;
    },
  };
}

/**
 * 在沙箱里跑一遍真实的 app.js，返回假 DOM 上的关键节点。
 * @param {number} count 货架上放几件商品
 * @returns {Promise<{container: object, search: object, document: object}>}
 */
async function boot(count) {
  const products = Array.from({ length: count }, (_, i) => ({
    id: 1001 + i,
    name: `货架商品${i}`,
    description: `第 ${i} 件商品的描述，用于把渲染产物撑到可测量的量级。`,
    category: ["生活", "文具", "数码"][i % 3],
    illustration: "box",
    price: "9.90",
    stock: 5,
  }));

  const nodes = new Map();
  const container = makeNode();
  const document = {
    body: { dataset: {} },
    handlers: {},
    querySelector(selector) {
      if (selector === "#products") return container;
      if (!nodes.has(selector)) nodes.set(selector, makeNode());
      return nodes.get(selector);
    },
    querySelectorAll: () => [],
    addEventListener(type, fn) {
      (this.handlers[type] ||= []).push(fn);
    },
  };

  const sandbox = {
    document,
    console,
    setTimeout,
    clearTimeout,
    // 轮询定时器在单测里没有意义，做成空实现：否则进程会被一个 3 秒的 setInterval 挂住不退出。
    setInterval: () => 0,
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    AbortSignal: { timeout: () => undefined },
    location: { href: "" },
    fetch: async () => ({
      ok: true,
      status: 200,
      text: async () => JSON.stringify(products),
    }),
  };

  vm.createContext(sandbox);
  vm.runInContext(APP_JS, sandbox);
  // 首屏是 async IIFE：把微任务队列排空，确保 renderProducts() 已跑完。
  for (let i = 0; i < 50; i += 1) await new Promise((resolve) => setImmediate(resolve));

  return { container, search: nodes.get("#search"), document };
}

/** 数出 innerHTML 里渲染了几张商品卡片。 */
function cardCount(html) {
  return html.split("<article class=\"product-card\"").length - 1;
}

test("首屏渲染：几百件商品时只渲染前 60 件，并提示还有多少件未显示", async () => {
  const { container } = await boot(506);

  assert.equal(cardCount(container.innerHTML), RENDER_LIMIT);
  assert.match(container.innerHTML, /还有 446 件未显示/);
});

test("筛选后的真实总数仍然显示在 #product-count，不受渲染上限影响", async () => {
  const { container, document } = await boot(506);

  // 这里必须是 506，不是 60：否则用户会以为店里只有 60 件。
  assert.equal(document.querySelector("#product-count").textContent, 506);
  assert.equal(cardCount(container.innerHTML), RENDER_LIMIT);
});

test("搜索框每次输入都重建货架：这条路同样受渲染上限约束", async () => {
  const { container, search, document } = await boot(506);
  assert.equal(cardCount(container.innerHTML), RENDER_LIMIT);

  const handler = search.handlers.input[0];
  assert.equal(typeof handler, "function", "搜索框必须挂了 input 监听，否则这条断言是空跑");

  // 「货架商品1」会命中 1 + 10 + 100 = 111 件：重建后仍只渲染 60 件，计数报 111。
  search.value = "货架商品1";
  handler();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(cardCount(container.innerHTML), RENDER_LIMIT);
  assert.equal(document.querySelector("#product-count").textContent, 111);
  assert.match(container.innerHTML, /还有 51 件未显示/);
});

test("商品数不超过上限时全部渲染，且不出现「还有 N 件」的提示", async () => {
  const { container, search } = await boot(20);

  assert.equal(cardCount(container.innerHTML), 20);
  assert.doesNotMatch(container.innerHTML, /还有 \d+ 件未显示/);

  // 收窄到少数几件后，提示同样不应该出现。
  search.value = "不存在的关键词";
  search.handlers.input[0]();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(cardCount(container.innerHTML), 0);
  assert.doesNotMatch(container.innerHTML, /还有 \d+ 件未显示/);
});
