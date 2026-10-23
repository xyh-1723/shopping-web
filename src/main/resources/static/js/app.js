// 原生 JavaScript：无前端构建步骤，学习重心留在后端。
const $ = (selector) => document.querySelector(selector);
const user = document.body.dataset.user;
const scope = `little-mall:${user || "guest"}:`;
const money = (value) => `¥${Number(value).toFixed(2)}`;
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        char
      ],
  );
function read(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(scope + key)) ?? fallback;
  } catch {
    return fallback;
  }
}
// 购物袋存在服务端（登录用户）：同一账号跨标签页、跨设备一致，助手也能往里加东西——
// 如果它只活在某个浏览器的 localStorage 里，助手加购完用户根本看不到。
// 未登录时先暂存在本地，登录那一刻合并进服务端。
const GUEST_CART_KEY = "little-mall:guest:cart";
let cartView = null; // 服务端返回：items + 试算金额 + 优惠说明
let currentQuoteId = null; // 用户看到当前购物袋金额时，由服务端拍下的快照
let guestCart = {};
try {
  guestCart = JSON.parse(localStorage.getItem(GUEST_CART_KEY)) || {};
} catch {}

let products = [],
  category = "全部",
  keyword = "",
  currentView = "shop",
  busy = false,
  toastTimer;
// —— 分页：一次只向服务端要一页 ——
// 以前这里是一次 GET /api/products 把整个目录拉回来（实测 1006 件时 227KB，
// 线性外推 5 万件是 11MB），然后在本机 filter。现在筛选与翻页都交给服务端，
// 浏览器只持有当前这一页。PAGE_SIZE 与后端 ProductQuery.DEFAULT_SIZE 对齐。
const PAGE_SIZE = 24;
let page = 0,
  totalPages = 1,
  total = 0;
function productsUrl() {
  const query = new URLSearchParams({ page, size: PAGE_SIZE });
  // 前端不再自己拼 category=全部：那是"不限"的意思，不是真的有个叫"全部"的分类。
  if (category !== "全部") query.set("category", category);
  if (keyword) query.set("keyword", keyword);
  return `/api/products?${query}`;
}
// —— 已见商品缓存 ——
// 分页之后浏览器只持有当前这一页，但购物袋里的商品可能是上一页的。
// 没有这份跨页累积的缓存，「翻到第二页后购物袋少了一半商品」这种问题
// 不会报错，只是静静地少显示几行——属于最难查的那一类。
const knownProducts = new Map();
function rememberProducts(list) {
  list.forEach((p) => knownProducts.set(p.id, p));
}
const findProduct = (id) => knownProducts.get(Number(id));

