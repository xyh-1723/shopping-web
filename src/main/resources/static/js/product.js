// 商品详情页：数量选择 + 加入购物袋，与首页共享同一份 localStorage 购物袋。
const $ = (selector) => document.querySelector(selector);
const user = document.body.dataset.user;
const scope = `little-mall:${user || "guest"}:`;
const wrap = $(".product-detail");
const pid = Number(wrap.dataset.id);
const name = wrap.dataset.name;
const stock = Number(wrap.dataset.stock);
const maxQty = Math.min(stock, 99);
let qty = 1;
let toastTimer;

function readCart() {
  try {
    return JSON.parse(localStorage.getItem(scope + "cart")) || {};
  } catch {
    return {};
  }
}
function saveCart(cart) {
  localStorage.setItem(scope + "cart", JSON.stringify(cart));
}
function toast(message, error = false) {
  const box = $("#toast");
  box.textContent = message;
  box.className = `toast${error ? " error" : ""}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.add("hidden"), 4500);
}
function renderQty() {
  $("#qty").textContent = qty;
}
function setQty(next) {
  if (next < 1) return;
  if (next > maxQty) {
    toast("已达到当前可购买数量", true);
    return;
  }
  qty = next;
  renderQty();
}

// 售罄商品不渲染数量选择器，相应按钮监听需判空。
const minus = $("#qty-minus");
const plus = $("#qty-plus");
if (minus) minus.addEventListener("click", () => setQty(qty - 1));
if (plus) plus.addEventListener("click", () => setQty(qty + 1));

$("#add-to-cart").addEventListener("click", () => {
  const cart = readCart();
  const have = cart[pid] || 0;
  if (have + qty > maxQty) {
    toast("购物袋里已经够了，去结算看看吧", true);
    return;
  }
  cart[pid] = have + qty;
  saveCart(cart);
  toast(`已把 ${qty} 件「${name}」放进购物袋`);
  const btn = $("#add-to-cart");
  const original = btn.innerHTML;
  btn.innerHTML = "已加入 ✓";
  setTimeout(() => (btn.innerHTML = original), 1600);
});

// 跳转到助手页并带上当前商品作为会话种子。
$("#ask-assistant").addEventListener("click", () => {
  location.href = `/assistant?seed=product:${pid}`;
});
