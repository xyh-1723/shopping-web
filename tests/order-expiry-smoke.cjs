// 真实容器下的「订单超时自动关闭」冒烟（不依赖浏览器，纯 fetch + 手工 cookie/CSRF）。
//
// 为什么需要它：单元测试里时间是被 mock 掉的，能证明规则正确，但证明不了
//   * V10/V11 迁移在真实持久化库上跑得通（内存库跑通不算数）；
//   * 真实 Tomcat 里的 @Scheduled 真的会把到期的订单关掉；
//   * 关单 → 归还库存 → 弃单召回 这条链在真实事务提交后真的连得上。
//
// 用法（支付时限压到 1 分钟，扫描间隔 5 秒，否则要等 15 分钟）：
//   java -jar target/learning-mall-1.0.0.jar --server.port=8097 \
//     --mall.order.payment-timeout-minutes=1 --mall.order.auto-close-scan-delay-ms=5000 \
//     --mall.order.auto-close-initial-delay-ms=8000
//   NO_PROXY='*' MALL_URL=http://127.0.0.1:8097 node tests/order-expiry-smoke.cjs
const BASE = process.env.MALL_URL || 'http://127.0.0.1:8097';
const PRODUCT_ID = Number(process.env.PRODUCT_ID || 1);

let passed = 0;
let failed = 0;
function check(name, ok, detail) {
  if (ok) {
    passed++;
    console.log(`  ✓ ${name}${detail ? ' → ' + detail : ''}`);
  } else {
    failed++;
    console.log(`  ✗ ${name}${detail ? ' → ' + detail : ''}`);
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

const json = async (res) => {
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { raw: text }; }
};

async function main() {
  console.log('订单超时自动关闭 · 真机冒烟 (' + BASE + ')\n');
  const student = await login('student');

  const listProducts = async () => json(await fetch(BASE + '/api/products'));
  const stockOf = async () => (await listProducts()).find((p) => p.id === PRODUCT_ID).stock;

  const before = await stockOf();
  console.log(`[1] 下单前库存 ${before}`);

  const created = await fetch(BASE + '/api/orders', {
    method: 'POST',
    headers: { ...student.headers, 'Idempotency-Key': 'expiry-smoke-' + Date.now() },
    body: JSON.stringify({ items: [{ productId: PRODUCT_ID, quantity: 1 }] }),
  });
  const order = await json(created);
  check('下单成功', created.status === 200 && order.status === 'PENDING', `order=${String(order.id).slice(0, 8)}`);
  check('下单即预占库存', (await stockOf()) === before - 1, `${before} → ${before - 1}`);
  check('返回了支付截止时间', Boolean(order.expiresAt), String(order.expiresAt));

  // 支付时限被压到 1 分钟，扫描间隔 5 秒：等它自己关掉，不手动触发。
  const deadline = Date.now() + 95_000;
  let view = order;
  while (Date.now() < deadline) {
    await sleep(5000);
    view = await json(await fetch(BASE + `/api/orders/${order.id}`, { headers: student.headers }));
    if (view.status !== 'PENDING') break;
  }
  check('超时后被自动关闭', view.status === 'CANCELLED', `status=${view.status}`);
  check('关闭原因记为超时', view.closeReason === 'TIMEOUT', `closeReason=${view.closeReason}`);
  check('记录了关闭时间', Boolean(view.closedAt), String(view.closedAt));

  const after = await stockOf();
  check('库存已归还', after === before, `${before - 1} → ${after}`);

  const pay = await fetch(BASE + `/api/orders/${order.id}/pay`, {
    method: 'POST',
    headers: student.headers,
    body: JSON.stringify({ method: 'DEMO_CARD' }),
  });
  const payBody = await json(pay);
  check('再支付时明确告知超时', pay.status === 409 && payBody.code === 'ORDER_EXPIRED',
      `${pay.status} ${payBody.code || ''} ${payBody.message || ''}`);

  const alerts = await json(await fetch(BASE + '/api/alerts', { headers: student.headers }));
  const recall = (alerts.alerts || []).find((a) => a.title && a.title.includes('超时'));
  check('生成了弃单召回提醒', Boolean(recall), recall ? recall.title : '未找到');
  if (recall) check('召回里说明东西还在', recall.content.includes('还有'), recall.content.slice(0, 60) + '…');

  // 顺带确认：用户主动取消的订单不会被召回（同一用户的这两类订单不能混）。
  const manual = await json(await fetch(BASE + '/api/orders', {
    method: 'POST',
    headers: { ...student.headers, 'Idempotency-Key': 'expiry-smoke-manual-' + Date.now() },
    body: JSON.stringify({ items: [{ productId: PRODUCT_ID, quantity: 1 }] }),
  }));
  const cancelled = await json(await fetch(BASE + `/api/orders/${manual.id}/cancel`, {
    method: 'POST', headers: student.headers }));
  check('主动取消仍记为 USER', cancelled.closeReason === 'USER', `closeReason=${cancelled.closeReason}`);

  const finalStock = await stockOf();
  check('主动取消也归还库存', finalStock === before, `库存 ${finalStock}`);

  console.log(`\n通过 ${passed} 项，失败 ${failed} 项`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('冒烟执行失败：', e.message);
  process.exit(1);
});
