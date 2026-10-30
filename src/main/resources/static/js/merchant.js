// 商家中心：只读视图。所有数字都来自 /api/merchant/inventory，
// 前端不做求和——"本店有多少件货"只该有一个算法（SQL 里那条）。
const $ = (selector) => document.querySelector(selector);
const money = (value) => `¥${Number(value).toFixed(2)}`;
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        char
      ],
  );

const PAGE_SIZE = 24;
let page = 0;
let total = 0;
let totalPages = 1;
let keyword = "";
let searchTimer;

function inventoryUrl() {
  const params = new URLSearchParams({ page, size: PAGE_SIZE });
  if (keyword) params.set("keyword", keyword);
  return `/api/merchant/inventory?${params}`;
}

async function api(path) {
  const response = await fetch(path, { headers: { Accept: "application/json" } });
  if (response.status === 401) {
    location.href = "/login";
    throw new Error("请先登录");
  }
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    throw new Error("响应格式异常，请刷新页面后重试");
  }
  if (!response.ok) throw new Error(data?.message || "读取库存失败");
  return data;
}

function stat(label, value, hint) {
  return `<div class="stat"><span>${label}</span><strong>${value}</strong><span class="stat-hint">${hint}</span></div>`;
}

function renderSummary(summary) {
  $("#merchant-summary").innerHTML = [
    stat("本店商品种类", summary.productCount, "含已售罄的商品"),
    stat("库存总量", summary.stockTotal, "全部商品的件数之和"),
    stat("售罄商品", summary.outOfStockCount, "库存为 0，需要补货"),
  ].join("");
}

function stockCell(stock) {
  if (stock === 0) return '<span class="stock-out">售罄</span>';
  if (stock < 10) return `<span class="stock-low">${stock} 件</span>`;
  return `${stock} 件`;
}

function renderProducts(view) {
  const items = view.items || [];
  total = view.total || 0;
  if (!items.length) {
    $("#merchant-products").innerHTML = '<p class="empty">本店没有匹配的商品。</p>';
    return;
  }
  const rows = items
    .map(
      (p) =>
        `<tr><td><div class="product-name">${esc(p.name)}</div><div class="product-desc">${esc(p.description)}</div></td><td>${esc(p.category)}</td><td class="num">${money(p.price)}</td><td class="num">${stockCell(p.stock)}</td></tr>`,
    )
    .join("");
  $("#merchant-products").innerHTML = `<p class="subtle merchant-result">共 ${total} 件商品</p><div class="merchant-table-wrap"><table class="merchant-table"><thead><tr><th>商品</th><th>分类</th><th>单价</th><th>库存</th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

function renderPager() {
  const box = $("#merchant-pager");
  if (!box) return;
  if (total <= PAGE_SIZE) {
    box.innerHTML = "";
    return;
  }
  const prev = `<button class="button outline small" data-page="${page - 1}" ${page === 0 ? "disabled" : ""}>上一页</button>`;
  const next = `<button class="button outline small" data-page="${page + 1}" ${page >= totalPages - 1 ? "disabled" : ""}>下一页</button>`;
  box.innerHTML = `${prev}<span class="subtle">第 ${page + 1} / ${totalPages} 页</span>${next}`;
}

async function load() {
  try {
    const view = await api(inventoryUrl());
    total = view.page.total || 0;
    totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
    // 搜到更少的商品后页码会越界：留在越界页上会得到一张空表。
    if (page > totalPages - 1) {
      page = totalPages - 1;
      return load();
    }
    renderSummary(view.summary);
    renderProducts(view.page);
    renderPager();
  } catch (error) {
    $("#merchant-products").innerHTML = `<p class="empty">${esc(error.message)}</p>`;
    $("#merchant-summary").innerHTML = "";
    $("#merchant-pager").innerHTML = "";
  }
}

document.addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-page]");
  if (!button) return;
  page = Number(button.dataset.page);
  await load();
});

// 与货架同一个 250ms 防抖：每敲一个字符打一次服务端，等于把一次输入变成十几次查询。
const search = $("#merchant-search");
if (search) {
  search.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
      keyword = search.value.trim();
      page = 0;
      await load();
    }, 250);
  });
}

if ($("#merchant-products")) load();
