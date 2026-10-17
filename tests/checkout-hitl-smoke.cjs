// 真实容器下的「金额口径统一（G1）+ 助手下单闭环与 HITL 泛化（G2）」冒烟。
//
// 为什么需要它：集成测试跑在 MockMvc + 内存库上。这一轮改动的验收点恰恰必须落在真实库上：
//   * V12/V13 是 ALTER TABLE / CHECK 约束，内存库重建一次就跑通不代表文件库迁移历史能兼容；
//   * 「订单金额 = 购物袋金额」要在真实 HTTP 链路的两端被断言（页面接口 ↔ 下单接口）；
//   * ORDER_CREATE 的待确认动作只能由真实模型累出来（助手标题 Dors工具调用），
//     这里用真实 deepseek 服务端做端到端验证，不 mock。
//
// 用法：
//   node tests/checkout-hitl-smoke.cjs
//   MALL_URL=http://127.0.0.1:8080 node tests/checkout-hitl-smoke.cjs
const BASE = process.env.MALL_URL || 'http://127.0.0.1:8080';
const USER = process.env.MALL_USER || 'student';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');

let passed = 0;
let failed = 0;
let skipped = 0;

function check(name, ok, detail) {
  if (ok) {
    passed++;
    console.log(`  ✓ ${name}${detail ? ' → ' + detail : ''}`);
  } else {
    failed++;
    console.log(`  ✗ ${name}${detail ? ' → ' + detail : ''}`);
  }
}
function skip(name, reason) {
  skipped++;
  console.log(`  ⊘ ${name} → SKIP：${reason}`);
}

// ——————————————————— 会话与通用请求 ———————————————————

function newSession() {
  let jsession = null;
  const absorb = (res) => {
    const list = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
    for (const c of list) {
      const m = /^JSESSIONID=([^;]*)/.exec(c);
      if (m) jsession = m[1];
    }
  };
  return { absorb, cookie: () => (jsession ? `JSESSIONID=${jsession}` : '') };
}

async function login(username) {
  const s = newSession();
  const page = await fetch(BASE + '/login', { redirect: 'manual' });
  s.absorb(page);
  const html = await page.text();
  const csrf = /name="_csrf"\s+value="([^"]+)"/.exec(html)?.[1];
  if (!csrf) throw new Error('登录页未取到 CSRF token');
  const res = await fetch(BASE + '/login', {
    method: 'POST',
    redirect: 'manual',
    headers: { 'content-type': 'application/x-www-form-urlencoded', cookie: s.cookie() },
    body: new URLSearchParams({ username, password: 'learn12345', _csrf: csrf }),
  });
  s.absorb(res);
  if (res.status !== 302 && res.status !== 200) throw new Error('登录失败 status=' + res.status);
  const assistant = await fetch(BASE + '/assistant', { headers: { cookie: s.cookie() } });
  const ahtml = await assistant.text();
  const token = /name="_csrf"\s+content="([^"]+)"/.exec(ahtml)?.[1] || csrf;
  const header = /name="_csrf_header"\s+content="([^"]+)"/.exec(ahtml)?.[1] || 'X-CSRF-TOKEN';
  return { s, headers: { cookie: s.cookie(), [header]: token, 'content-type': 'application/json' } };
}

async function api(sess, p, options = {}) {
  // 注意顺序：调用方补的头（如 Idempotency-Key）不能被会话头覆盖回去。
  const headers = { ...sess.headers, ...(options.headers || {}) };
  const res = await fetch(BASE + p, { ...options, headers });
  const text = await res.text();
  let body = null;
  try { body = JSON.parse(text); } catch { /* 空响应体 */ }
  return { status: res.status, body, raw: text };
}

const cartView = (sess) => api(sess, '/api/cart');
const cartClear = (sess) => api(sess, '/api/cart', { method: 'DELETE' });
const cartAdd = (sess, productId, quantity) =>
    api(sess, '/api/cart/items', { method: 'POST', body: JSON.stringify({ productId, quantity }) });