async function loadProductPage() {
  const view = await api(productsUrl());
  products = view.items || [];
  total = view.total || 0;
  totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  // 目录缩小（或筛选后命中变少）时页码会越界，拉回来一页空的结果很难查。
  if (page > totalPages - 1) {
    page = totalPages - 1;
    return loadProductPage();
  }
  rememberProducts(products);
  renderProducts();
  renderPager();
}
async function loadProducts() {
  await loadProductPage();
  await loadCart();
  renderCart();
}
// 翻页与筛选共用的入口：改条件就回到第一页，只翻页才保留页码。
function resetPage() {
  page = 0;
}
function renderPager() {
  const box = $("#pager");
  if (!box) return;
  if (total <= PAGE_SIZE) {
    box.innerHTML = "";
    return;
  }
  const prev = `<button class="button outline small" data-page="${page - 1}" ${page === 0 ? "disabled" : ""}>上一页</button>`;
  const next = `<button class="button outline small" data-page="${page + 1}" ${page >= totalPages - 1 ? "disabled" : ""}>下一页</button>`;
  box.innerHTML = `${prev}<span class="subtle">第 ${page + 1} / ${totalPages} 页</span>${next}`;
}
// 搜索防抖：每敲一个字符就打一次服务端，等于把一次输入变成十几次查询。
// 250ms 是"打完这个词"和"等得有点久"之间的折中。
let searchTimer;
function onSearchInput() {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(async () => {
    keyword = $("#search").value.trim();
    resetPage();
    // 只刷货架：搜索不会改变购物袋，顺带重拉袋子只会多打两次接口。
    try {
      await loadProductPage();
    } catch (error) {
      toast(error.message, true);
    }
  }, 250);
}
function saveGuestCart() {
  localStorage.setItem(GUEST_CART_KEY, JSON.stringify(guestCart));
}
async function captureCurrentQuote() {
  currentQuoteId = null;
  if (!user || !cartView?.items?.length) return;
  try {
    const items = cartView.items.map(({ productId, quantity }) => ({
      productId,
      quantity,
    }));
    const quote = await api("/api/quotes", {
      method: "POST",
      body: JSON.stringify({ items }),
    });
    currentQuoteId = quote.id;
  } catch {
    // 快照是用户保护而非下单权限边界；网络抖动时保留购物袋并按无快照兼容。
    currentQuoteId = null;
  }
}
async function loadCart() {
  if (!user) return;
  try {
    cartView = await api("/api/cart");
    // 金额展示给用户的这一刻拍快照，而不是点击提交后才拍。
    await captureCurrentQuote();
  } catch {
    cartView = null;
    currentQuoteId = null;
  }
}
// 登录后把游客暂存合并进服务端购物袋；失败不影响正常浏览，暂存会留到下次。
async function mergeGuestCart() {
  if (!user) return;
  const ids = Object.keys(guestCart);
  if (!ids.length) return;
  try {
    for (const id of ids)
      await api("/api/cart/items", {
        method: "POST",
        body: JSON.stringify({ productId: Number(id), quantity: guestCart[id] }),
      });
    guestCart = {};
    localStorage.removeItem(GUEST_CART_KEY);
    await loadCart();
  } catch {}
}
function toast(message, error = false) {
  const box = $("#toast");
  box.textContent = message;
  box.className = `toast${error ? " error" : ""}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.add("hidden"), 4500);
}
async function api(path, options = {}) {
  const headers = {
    "Content-Type": "application/json",
    [document.querySelector('meta[name="_csrf_header"]').content]:
      document.querySelector('meta[name="_csrf"]').content,
    ...options.headers,
  };
  const response = await fetch(path, {
    ...options,
    headers,
    signal: AbortSignal.timeout(20000),
  });
  if (response.status === 401) {
    location.href = "/login";
    throw new Error("请先登录");
  }
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    throw new Error("响应格式异常，请刷新页面后重试");
  }
  if (!response.ok) throw new Error(data?.message || "请求失败，请稍后重试");
  return data;
}
// 单次渲染的卡片上限。实测 506 件商品一次性 innerHTML 会拼出 324,863 字节的 DOM 字符串；
// 而搜索框每次 input 事件都会把整个货架重建一遍——那等于每敲一个字符就重排 32 万字节。
// 超出上限的不再渲染，改为一句提示，让用户用分类或搜索自己收窄范围。
function renderProducts() {
  // 筛选与搜索都已经在服务端做完（keyword / category 随分页请求一起发出去），
  // 这里只负责渲染这一页。以前是在浏览器里 filter 整个目录——目录 5 万件时
  // 每敲一个字符都要遍历 5 万条，还会把不在本页的商品一起算进「共 N 件」。
  $("#product-count").textContent = total;
  const cards = products
    .map(
      (p) =>
        `<article class="product-card"><a class="product-link" href="/product/${p.id}"><div class="product-image" data-category="${esc(p.category)}"><span class="product-tag">${esc(p.category)}</span><img src="/art/${esc(p.illustration)}.svg" alt="${esc(p.name)}" loading="lazy"></div></a><div class="product-info"><h3><a class="product-link" href="/product/${p.id}">${esc(p.name)}</a></h3><p>${esc(p.description)}</p><div class="product-bottom"><div><div class="price"><small>¥</small>${Number(p.price).toFixed(2)}</div><div class="stock">${p.stock > 0 ? `现货 ${p.stock} 件` : "暂时售罄"}</div></div><button class="add-button" data-add="${p.id}" aria-label="将${esc(p.name)}加入购物车" ${p.stock === 0 ? "disabled" : ""}>+</button></div></div></article>`,
    )
    .join("");
  $("#products").innerHTML =
    cards || '<p class="empty">没有找到这件小物，换个关键词试试。</p>';
}
// 当前购物袋里的行：登录用户取服务端快照（含实时价与库存），游客取本地暂存。
function cartEntries() {
  if (user && cartView)
    // 服务端返回的每一行业务字段齐全（名称、图、单价、库存），
    // 不依赖「这件商品正好在当前这一页」——分页之后这个前提不再成立。
    return cartView.items.map((item) => ({
      productId: item.productId,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      subtotal: item.subtotal,
      stock: item.stock,
      available: item.available,
      product: {
        id: item.productId,
        name: item.name,
        illustration: item.illustration,
        price: item.unitPrice,
        stock: item.stock,
      },
    }));
  return Object.entries(guestCart)
    .map(([id, quantity]) => {
      const product = findProduct(id);
      if (!product) return null;
      return {
        productId: product.id,
        quantity,
        unitPrice: product.price,
        subtotal: product.price * quantity,
        stock: product.stock,
        available: product.stock >= quantity,
        product,
      };
    })
    .filter(Boolean);
}
function renderCart() {
  const entries = cartEntries();
  const count = entries.reduce((n, row) => n + row.quantity, 0);
  // 金额统一由服务端算（含满减与运费），前端只做展示——同一个数字只有一处算法。
  const total =
    user && cartView
      ? Number(cartView.total)
      : entries.reduce((n, row) => n + Number(row.unitPrice) * row.quantity, 0);
  $("#cart-count").textContent = count;
  $("#cart-total").textContent = money(total);
  $("#checkout").disabled = count === 0 || busy;
  const notes =
    user && cartView && cartView.notes && cartView.notes.length
      ? `<div class="cart-notes">${cartView.notes
          .map((text) => `<span>${esc(text)}</span>`)
          .join("")}</div>`
      : "";
  $("#cart-items").innerHTML = entries.length
    ? notes +
      entries
        .map(
          (row) =>
            `<div class="cart-row"><img src="/art/${esc(row.product.illustration)}.svg" alt=""><div class="cart-row-main"><div class="cart-row-title"><span>${esc(row.product.name)}</span><button class="remove" data-remove="${row.productId}" aria-label="移除${esc(row.product.name)}">×</button></div><div class="cart-row-bottom"><strong>${money(row.unitPrice)}</strong><div class="quantity"><button data-adjust="${row.productId}" data-delta="-1" aria-label="减少${esc(row.product.name)}数量">−</button><span>${row.quantity}</span><button data-adjust="${row.productId}" data-delta="1" aria-label="增加${esc(row.product.name)}数量">+</button></div></div>${row.available ? "" : `<div class="cart-warn">库存只剩 ${row.stock} 件，请调整数量</div>`}</div></div>`,
        )
        .join("")
    : '<div class="empty"><div class="empty-bag">♧</div>购物袋还是空的<br>选一件喜欢的小物吧</div>';
}
async function adjust(id, delta) {
  if (busy) return;
  // 优先查缓存（跨页累积）；袋内行在缓存里查不到时，服务端快照本身也带着实时库存。
  const p =
    findProduct(id) ||
    (user && cartView
      ? cartView.items.find((item) => item.productId === Number(id))
      : null);
  if (!p) return;
  const current = user && cartView
    ? cartView.items.find((item) => item.productId === Number(id))?.quantity || 0
    : guestCart[id] || 0;
  const next = current + delta;
  if (delta > 0 && next > Math.min(p.stock, 99))
    return toast("已达到当前可购买数量", true);
  await setQuantity(Number(id), next);
}
async function addToCart(id) {
  const p = findProduct(id);
  if (!p || p.stock === 0) return;
  if (!user) {
    guestCart[id] = Math.min((guestCart[id] || 0) + 1, Math.min(p.stock, 99));
    saveGuestCart();
    renderCart();
    return toast("已暂存，登录后会自动并入购物袋");
  }
  try {
    cartView = await api("/api/cart/items", {
      method: "POST",
      body: JSON.stringify({ productId: Number(id), quantity: 1 }),
    });
    await captureCurrentQuote();
    renderCart();
    toast(`已加入购物袋：${p.name}`);
  } catch (error) {
    toast(error.message, true);
  }
}
async function setQuantity(id, quantity) {
  if (!user) {
    if (quantity <= 0) delete guestCart[id];
    else guestCart[id] = quantity;
    saveGuestCart();
    renderCart();
    return;
  }
  try {
    cartView =
      quantity <= 0
        ? await api(`/api/cart/items/${id}`, { method: "DELETE" })
        : await api(`/api/cart/items/${id}`, {
            method: "PUT",
            body: JSON.stringify({ productId: id, quantity }),
          });
    await captureCurrentQuote();
    renderCart();
  } catch (error) {
    // 服务端拒绝（库存不足等）时以服务端为准重拉一次，避免前端显示与真实状态不一致。
    toast(error.message, true);
    await loadCart();
    renderCart();
  }
}
async function checkout() {
  if (!user) {
    location.href = "/login";
    return;
  }
  const entries = cartEntries();
  if (busy || !entries.length) return;
  const body = {
    items: entries
      .map((row) => ({ productId: Number(row.productId), quantity: row.quantity }))
      .sort((a, b) => a.productId - b.productId),
    quoteId: currentQuoteId,
  };
  // 这里是浏览器层把快照串到下单请求的关键点；MockMvc 只能覆盖服务端链路，
  // 未引入 JavaScript 测试框架前，本段仍需由真机反向冒烟守护。
  let request = read("pendingRequest", null);
  if (!request || JSON.stringify(request.body) !== JSON.stringify(body))
    request = { key: crypto.randomUUID(), body };
  // 发送之前保存 key 和正文：网络超时后重试仍使用同一编号。
  localStorage.setItem(scope + "pendingRequest", JSON.stringify(request));
  busy = true;
  renderCart();
  $("#checkout").textContent = "正在提交…";
  try {
    const order = await api("/api/orders", {
      method: "POST",
      headers: { "Idempotency-Key": request.key },
      body: JSON.stringify(request.body),
    });
    localStorage.setItem(scope + "lastRequest", JSON.stringify(request));
    localStorage.removeItem(scope + "pendingRequest");
    // 不再在这里清袋：服务端下单成功后会同步清空（订单落库事件），页面与助手两条路径一致。
    // 原来的写法是 await api("/api/cart", { method: "DELETE" }).catch(() => {})，
    // 失败被静默吞掉——袋子留着、用户毫不知情，下次可能重复下单。清袋不该由前端负责。
    await loadCart();
    await loadProducts();
    await showView("orders");
    toast(`订单 ${order.id.slice(0, 8)} 已创建`);
  } catch (error) {
    toast(error.message + "；可再次提交重试", true);
  } finally {
    busy = false;
    $("#checkout").innerHTML = "提交订单 <span>→</span>";
    renderCart();
  }
}
async function replay() {
  const request = read("lastRequest", null);
  if (!request) return toast("请先完成一次下单");
  $("#replay").disabled = true;
  try {
    const order = await api("/api/orders", {
      method: "POST",
      headers: { "Idempotency-Key": request.key },
      body: JSON.stringify(request.body),
    });
    toast(`幂等生效：仍是订单 ${order.id.slice(0, 8)}，没有重复扣库存`);
    await loadOrders();
  } catch (error) {
    toast(error.message, true);
  } finally {
    $("#replay").disabled = false;
  }
}
const statusText = {
  PENDING: "待处理",
  PAID: "已支付",
  CANCELLED: "已取消",
  DELIVERED: "已送达",
  DEAD: "死信",
};
const time = (value) =>
  String(value || "")
    .replace("T", " ")
    .slice(0, 19);
// 关闭原因决定用户看到什么：主动取消与超时关闭在数据上是同一个状态，
// 但对用户是两件事——后者要告诉他「东西还在，可以重新下单」。
const closeReasonText = { USER: "已取消", TIMEOUT: "已超时关闭" };
const deadlineOf = (value) => new Date(String(value || "").slice(0, 19)).getTime();
const leftMs = (order) =>
  order.expiresAt ? deadlineOf(order.expiresAt) - Date.now() : Infinity;
const clockText = (ms) => {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
};
const orderBadge = (order) => {
  if (order.status === "PENDING") return leftMs(order) > 0 ? "待支付" : "支付超时";
  if (order.status === "CANCELLED")
    return closeReasonText[order.closeReason] || "已取消";
  return statusText[order.status] || order.status;
};
let orderTicker = null;
let orderRefreshPending = false;
// 倒计时只是体验，服务端才是唯一权威（客户端时钟可以随手改）。
// 所以归零后不是自己把订单标成「已关闭」，而是回去问一次服务端。
function tickCountdowns() {
  let expired = false;
  document.querySelectorAll("[data-deadline]").forEach((node) => {
    const left = deadlineOf(node.dataset.deadline) - Date.now();
    node.textContent = clockText(left);
    if (left <= 0) expired = true;
  });
  if (expired && !orderRefreshPending) {
    orderRefreshPending = true;
    loadOrders()
      .then(loadProducts)
      .catch(() => {})
      .finally(() => {
        orderRefreshPending = false;
      });
  }
}
// ————— 钱包：让「钱从哪儿出去的」看得见 —————
// 后端默认支付方式已经是钱包（扣的是真余额），页面上却只有一个下拉框、
// 看不见余额，等于让用户蒙着眼睛选。余额与流水一律读服务端，前端不做任何加减。
const WALLET_REASONS = {
  OPENING: "开户赠送",
  TOPUP: "演示充值",
  PAYMENT: "下单支付",
  REFUND: "退款到账",
};
async function loadWallet() {
  if (!user) return;
  try {
    renderWallet(await api("/api/wallet"));
  } catch {
    // 钱包看不到不该拖垮整个页面：订单与货架仍然要能用。
    const box = $("#wallet");
    if (box) box.innerHTML = '<div class="wallet-head"><span class="subtle">钱包暂时不可用</span></div>';
  }
}
function renderWallet(view) {
  const box = $("#wallet");
  if (!box || !view?.balance) return;
  // 首屏这一格是 hidden 的（避免先闪一个空框），拿到数据后必须自己摘掉它。
  // 少了这一句，.hidden 是 display:none !important，钱包在数据到位之后依然不可见——
  // 「余额查到了但用户看不见」和「接口根本没通」在页面上长得一模一样。
  box.classList.remove("hidden");
  const recent = (view.recent || []).slice(0, 5);
  const rows = recent
    .map((entry) => {
      const out = Number(entry.deltaCents) < 0;
      return `<li><span>${esc(WALLET_REASONS[entry.reason] || entry.reason)}${
        entry.orderId ? ` · 订单 ${esc(String(entry.orderId).slice(0, 8))}` : ""
      }</span><span class="${out ? "wallet-out" : "wallet-in"}">${
        out ? "−" : "+"
      }${money(Math.abs(Number(entry.deltaYuan)))}</span></li>`;
    })
    .join("");
  box.innerHTML = `<div class="wallet-head"><div><span class="subtle">我的钱包余额</span><strong>${money(
    Number(view.balance.balanceYuan ?? 0),
  )}</strong></div><button class="button outline small" data-wallet-action="topup">充值 ¥100</button></div>${
    rows
      ? `<ul class="wallet-ledger">${rows}</ul>`
      : '<p class="subtle">还没有资金变动记录。</p>'
  }<p class="subtle">演示充值固定 ¥100，累计上限 ¥1000；用钱包支付的订单，钱会从这里真的扣掉。</p>`;
}
async function topUpWallet(button) {
  button.disabled = true;
  try {
    const view = await api("/api/wallet/top-up", { method: "POST" });
    renderWallet(view);
    toast("充值成功，余额已更新");
  } catch (error) {
    toast(error.message, true);
  } finally {
    button.disabled = false;
  }
}
async function loadOrders() {
  const orders = await api("/api/orders");
  $("#orders").innerHTML =
    orders
      .map((order) => {
        const pending = order.status === "PENDING";
        const expired = pending && leftMs(order) <= 0;
        const actions = pending
          ? expired
            ? '<span class="subtle">支付已超时，系统正在关闭订单并归还库存</span>'
            : `<select aria-label="模拟支付方式" id="method-${order.id}"><option value="DEMO_WALLET">模拟钱包</option><option value="DEMO_CARD">模拟银行卡</option></select><button class="button outline small" data-order-action="cancel" data-id="${order.id}">取消订单</button><button class="button green small" data-order-action="pay" data-id="${order.id}">模拟支付</button>`
          : order.status === "PAID"
            ? // 钱包支付是真的从余额里扣了钱的，再写「无实际扣款」就是对用户撒谎。
              `<span class="subtle">${
                order.paymentMethod === "DEMO_WALLET"
                  ? "已从我的钱包扣款"
                  : "模拟交易 · 无实际扣款"
              }</span>`
            : `<span class="subtle">${order.closeReason === "TIMEOUT" ? "超时未支付 · 库存已归还，可以重新下单" : "库存已归还"}</span>`;
        const countdown = pending
          ? `<div class="order-meta">剩余支付时间 <strong data-deadline="${esc(String(order.expiresAt).slice(0, 19))}">${clockText(leftMs(order))}</strong></div>`
          : "";
        // 金额拆成四行展示：只有一个「合计」数字时，用户无法判断优惠到底算没算进去。
        // 应付一律取服务端算好的 payAmount，前端不做任何加减。
        const num = (v) => (v === undefined || v === null ? 0 : Number(v));
        const breakdown = `<div class="order-amounts">
            <div><span class="subtle">商品小计</span><span>${money(num(order.subtotal))}</span></div>
            ${num(order.discount) > 0 ? `<div><span class="subtle">满减优惠</span><span class="discount">−${money(num(order.discount))}</span></div>` : ""}
            <div><span class="subtle">运费</span><span>${money(num(order.shippingFee))}</span></div>
            <div class="order-payable"><span>应付</span><span>${money(num(order.payAmount))}</span></div>
          </div>`;
        return `<article class="order-card"><div class="order-top"><div><h3>订单 ${esc(order.id.slice(0, 8))}</h3><div class="order-meta">${esc(time(order.createdAt))} · ${esc(order.id)}</div>${countdown}</div><span class="badge ${esc(order.status)}">${orderBadge(order)}</span></div>${order.items.map((item) => `<div class="order-item"><span>${esc(item.productName)} <span class="subtle">× ${item.quantity}</span></span><span>${money(item.unitPrice * item.quantity)}</span></div>`).join("")}${breakdown}<div class="order-actions"><strong>${money(num(order.payAmount))}</strong>${actions}</div></article>`;
      })
      .join("") ||
    '<div class="empty">还没有订单，先去挑选一件喜欢的小物吧。</div>';
  if (orderTicker) clearInterval(orderTicker);
  orderTicker = setInterval(tickCountdowns, 1000);
}
async function orderAction(button) {
  const { id, orderAction: action } = button.dataset;
  button.disabled = true;
  try {
    const wallet = action === "pay" && document.getElementById("method-" + id).value === "DEMO_WALLET";
    const body =
      action === "pay"
        ? JSON.stringify({
            method: document.getElementById("method-" + id).value,
          })
        : undefined;
    await api(`/api/orders/${id}/${action}`, { method: "POST", body });
    toast(
      action === "pay"
        ? wallet
          ? "支付成功，已从我的钱包扣款"
          : "模拟支付成功，没有发生真实扣款"
        : "订单已取消，库存已归还",
    );
    await Promise.all([loadOrders(), loadProducts()]);
    // 钱包支付会真的扣余额，支付后必须把余额刷一遍，否则页面显示的还是上一次的值。
    await loadWallet();
  } catch (error) {
    toast(error.message, true);
  } finally {
    button.disabled = false;
  }
}
async function loadMessages() {
  const data = await api("/api/messages");
  $("#pool-stats").innerHTML = [
    [data.pool.size, "已创建线程"],
    [data.pool.active, "正在执行"],
    [data.pool.queued, "内存中排队"],
    [data.pool.maximum, "最大线程数"],
  ]
    .map(
      ([n, label]) =>
        `<div class="stat"><span>${label}</span><strong>${n}</strong></div>`,
    )
    .join("");
  $("#notifications").innerHTML =
    data.notifications
      .map(
        (n) =>
          `<article class="notice"><span class="eyebrow muted">订单通知</span><p>${esc(n.content)}</p><time>${esc(time(n.createdAt))}</time></article>`,
      )
      .join("") ||
    '<p class="empty">还没有通知。下一次下单后，在这里等一小会儿。</p>';
  $("#events").innerHTML =
    data.events
      .map(
        (e) =>
          `<article class="event"><div class="event-title"><strong>${esc(e.eventType)}</strong><span class="badge ${esc(e.status)}">${statusText[e.status]}</span></div><p>${esc(e.content)}</p><small>失败 ${e.attempts} 次 · ${esc(time(e.createdAt))}${e.lastError ? ` · ${esc(e.lastError)}` : ""}</small>${e.status === "DEAD" ? `<div><button class="button outline small" data-retry="${e.id}">重新投递</button></div>` : ""}</article>`,
      )
      .join("") || '<p class="empty">消息队列目前没有你的投递记录。</p>';
}
async function showView(view) {
  if (view !== "shop" && !user) {
    location.href = "/login";
    return;
  }
  currentView = view;
  document
    .querySelectorAll(".view")
    .forEach((el) => el.classList.toggle("hidden", el.id !== view + "-view"));
  document
    .querySelectorAll("[data-view]")
    .forEach((el) => el.classList.toggle("active", el.dataset.view === view));
  // 离开订单页就停掉倒计时：看不见的东西不该继续占用定时器。
  if (view !== "orders" && orderTicker) {
    clearInterval(orderTicker);
    orderTicker = null;
  }
  try {
    if (view === "orders") await Promise.all([loadOrders(), loadWallet()]);
    if (view === "messages") await loadMessages();
  } catch (error) {
    toast(error.message, true);
  }
}
document.addEventListener("click", async (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  if (button.dataset.view) await showView(button.dataset.view);
  if (button.dataset.category) {
    category = button.dataset.category;
    document
      .querySelectorAll("[data-category].chip")
      .forEach((el) => el.classList.toggle("active", el === button));
    resetPage();
    // 分类筛选以前是在浏览器里做的（点一下 chip 只是重排本机数组）；
    // 现在它是服务端条件，必须真的回去要一次数据，否则按钮点了没反应。
    await loadProductPage();
  }
  if (button.dataset.add) await addToCart(Number(button.dataset.add));
  if (button.dataset.page) {
    page = Number(button.dataset.page);
    await loadProductPage();
  }
  if (button.dataset.adjust)
    await adjust(Number(button.dataset.adjust), Number(button.dataset.delta));
  if (button.dataset.remove && !busy) await setQuantity(Number(button.dataset.remove), 0);
  if (button.dataset.orderAction) await orderAction(button);
  if (button.dataset.walletAction === "topup") await topUpWallet(button);
  if (button.dataset.retry) {
    button.disabled = true;
    try {
      await api(`/api/messages/${button.dataset.retry}/retry`, {
        method: "POST",
      });
      toast("已放回待投递队列");
      await loadMessages();
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
    }
  }
});
// 搜索走服务端（keyword 随分页请求发出），所以这里不能直接重排本机数组；
// 250ms 防抖把"打完一个词"合并成一次请求，而不是每敲一个字符打一次服务端。
$("#search").addEventListener("input", onSearchInput);
$("#checkout").addEventListener("click", checkout);
$("#replay").addEventListener("click", replay);
let refreshing = false;
setInterval(async () => {
  if (currentView !== "messages" || document.hidden || refreshing) return;
  refreshing = true;
  try {
    await loadMessages();
  } catch (error) {
    toast(error.message, true);
  } finally {
    refreshing = false;
  }
}, 3000);
(async () => {
  // 登录状态下先把游客暂存并进服务端购物袋，再拉货架，首屏显示的就是完整购物车。
  if (user) await mergeGuestCart();
  try {
    await loadProducts();
  } catch (error) {
    $("#products").innerHTML =
      '<p class="empty">货架暂时加载失败，请刷新页面重试。</p>';
    toast(error.message, true);
  }
  // 钱包与首屏并行无关，失败也不该挡住货架（loadWallet 自己吞掉异常）。
  await loadWallet();
})();
