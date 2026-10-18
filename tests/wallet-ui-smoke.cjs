// 钱包可见性的真机冒烟（不依赖浏览器，纯 fetch + 手工 cookie/CSRF）。
//
// 为什么需要它：WalletControllerTest 跑在 MockMvc 上，能证明「端点的状态码与 JSON 结构对」，
// 但证明不了真实 Tomcat 上：
//   1) 页面真的能加载，且钱包区块真的挂在了页面上；
//   2) 支付下拉框的第一项真的是钱包（这是静态资源，MockMvc 看不到它）；
//   3) 用钱包付一笔订单，余额真的减少、流水里真的多一条 PAYMENT（钱从哪儿出去的）。
//
// 关于浏览器：项目里 tests/quote-price-change-smoke.cjs 需要 @playwright/test + 一个真实浏览器，
// 离线环境下（无 node_modules、无浏览器缓存）跑不起来。本脚本用「取页面 HTML + 取 /js/app.js
// 原文」的方式覆盖同一批断言，代价是不覆盖渲染后的 DOM——这个取舍写在下面第 6 组里。
//
// 用法：
//   java -jar target/learning-mall-1.0.0.jar --server.port=8094
//   MALL_URL=http://127.0.0.1:8094 node tests/wallet-ui-smoke.cjs
const BASE = process.env.MALL_URL || 'http://127.0.0.1:8094';
const PASSWORD = process.env.MALL_PASSWORD || 'learn12345';

const results = [];