function createOrder(sess, items) {
  return api(sess, '/api/orders', {
    method: 'POST',
    headers: { ...sess.headers, 'Idempotency-Key': 'g1-smoke-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8) },
    body: JSON.stringify({ items }),
  });
}

const orderCount = async (sess) => {
  const r = await api(sess, '/api/orders');
  return Array.isArray(r.body) ? r.body.length : -1;
};

// ——————————————————— 促销规则：从 application.yml 读，不写死 ———————————————————

/** 极简 YAML 解析：只取 mall.shopping 下的门槛/运费/满减档位，避免引第三方依赖。 */
function readShoppingRules() {
  const root = path.resolve(__dirname, '..');
  const yml = fs.readFileSync(path.join(root, 'src/main/resources/application.yml'), 'utf8');
  const block = /mall:\s*\n(?:.|\n)*?  shopping:\s*\n((?:    [^\n]*\n|\n)*)/.exec(yml);
  const shopping = block ? block[1] : '';
  const num = (key, fallback) => {
    const m = new RegExp(key + ':\\s*([\\d.]+)').exec(shopping);
    return m ? Number(m[1]) : fallback;
  };
  const tiers = [];
  const tierRe = /-\s*threshold:\s*([\d.]+)\s*\n\s*discount:\s*([\d.]+)/g;
  let m;
  while ((m = tierRe.exec(shopping)) !== null) tiers.push({ threshold: Number(m[1]), discount: Number(m[2]) });
  tiers.sort((a, b) => a.threshold - b.threshold);
  return {
    freeShipping: num('free-shipping-threshold', 99),
    fee: num('shipping-fee', 8),
    tiers,
  };
}

/** 照 CartPricing 的规则算一遍期望值，用于和接口返回的价格对账。 */
function quote(subtotal, rules) {
  let discount = 0;
  for (let i = rules.tiers.length - 1; i >= 0; i--) {
    if (subtotal >= rules.tiers[i].threshold) { discount = rules.tiers[i].discount; break; }
  }
  const after = subtotal - discount;
  const shipping = after >= rules.freeShipping ? 0 : rules.fee;
  return { subtotal, discount, shipping, payable: after + shipping };
}

const money = (n) => Number(n).toFixed(2);
const amountOf = (o, field) => (o && o[field] !== undefined && o[field] !== null ? Number(o[field]) : null);

/** 逐项比对「购物袋 ↔ 订单」的四个金额，返回不一致项的可读串（空串表示完全一致）。 */
function diffAmounts(cart, order) {
  const pairs = [['subtotal', 'subtotal'], ['discount', 'discount'],
                 ['shippingFee', 'shippingFee'], ['total', 'payAmount']];
  const bad = [];
  for (const [c, o] of pairs) {
    const cv = amountOf(cart, c);
    const ov = amountOf(order, o);
    if (cv === null || ov === null || Math.abs(cv - ov) > 0.005) bad.push(`${c}:袋 ${cv} vs 单 ${ov}`);
  }
  return bad.join('，');
}

/**
 * G1 正向验证：同一个商品组合，购物袋试算与下单结果必须逐项一致。
 * @param label 用例名
 * @param items 商品行
 */
async function checkoutCase(sess, label, items) {
  await cartClear(sess);
  let addStatus = 200;
  for (const it of items) {
    const r = await cartAdd(sess, it.productId, it.quantity);
    if (r.status !== 200) addStatus = r.status;
  }
  const cartRes = await cartView(sess);
  const cart = cartRes.body;
  if (addStatus !== 200 || cart == null) {
    check(`[${label}] 加购并取到购物袋`, false, `status=${addStatus} raw=${cartRes.raw.slice(0, 160)}`);
    return null;
  }
  check(`[${label}] 取到购物袋试算`, true,
      `subtotal=${money(cart.subtotal)} discount=${money(cart.discount)} `
      + `shippingFee=${money(cart.shippingFee)} total=${money(cart.total)}`);

  const created = await createOrder(sess, items);
  const order = created.body;
  if (created.status !== 200 || order == null || !order.id) {
    check(`[${label}] 下单成功`, false, `status=${created.status} raw=${created.raw.slice(0, 200)}`);
    return null;
  }
  check(`[${label}] 下单成功`, true,
      `payAmount=${money(order.payAmount)} subtotal=${money(order.subtotal)} `
      + `discount=${money(order.discount)} shippingFee=${money(order.shippingFee)}`);

  const bad = diffAmounts(cart, order);
  check(`[${label}] 订单金额与购物袋逐项一致`, bad === '', bad || '四列全对得上');

  const identity = amountOf(order, 'subtotal') - amountOf(order, 'discount') + amountOf(order, 'shippingFee');
  check(`[${label}] payAmount = subtotal − discount + shippingFee`,
      Math.abs(identity - amountOf(order, 'payAmount')) <= 0.005,
      `${money(identity)} vs ${money(order.payAmount)}`);
  check(`[${label}] 历史列 total 与 payAmount 同源`,
      Math.abs(amountOf(order, 'total') - amountOf(order, 'payAmount')) <= 0.005,
      `total=${money(order.total)} payAmount=${money(order.payAmount)}`);
  return { cart, order };
}

// ——————————————————— HITL：ORDER_CREATE 确认前后 ———————————————————

async function newConversation(sess) {
  const r = await api(sess, '/api/assistant/conversations', { method: 'POST', body: '{}' });
  return r.body && r.body.id;
}

async function askAssistant(sess, cid, message) {
  const t0 = Date.now();
  const r = await api(sess, `/api/assistant/conversations/${cid}/messages`, {
    method: 'POST', body: JSON.stringify({ message }),
  });
  return { status: r.status, body: r.body, raw: r.raw, ms: Date.now() - t0 };
}

/** 让模型发起下单确认：直接问一次，拿不到再从「会话待确认」接口兜一次。 */
async function requestOrderCreate(sess, cid) {
  const phrases = ['帮我下单，把购物袋里的东西买了', '就买袋子里的这些，帮我结算'];
  let last = null;
  for (const p of phrases) {
    last = await askAssistant(sess, cid, p);
    if (last.status === 200 && last.body && last.body.pendingAction) return { source: 'message', ...last };
  }
  const pending = await api(sess, `/api/assistant/conversations/${cid}/pending-actions`);
  const hit = (pending.body || []).find((a) => a.kind === 'ORDER_CREATE');
  if (hit) return { source: 'pending-list', body: { pendingAction: hit }, raw: JSON.stringify(hit) };
  return { source: 'none', ...last };
}

async function main() {
  console.log('金额口径统一 + 助手下单闭环 · 真机冒烟 (' + BASE + ')\n');
  const rules = readShoppingRules();
  console.log(`促销档位（读自 application.yml）：门槛 ${rules.freeShipping} / 运费 ${rules.fee} / `
      + rules.tiers.map((t) => `满${t.threshold}减${t.discount}`).join(' '));

  const sess = await login(USER);
  const products = (await fetch(BASE + '/api/products').then((r) => r.json()));
  const price = new Map(products.map((p) => [p.id, Number(p.price)]));
  const stock = new Map(products.map((p) => [p.id, p.stock]));
  console.log(`商品：${products.map((p) => `#${p.id} ¥${money(p.price)}(库存${p.stock})`).join(' ')}\n`);

  // ——— 用例 1：满减 + 包邮（最高档 199 → 减 25）———
  console.log('[1] 满减 + 包邮');
  const big = products.filter((p) => p.stock >= 1)
      .map((p) => ({ subtotal: Number(p.price), items: [{ productId: p.id, quantity: 1 }] }))
      .find((c) => c.subtotal >= rules.tiers[rules.tiers.length - 1].threshold);
  await cartClear(sess);
  if (big) {
    const expect = quote(big.subtotal, rules);
    for (const it of big.items) await cartAdd(sess, it.productId, it.quantity);
    const cart = (await cartView(sess)).body;
    check('购物袋试算符合规则表',
        Math.abs(Number(cart.discount) - expect.discount) <= 0.005
        && Math.abs(Number(cart.shippingFee) - expect.shipping) <= 0.005,
        `期望 discount=${expect.discount} shipping=${expect.shipping}，实收 discount=${cart.discount} shipping=${cart.shippingFee}`);
    const created = await createOrder(sess, big.items);
    const order = created.body;
    if (created.status !== 200 || !order || !order.id) {
      check('[1] 下单成功', false, `status=${created.status} raw=${created.raw.slice(0, 200)}`);
    } else {
      check('订单 shippingFee 包邮时为 0', Math.abs(Number(order.shippingFee)) <= 0.005,
          `shippingFee=${order.shippingFee}`);
      check('订单 payAmount 与购物袋 total 一致',
          Math.abs(Number(order.payAmount) - Number(cart.total)) <= 0.005,
          `袋 total=${cart.total} vs 单 payAmount=${order.payAmount}`);
      check('订单四列金额与购物袋逐项一致', diffAmounts(cart, order) === '',
          diffAmounts(cart, order) || '全对得上');
    }
  } else {
    skip('满减 + 包邮', '没有单价达到最高档门槛且仍有库存的商品');
  }

  // ——— 用例 2：满减后跌破包邮门槛 ———
  console.log('\n[2] 满减后跌破包邮门槛（运费必须被算进去）');
  const ids = products.map((p) => p.id);
  let combo = null;
  for (const a of ids) {
    for (const b of ids) {
      for (const qa of b === a ? [1] : [1, 2]) {
        const qb = 1;
        const items = b === a ? [{ productId: a, quantity: qa }] : [{ productId: a, quantity: qa }, { productId: b, quantity: qb }];
        if (items.every((i) => stock.get(i.productId) >= i.quantity)) {
          const subtotal = items.reduce((s, i) => s + price.get(i.productId) * i.quantity, 0);
          const q = quote(subtotal, rules);
          if (q.discount > 0 && q.shipping > 0) { combo = { items, subtotal, expect: q }; break; }
        }
      }
      if (combo) break;
    }
    if (combo) break;
  }
  if (!combo) {
    skip('满减后跌破包邮门槛', '库存内凑不出「满减后低于包邮门槛」的组合');
  } else {
    await cartClear(sess);
    for (const it of combo.items) await cartAdd(sess, it.productId, it.quantity);
    const cart = (await cartView(sess)).body;
    const created = await createOrder(sess, combo.items);
    const order = created.body;
    console.log(`  组合 ${combo.items.map((i) => `#${i.productId}×${i.quantity}`).join(' + ')} `
        + `小计 ${money(combo.subtotal)} → 期望 减${combo.expect.discount} 运费${combo.expect.shipping} 应付${money(combo.expect.payable)}`);
    check('购物袋：满减后跌破门槛，运费被收取', Number(cart.shippingFee) > 0,
        `subtotal=${cart.subtotal} discount=${cart.discount} shippingFee=${cart.shippingFee}`);
    if (created.status === 200 && order && order.id) {
      check('订单 shippingFee 不为 0（运费真的落库了）', Number(order.shippingFee) > 0,
          `shippingFee=${order.shippingFee}，期望 ${combo.expect.shipping}`);
      check('订单 shippingFee 等于规则里的运费',
          Math.abs(Number(order.shippingFee) - combo.expect.shipping) <= 0.005,
          `${order.shippingFee} vs ${combo.expect.shipping}`);
      check('订单与购物袋四列金额一致', diffAmounts(cart, order) === '',
          diffAmounts(cart, order) || `payAmount=${order.payAmount} = 袋 total=${cart.total}`);
    } else {
      check('创建「跌破门槛」订单', false, `status=${created.status} raw=${created.raw.slice(0, 200)}`);
    }
  }

  // ——— 用例 3：助手下单闭环 —— 确认前后订单行数 ———
  console.log('\n[3] 助手 ORDER_CREATE：确认前零副作用 / 确认后 +1');
  await cartClear(sess);
  const seedProduct = products.find((p) => p.stock >= 2) || products[0];
  const addRes = await cartAdd(sess, seedProduct.id, 1);
  if (addRes.status !== 200) {
    skip('助手下单闭环', `加购失败 status=${addRes.status} raw=${addRes.raw.slice(0, 160)}`);
  } else {
    const cid = await newConversation(sess);
    const before = await orderCount(sess);
    const cartBefore = (await cartView(sess)).body;
    console.log(`  会话 ${cid} · 购物袋 ${cartBefore.itemCount} 件/应付 ${money(cartBefore.total)} · 当前订单数 ${before}`);

    const asked = await requestOrderCreate(sess, cid);
    const pending = asked.body && asked.body.pendingAction;
    if (!pending || pending.kind !== 'ORDER_CREATE') {
      const reply = asked.body && asked.body.reply ? asked.body.reply.content : '';
      check('模型发起 ORDER_CREATE 待确认动作', false,
          `来源=${asked.source} status=${asked.status} kind=${pending ? pending.kind : '无'} `
          + `reply=${String(reply).slice(0, 160)} raw=${String(asked.raw).slice(0, 200)}`);
    } else {
      check('模型发起 ORDER_CREATE 待确认动作', true,
          `actionId=${String(pending.id).slice(0, 8)} kind=${pending.kind} amount=${pending.amount} (${asked.ms}ms)`);
      const midCount = await orderCount(sess);
      const midCart = (await cartView(sess)).body;
      check('确认之前订单行数不变', midCount === before, `${before} → ${midCount}`);
      check('确认之前购物袋未被清空', midCart.itemCount === cartBefore.itemCount,
          `${cartBefore.itemCount} → ${midCart.itemCount}`);

      const confirmed = await api(sess, `/api/assistant/pending-actions/${pending.id}/confirm`, { method: 'POST' });
      const action = confirmed.body && confirmed.body.action;
      if (confirmed.status !== 200 || !action) {
        check('本人确认下单', false, `status=${confirmed.status} raw=${confirmed.raw.slice(0, 240)}`);
      } else {
        check('本人确认后动作迁移为 CONFIRMED', action.status === 'CONFIRMED',
            `status=${action.status} kind=${action.kind}`);
        const afterCount = await orderCount(sess);
        check('确认之后订单行数 +1', afterCount === before + 1, `${before} → ${afterCount}（期望 ${before + 1}）`);
        const afterCart = (await cartView(sess)).body;
        check('同一事务内购物袋被清空', afterCart.itemCount === 0, `剩余 ${afterCart.itemCount} 件`);
        const newest = (await api(sess, '/api/orders')).body;
        const mine = Array.isArray(newest) ? newest[0] : null;
        if (mine) {
          check('新订单应付金额 = 购物袋当时承诺的金额',
              Math.abs(Number(mine.payAmount) - Number(cartBefore.total)) <= 0.005,
              `袋 total=${cartBefore.total} vs 单 payAmount=${mine.payAmount}`);
        }
        check('（对照）重复确认被一次性闸门拦下',
            (await api(sess, `/api/assistant/pending-actions/${pending.id}/confirm`, { method: 'POST' })).status === 409,
            '第二次确认返回 409 ALREADY_RESOLVED');
      }
    }
  }

  // ——— 用例 4：前端静态资源语法 ———
  console.log('\n[4] 前端 JS 语法检查（node --check）');
  for (const rel of ['src/main/resources/static/js/app.js', 'src/main/resources/static/js/assistant.js']) {
    const file = path.resolve(__dirname, '..', rel);
    const name = rel.split('/').pop();
    // 首选官方做法：node --check。某些沙箱里再 spawn 一个 node 会被拦（EBUSY），
    // 此时退回 vm 编译——只解析不执行，同样能抓语法错误（这两个文件是纯脚本，无 ESM 语法）。
    let how = 'node --check';
    let err = null;
    try {
      execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' });
    } catch (e) {
      err = e;
      how = 'vm.Script';
      try {
        new vm.Script(fs.readFileSync(file, 'utf8'), { filename: file });
        err = null;
      } catch (e2) {
        err = e2;
      }
    }
    if (err) {
      check(name + ' 无 JS 语法错误', false,
          `${how} 失败：${String(err.stderr || err.message).slice(0, 300)}`);
    } else {
      check(name + ' 无 JS 语法错误', true, `（${how}）`);
    }
  }

  console.log(`\n通过 ${passed} 项，失败 ${failed} 项，跳过 ${skipped} 项`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('冒烟执行失败：', e && e.stack ? e.stack : e);
  process.exit(1);
});