function check(number, ok, detail) {
  results.push({ number, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${number} ${detail}`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

/** 极简会话：只需要 JSESSIONID，所以不做完整 cookie jar。 */
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
  assert(csrf, '登录页未取到 CSRF token');

  const body = new URLSearchParams({ username, password: PASSWORD, _csrf: csrf });
  const res = await fetch(BASE + '/login', {
    method: 'POST',
    redirect: 'manual',
    headers: { 'content-type': 'application/x-www-form-urlencoded', cookie: s.cookie() },
    body,
  });
  s.absorb(res); // 登录会换 session id（防会话固定），必须重新吸收
  assert(res.status === 302 || res.status === 200, '登录失败 status=' + res.status);
  return s;
}

/** 已登录会话的 JSON 请求头：cookie + CSRF（CSRF 从首页的 meta 里取，与页面同一来源）。 */
async function jsonHeaders(s) {
  const html = await fetch(BASE + '/', { headers: { cookie: s.cookie() } }).then((r) => r.text());
  const token = /name="_csrf"\s+content="([^"]+)"/.exec(html)?.[1];
  const header = /name="_csrf_header"\s+content="([^"]+)"/.exec(html)?.[1] || 'X-CSRF-TOKEN';
  assert(token, '首页未取到 CSRF token');
  return { cookie: s.cookie(), [header]: token, 'content-type': 'application/json' };
}

async function api(headers, path, options = {}) {
  // 三个点号的顺序是坑：写成 { ...options, headers } 会让调用方额外传的
  // Idempotency-Key 被整体覆盖掉，下单接口因此拿不到幂等键、返回 400。
  const r = await fetch(BASE + path, { ...options, headers: { ...headers, ...options.headers } });
  const text = await r.text();
  let body = null;
  try { body = JSON.parse(text); } catch { /* 允许空响应体 */ }
  return { status: r.status, body, raw: text };
}

(async () => {
  try {
    // ── 1/6 应用真的起来了：匿名首页 200，且页面里有钱包挂载点 ──
    const home = await fetch(BASE + '/');
    const homeHtml = await home.text();
    check('1/6', home.status === 200 && homeHtml.includes('小小商城'),
      `GET / → ${home.status}，首页含「小小商城」=${homeHtml.includes('小小商城')}`);

    // ── 2/6 匿名查钱包必须是 401，不能是 500（更不能被当成「余额 0」） ──
    const anon = await fetch(BASE + '/api/wallet');
    const anonBody = await anon.text();
    check('2/6', anon.status === 401,
      `匿名 GET /api/wallet → ${anon.status}（期望 401，绝不能是 500）；响应=${anonBody.trim().slice(0, 60)}`);

    // ── 3/6 静态资源：支付下拉框第一项是钱包，且钱包区块真的被写进了 app.js ──
    //    这一组 MockMvc 覆盖不到：app.js 是静态文件，只有把它取回来读原文才能验证顺序。
    const appJs = await fetch(BASE + '/js/app.js').then((r) => r.text());
    const dropdown = /<select aria-label="模拟支付方式"[^>]*>(.*?)<\/select>/.exec(appJs)?.[1] || '';
    const firstMethod = /value="(DEMO_[A-Z]+)"/.exec(dropdown)?.[1] || '(未找到)';
    // 钱包格在模板里是 class="wallet-strip hidden"，而 .hidden 是 display:none !important。
    // 没有浏览器就拿不到计算样式，这里退一步检查「拿到数据后自己摘掉 hidden」这一句确实存在——
    // 它比「接口通不通」更容易被漏掉：接口全绿，页面上却一个字都看不见。
    const unhidesWallet =
      /const box = \$\("#wallet"\);[\s\S]{0,600}?classList\.remove\("hidden"\)/.test(appJs);
    check('3/6', firstMethod === 'DEMO_WALLET' && appJs.includes('/api/wallet') && unhidesWallet,
      `下拉框第一项=${firstMethod}；app.js 引用 /api/wallet=${appJs.includes('/api/wallet')}；` +
      `渲染后摘掉 hidden=${unhidesWallet}`);

    // ── 4/6 已登录能查到余额结构（分 + 元 + 开户流水） ──
    const s = await login('student');
    const headers = await jsonHeaders(s);
    const first = await api(headers, '/api/wallet');
    const balance = first.body?.balance;
    check('4/6', first.status === 200 && typeof balance?.balanceCents === 'number'
      && balance.recent === undefined && Array.isArray(first.body?.recent),
      `GET /api/wallet → ${first.status}，username=${balance?.username}，` +
      `balanceCents=${balance?.balanceCents}，balanceYuan=${balance?.balanceYuan}，` +
      `流水 ${first.body?.recent?.length} 条，首条 reason=${first.body?.recent?.[0]?.reason}`);
    assert(first.status === 200, '查钱包失败，后续用例无从谈起');

    // ── 5/6 充值：余额真的增加 100.00 元，且最新一条流水就是这次 TOPUP ──
    const beforeTopUp = balance.balanceCents;
    const topUp = await api(headers, '/api/wallet/top-up', { method: 'POST' });
    const after = topUp.body?.balance?.balanceCents;
    check('5/6', topUp.status === 200 && after === beforeTopUp + 10000
      && topUp.body?.recent?.[0]?.reason === 'TOPUP',
      `POST /api/wallet/top-up → ${topUp.status}，余额 ${beforeTopUp} → ${after}（+${after - beforeTopUp} 分），` +
      `首条 reason=${topUp.body?.recent?.[0]?.reason}`);

    // ── 6/6 用钱包付一笔订单：钱真的从余额里出去，流水里多一条负的 PAYMENT ──
    await api(headers, '/api/cart/items', {
      method: 'POST',
      body: JSON.stringify({ productId: 1, quantity: 1 }),
    });
    const created = await api(headers, '/api/orders', {
      method: 'POST',
      headers: { ...headers, 'Idempotency-Key': 'wallet-ui-smoke-' + Date.now() },
      body: JSON.stringify({ items: [{ productId: 1, quantity: 1 }] }),
    });
    assert(created.status === 200, '下单失败 status=' + created.status);
    const orderId = created.body.id;
    const beforePay = (await api(headers, '/api/wallet')).body.balance.balanceCents;

    const paid = await api(headers, `/api/orders/${orderId}/pay`, {
      method: 'POST',
      body: JSON.stringify({ method: 'DEMO_WALLET' }),
    });
    const walletAfterPay = await api(headers, '/api/wallet');
    const entry = walletAfterPay.body.recent[0];
    check('6/6', paid.status === 200 && paid.body.paymentMethod === 'DEMO_WALLET'
      && entry.reason === 'PAYMENT' && entry.deltaCents < 0
      && walletAfterPay.body.balance.balanceCents === beforePay + entry.deltaCents,
      `订单 ${String(orderId).slice(0, 8)} 支付 → ${paid.status}，方式=${paid.body.paymentMethod}，` +
      `余额 ${beforePay} → ${walletAfterPay.body.balance.balanceCents}，` +
      `首条流水 ${entry.reason} ${entry.deltaCents} 分，orderId=${String(entry.orderId).slice(0, 8)}`);

    const passed = results.filter((r) => r.ok).length;
    console.log(`\n${passed}/${results.length} PASS`);
    process.exit(passed === results.length ? 0 : 1);
  } catch (error) {
    console.error('冒烟中断：' + error.message);
    process.exit(1);
  }
})();
