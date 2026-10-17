// 购物助手：会话列表 + 多轮对话 + 语音输入与播报。会话持久化在后端，按当前登录用户隔离。
const $ = (s) => document.querySelector(s);
const user = document.body.dataset.user;
const cartScope = `little-mall:${user || "guest"}:`;
let currentId = null;
let busy = false;
let toastTimer;
let voice = null;

// 加购走服务端：助手放进袋子里的商品，用户在首页购物袋里立刻能看到，反之亦然。
// 若仍写 localStorage，就会出现「助手说加好了，用户刷新页面什么都没有」的经典分裂。
async function addToCart(product) {
  const id = Number(product.id);
  try {
    await api("/api/cart/items", {
      method: "POST",
      body: JSON.stringify({ productId: id, quantity: 1 }),
    });
    toast(`已把「${product.name}」放进购物袋，去首页结算即可`);
  } catch (e) {
    toast(e.message || "加入购物袋失败，请稍后再试", true);
  }
}
function renderCards(toolData) {
  if (!toolData) return "";
  let products;
  try {
    products = JSON.parse(toolData);
  } catch {
    return "";
  }
  if (!Array.isArray(products) || !products.length) return "";
  return (
    '<div class="assistant-cards">' +
    products
      .map(
        (p) =>
          `<div class="assistant-card"><div class="assistant-card-main"><span class="assistant-card-name">${esc(p.name)}</span><span class="assistant-card-price">¥${Number(p.price).toFixed(2)}</span><span class="assistant-card-stock">${p.stock > 0 ? `现货 ${p.stock}` : "售罄"}</span></div>${p.reason ? `<div class="assistant-card-reason">${esc(p.reason)}</div>` : ""}<div class="assistant-card-actions"><a class="button outline small" href="/product/${p.id}" target="_blank" rel="noopener">看详情</a><button class="button outline small" data-add-card="${p.id}" ${p.stock === 0 ? "disabled" : ""}>加购物袋</button><button class="button outline small" data-watch-card="${p.id}">降价提醒</button><button class="button green small" data-checkout-card="${p.id}" ${p.stock === 0 ? "disabled" : ""}>去结算</button></div></div>`,
      )
      .join("") +
    "</div>"
  );
}

// 关注一件商品：降价或补货时系统会主动提醒。这是「有点想要但先不买」的出口，
// 也是助手能先开口（而不是永远等你问）的前提。
async function watchProduct(product) {
  try {
    await api("/api/watches", {
      method: "POST",
      body: JSON.stringify({ productId: Number(product.id) }),
    });
    toast(`开始帮你盯着「${product.name}」，降价或补货时提醒你`);
    await loadAlerts();
  } catch (e) {
    toast(e.message || "关注失败，请稍后再试", true);
  }
}

function toast(message, error = false) {
  const box = $("#toast");
  box.textContent = message;
  box.className = `toast${error ? " error" : ""}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.add("hidden"), 4500);
}
const csrfHeader = () => document.querySelector('meta[name="_csrf_header"]').content;
const csrfToken = () => document.querySelector('meta[name="_csrf"]').content;
async function api(path, options = {}) {
  const headers = {
    "Content-Type": "application/json",
    [csrfHeader()]: csrfToken(),
    ...options.headers,
  };
  const response = await fetch(path, {
    ...options,
    headers,
    signal: AbortSignal.timeout(30000),
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

// 解析一个 SSE 帧（形如 "event:tool\ndata:{...}"）。多条 data 行按 SSE 规范用换行拼接。
function parseSseFrame(raw) {
  let event = "message";
  const dataLines = [];
  for (const line of raw.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
  }
  if (!dataLines.length) return null;
  const payload = dataLines.join("\n");
  let data;
  try {
    data = JSON.parse(payload);
  } catch {
    data = payload;
  }
  return { event, data };
}

/**
 * 流式发送一轮消息，边收边把进度交给 onEvent。
 *
 * 为什么不用 EventSource：它只能发 GET、不能带自定义头，于是既传不了 CSRF 令牌，
 * 也传不了消息正文。所以这里用 fetch 读响应体，自己解析同样格式的 SSE 帧。
 * 真正「一次性返回」的终点（done / error）在这里被收敛成返回值与异常，
 * 调用方拿到的东西和旧的非流式接口完全一致——降级路径因此几乎是免费的。
 */
async function streamTurn(convId, text, onEvent, images = []) {
  // 图片与文字走同一个 JSON 正文：流式端点必须自带 CSRF 头与正文，
  // 若图片改走 multipart 就得为同一个动作维护两套序列化与两套错误处理。
  const body = () => JSON.stringify({
    message: text,
    images: images.map((item) => ({ mime: item.mime, data: item.data })),
  });
  const response = await fetch(`/api/assistant/conversations/${convId}/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json", [csrfHeader()]: csrfToken() },
    body: body(),
    signal: AbortSignal.timeout(120000),
  });
  if (response.status === 401) {
    location.href = "/login";
    throw new Error("请先登录");
  }
  // 非 200 或拿不到流（老代理、不支持 SSE 的环境）：回退到非流式接口，功能不打折，只是没有进度。
  if (!response.ok || !response.body) {
    return api(`/api/assistant/conversations/${convId}/messages`, {
      method: "POST",
      body: body(),
    });
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let done = null;
  let failure = null;
  while (true) {
    const { value, done: finished } = await reader.read();
    if (finished) break;
    buffer += decoder.decode(value, { stream: true });
    let boundary;
    while ((boundary = buffer.indexOf("\n\n")) >= 0) {
      const frame = parseSseFrame(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      if (!frame) continue;
      if (frame.event === "done") done = frame.data;
      else if (frame.event === "error") failure = frame.data;
      else onEvent(frame.event, frame.data);
    }
  }
  if (failure) throw new Error(failure.message || "助手暂时无法回答，请稍后再试");
  if (!done) throw new Error("连接中断了，请重试一次");
  return done;
}
const esc = (v) =>
  String(v ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
const time = (v) => String(v || "").replace("T", " ").slice(5, 16);

async function loadStatus() {
  try {
    const status = await api("/api/assistant/status");
    const badge = $("#assistant-status");
    badge.textContent = status.enabled ? "已启用" : "未启用";
    badge.classList.toggle("enabled", !!status.enabled);
    renderGuard(status);
  } catch {
    /* 忽略，徽标保持默认 */
  }
}

// 角色与能力清单：让用户在提问前就知道自己能问到什么，比事后被拒友好。
function renderGuard(status) {
  const staff = status.role === "STAFF";
  const role = $("#guard-role");
  role.textContent = staff ? "当前角色：STAFF · 店员" : "当前角色：USER · 顾客";
  role.classList.toggle("staff", staff);
  $("#guard-caps").textContent = (status.capabilities || []).join(" · ");
  $("#guard-shield").textContent =
    `输入 / 输出双层防护 · 限流 ${status.rateLimitPerMinute} 条/分钟`;
  if (staff) {
    $("#audit-panel").hidden = false;
    loadAudit();
  }
}

// 安全审计面板（仅店员）。记录的是「安全事件」，不是全部对话，所以条目少且有价值。
async function loadAudit() {
  try {
    const rows = await api("/api/assistant/audit?limit=20");
    $("#audit-list").innerHTML = rows.length
      ? rows
          .map(
            (r) =>
              `<div class="audit-row"><div class="audit-row-head"><span class="audit-action ${r.action === "ALLOW" ? "allow" : "block"}">${esc(r.action)}</span><span class="audit-rule">${esc(r.ruleId || "—")}</span><span class="audit-owasp">${esc(r.owasp || "")}</span></div><div class="audit-meta">${esc(r.username)} · ${esc(r.stage)} · ${time(r.createdAt)}</div><div class="audit-excerpt">${esc(r.excerpt || "")}</div></div>`,
          )
          .join("")
      : '<p class="empty">暂无安全事件。</p>';
  } catch (e) {
    $("#audit-list").innerHTML = `<p class="empty">${esc(e.message)}</p>`;
  }
}

async function loadConversations() {
  try {
    const list = await api("/api/assistant/conversations");
    $("#conversations").innerHTML = list.length
      ? list
          .map(
            (c) =>
              `<button class="conv-item${c.id === currentId ? " active" : ""}" data-conv="${c.id}"><span class="conv-item-main"><span class="conv-item-title">${esc(c.title)}</span><span class="conv-item-meta">${time(c.updatedAt)} · ${c.messageCount} 条</span></span><span class="conv-delete" data-delete="${c.id}" role="button" aria-label="删除会话">×</span></button>`,
          )
          .join("")
      : '<p class="empty">还没有会话，点「新会话」开始。</p>';
  } catch (e) {
    toast(e.message, true);
  }
}

// 记忆面板：让用户看到助手记住了什么，并能逐条删掉。
// 「可见 + 可删」是记忆功能的一部分——不可见的记忆会让人不安，而信任是复购的前提。
// source=AUTO 的条目标成「助手推断」：自动抽取的偏好必须一眼看得出是推断而不是用户原话，
// 否则用户会觉得「我没说过这个啊，它怎么知道」——这正是自动记忆最容易招致反感的地方。
async function loadMemory() {
  try {
    const rows = await api("/api/assistant/memory");
    $("#memory-list").innerHTML = rows.length
      ? rows
          .map((m) => {
            const inferred =
              m.source === "AUTO"
                ? '<span class="memory-source" title="助手根据以往对话推断，可能不准，可随时删除">助手推断</span>'
                : "";
            return `<div class="memory-row"><span class="memory-key">${esc(m.label || m.key)}</span><span class="memory-value">${esc(m.value)}${inferred}</span><button class="memory-forget" type="button" data-forget="${esc(m.key)}" title="忘记这一条" aria-label="忘记${esc(m.label || m.key)}">×</button></div>`;
          })
          .join("")
      : '<p class="empty">还没有记住任何偏好。聊到预算、用途、送给谁时，小拾会记下来。</p>';
  } catch (e) {
    $("#memory-list").innerHTML = `<p class="empty">${esc(e.message)}</p>`;
  }
}

// 提醒中心：助手「先开口」的地方。关注过的商品降价或补货，会在这里出现。
// 未读单独标出来，因为「有没有新提醒」是每次进页面都该看一眼的信息。
async function loadAlerts() {
  try {
    const center = await api("/api/alerts");
    const badge = $("#alert-unread");
    badge.hidden = !center.unread;
    badge.textContent = center.unread > 99 ? "99+" : String(center.unread);
    $("#alert-list").innerHTML = center.alerts.length
      ? center.alerts
          .map(
            (a) =>
              `<div class="alert-row${a.read ? "" : " unread"}"><div class="alert-row-head"><span class="alert-title">${esc(a.title)}</span><span class="alert-time">${time(a.createdAt)}</span></div><div class="alert-content">${esc(a.content)}</div><div class="alert-actions">${a.productId ? `<a class="alert-link" href="/product/${a.productId}" target="_blank" rel="noopener">去看看</a>` : ""}${a.read ? "" : `<button class="text-button" type="button" data-alert-read="${esc(a.id)}">知道了</button>`}</div></div>`,
          )
          .join("")
      : '<p class="empty">还没有提醒。关注商品后，降价与补货会出现在这里。</p>';
  } catch (e) {
    $("#alert-list").innerHTML = `<p class="empty">${esc(e.message)}</p>`;
  }
}

const SPEAK_ICON =
  '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9h3l4-3.5v13L7 15H4z" /><path d="M15.5 8.6a5 5 0 0 1 0 6.8" /></svg>';
const speakButton = () =>
  `<button class="speak-button" type="button" data-speak="1" title="朗读这条回复" aria-label="朗读这条回复">${SPEAK_ICON}朗读</button>`;
// 助手回复气泡（含商品卡片与朗读按钮），渲染历史与实时追加共用。
// decision 非 ALLOW 时加标记样式，让「被安全策略处理过」一眼可见。
// 追问的候选答案渲染成可点按钮：用户常常不想打字，只想点一下。
// 这些选项来自 requestClarification 工具，不是前端猜的——所以点下去必然是有意义的一步。
const askChips = (suggestions) =>
  suggestions && suggestions.length
    ? `<div class="ask-chips">${suggestions
        .map(
          (option) =>
            `<button class="ask-chip" type="button" data-ask="${esc(option)}">${esc(option)}</button>`,
        )
        .join("")}</div>`
    : "";
const assistantBubble = (content, toolData, decision, suggestions) =>
  `<div class="msg assistant${decision && decision !== "ALLOW" ? " guarded" : ""}"><span class="msg-role">小拾</span><div class="msg-bubble">${esc(content)}</div>${speakButton()}${renderCards(toolData)}${askChips(suggestions)}</div>`;

// ————— human-in-the-loop：确认卡片（支付 / 下单 / 取消）—————
// 模型只能「发起」，真正的执行由用户本人点这里的按钮触发（走独立的确认接口）。
//
// 三类动作共用一张卡片，但文案必须逐项区分：把「下单确认」也写成「支付确认」，
// 用户就会对确认麻木——而麻木的确认，等于没有确认。
const HITL_METHODS = { DEMO_CARD: "模拟银行卡", DEMO_WALLET: "模拟钱包" };
const HITL_KINDS = {
  ORDER_PAYMENT: {
    title: "支付确认",
    confirm: "确认支付",
    hint: "由助手发起，需你本人确认后才会支付",
    detail: (a) =>
      `订单 ${esc(String(a.orderId).slice(0, 8))} · ¥${Number(a.amount).toFixed(2)} · ${esc(HITL_METHODS[a.method] || a.method)}`,
    resolved: (ok) => (ok ? "已确认支付" : "已取消"),
    toast: (ok) => (ok ? "支付完成" : "已取消这笔支付"),
  },
  ORDER_CREATE: {
    title: "确认下单",
    confirm: "确认下单",
    hint: "由助手发起，需你本人确认后才会占用库存、生成订单",
    detail: (a) => `应付 ¥${Number(a.amount).toFixed(2)}`,
    resolved: (ok) => (ok ? "已下单" : "已取消"),
    toast: (ok) => (ok ? "下单成功" : "已取消这次下单"),
  },
  ORDER_CANCEL: {
    title: "取消确认",
    confirm: "确认取消",
    hint: "由助手发起，需你本人确认后才会关闭订单并归还库存",
    detail: (a) =>
      `订单 ${esc(String(a.orderId).slice(0, 8))} · ¥${Number(a.amount).toFixed(2)}`,
    resolved: (ok) => (ok ? "已取消订单" : "已保留"),
    toast: (ok) => (ok ? "订单已取消" : "已保留这笔订单"),
  },
};
const hitlKind = (kind) => HITL_KINDS[kind] || HITL_KINDS.ORDER_PAYMENT;

function renderHitlCard(action) {
  const k = hitlKind(action.kind);
  return `<div class="hitl-card" data-hitl-card="${esc(action.id)}" data-hitl-kind="${esc(action.kind || "ORDER_PAYMENT")}"><div class="hitl-card-main"><span class="hitl-card-title">${k.title}</span><span class="hitl-card-detail">${k.detail(action)}</span><span class="hitl-card-hint">${k.hint}</span></div><div class="hitl-card-actions"><button class="button green small" data-hitl-confirm="${esc(action.id)}">${k.confirm}</button><button class="button outline small" data-hitl-cancel="${esc(action.id)}">取消</button></div></div>`;
}

function markHitlResolved(card, label) {
  if (!card) return;
  card.classList.add("resolved");
  const actions = card.querySelector(".hitl-card-actions");
  if (actions) actions.innerHTML = `<span class="hitl-card-state">${esc(label)}</span>`;
}

async function resolveHitl(id, approve) {
  const card = document.querySelector(`[data-hitl-card="${id}"]`);
  const k = hitlKind(card?.dataset.hitlKind);
  card?.querySelectorAll("button").forEach((b) => (b.disabled = true));
  try {
    const result = await api(`/api/assistant/pending-actions/${id}/${approve ? "confirm" : "cancel"}`, {
      method: "POST",
    });
    markHitlResolved(card, k.resolved(approve));
    if (result.message) {
      const area = $("#messages");
      area.insertAdjacentHTML("beforeend", assistantBubble(result.message.content, null, "ALLOW"));
      area.scrollTop = area.scrollHeight;
      speakBubble(area.lastElementChild.querySelector(".msg-bubble"), false);
    }
    toast(k.toast(approve));
    if (!$("#audit-panel").hidden) loadAudit();
  } catch (e) {
    toast(e.message, true);
    // 已处理 / 已过期 / 不属于本人：卡片不再可操作。
    markHitlResolved(card, "已失效");
  }
}

// 页面刷新或切换会话后，找回仍未处理的确认卡片。
async function loadPendingActions(convId) {
  try {
    const list = await api(`/api/assistant/conversations/${convId}/pending-actions`);
    if (!list.length) return;
    const area = $("#messages");
    for (const action of list) area.insertAdjacentHTML("beforeend", renderHitlCard(action));
    area.scrollTop = area.scrollHeight;
  } catch {
    /* 忽略，确认卡片不阻断会话加载 */
  }
}

function renderMessages(messages) {
  $("#messages").innerHTML = messages.length
    ? messages
        .map((m) =>
          m.role === "ASSISTANT"
            ? assistantBubble(m.content, m.toolData)
            : `<div class="msg user"><span class="msg-role">我</span><div class="msg-bubble">${esc(m.content)}</div></div>`,
        )
        .join("")
    : '<div class="chat-empty"><div class="empty-bag">✦</div>说点什么开始对话吧。</div>';
  const area = $("#messages");
  area.scrollTop = area.scrollHeight;
}

// 从最近一次渲染的消息列表里按 id 找到商品对象，供加购物袋使用。
function findProductInMessages(productId) {
  const node = document.querySelector(`[data-add-card="${productId}"]`);
  if (!node) return null;
  const card = node.closest(".assistant-card");
  if (!card) return null;
  return {
    id: Number(productId),
    name: card.querySelector(".assistant-card-name")?.textContent || "商品",
    stock: Number(card.querySelector(".assistant-card-stock")?.textContent?.match(/\d+/)?.[0] || 0),
  };
}

function enableInput(on) {
  $("#chat-text").disabled = !on;
  $("#chat-send").disabled = !on;
  // 没有选中会话时同时收起录音，避免把话录进一个不存在的会话。
  if (!on) {
    $("#voice-mic").disabled = true;
    voice?.halt();
  } else {
    $("#voice-mic").disabled = false;
  }
}

async function selectConversation(id) {
  try {
    voice?.halt(); // 切会话时停止录音与朗读
    const detail = await api(`/api/assistant/conversations/${id}`);
    currentId = id;
    renderMessages(detail.messages);
    await loadPendingActions(id);
    enableInput(true);
    await loadConversations();
    $("#chat-text").focus();
  } catch (e) {
    toast(e.message, true);
  }
}

async function newConversation() {
  try {
    const conv = await api("/api/assistant/conversations", { method: "POST" });
    await selectConversation(conv.id);
  } catch (e) {
    toast(e.message, true);
  }
}

// ————— 语音：麦克风输入 + 回复播报 —————
// 组件实现见 voice.js；这里只做业务接线（录音文本进输入框、回复自动朗读）。
function initVoice() {
  if (!window.VoiceKit) return;
  voice = window.VoiceKit.mountVoiceUi({
    el: {
      mic: $("#voice-mic"),
      toolbar: $("#voice-toolbar"),
      toggle: $("#voice-toggle"),
      toggleText: $("#voice-toggle-text"),
      settings: $("#voice-settings"),
      voicePicker: $("#voice-picker"),
      ratePicker: $("#voice-rate"),
      preview: $("#voice-preview"),
      stop: $("#voice-stop"),
      transcript: $("#voice-transcript"),
      transcriptText: $("#voice-transcript-text"),
      timer: $("#voice-transcript-timer"),
      cancel: $("#voice-cancel"),
      hint: $("#voice-hint"),
    },
    storageKey: cartScope + "voice-broadcast",
    getInput: () => $("#chat-text"),
    onToast: (message, error) => toast(message, error),
    onBroadcastState: (state) => {
      // 朗读结束或被打断时移除气泡高亮。
      if (state !== "speaking") {
        document.querySelectorAll(".msg-bubble.speaking").forEach((node) => node.classList.remove("speaking"));
      }
    },
  });
}

// 朗读一个回复气泡。force=true 表示用户手动点朗读，此时忽略播报总开关。
function speakBubble(bubble, force) {
  if (!voice || !bubble) return;
  document.querySelectorAll(".msg-bubble.speaking").forEach((node) => node.classList.remove("speaking"));
  bubble.classList.add("speaking");
  if (!voice.broadcast.speak(bubble.textContent, force)) bubble.classList.remove("speaking");
}

function autoGrow(el) {
  el.style.height = "auto";
  el.style.height = Math.min(el.scrollHeight, 132) + "px";
}

// ————— 进度行：回答期间的「正在为你搜索…」 —————
// 助手一次回答常常十几秒（几轮模型 + 若干工具）。这十几秒里如果没有字在动，
// 用户唯一能做的判断就是「是不是卡了」——而绝大多数时候它其实在正常工作。
//
// 这一行同时承担两件事：工具阶段显示「正在搜索商品…」，模型开始出字时原地变成正文气泡。
// 不做成两行，是因为「小拾 正在思考…」和「小拾 正在输出回答」挨着出现时，
// 用户看到的是两个小拾在说话——那比没有进度更让人困惑。
function progressNode() {
  const node = document.createElement("div");
  node.className = "msg assistant progress-msg";
  node.innerHTML =
    '<span class="msg-role">小拾</span><div class="live-body"><div class="progress-line"><span class="progress-dot" aria-hidden="true"></span><span class="progress-text">正在理解你的需求…</span></div></div>';
  return node;
}
function setProgress(node, text) {
  const line = node?.querySelector(".progress-text");
  if (line && text && line.textContent !== text) line.textContent = text;
}

/**
 * 追加一段模型增量。气泡按需创建——没有增量就不会凭空多出一个空气泡。
 *
 * 用 textContent 追加而不是拼 HTML：增量是模型输出，必须当不可信文本处理；
 * 转义本来就由浏览器这层保证，一旦走 innerHTML 就打开了一个注入面。
 */
function appendToken(node, token) {
  const body = node.querySelector(".live-body");
  let bubble = node.querySelector(".msg-bubble");
  if (!bubble) {
    bubble = document.createElement("div");
    bubble.className = "msg-bubble";
    body.appendChild(bubble);
    node.classList.add("streaming");
  }
  // 上一轮的文字已经被降级成过程说明，说明模型已经动过工具了——现在这些字是新一轮的输出。
  // 清掉重来，否则最后一轮（也就是真正的答案）会顶着「过程说明」的灰字样式一路长到底，
  // 用户会以为助手在自言自语而不是在回答他。
  if (node.classList.contains("process")) {
    bubble.textContent = "";
    node.classList.remove("process");
  }
  bubble.textContent += token;
}

/**
 * 本轮开始调工具了：已经流出来的那些字不是最终答案（模型往往先说「我帮你查一下」再动手）。
 * 这里不做清空——清空会让界面闪一下，比留着更像故障；改成弱化呈现，
 * 等 done 带来权威文本时再整体替换。
 */
function demoteStreamedText(node) {
  if (node.querySelector(".msg-bubble")) node.classList.add("process");
}

/** 输出守卫在增量里命中：已流出的字不作数，立刻清掉。 */
function discardStreamedText(node) {
  const bubble = node.querySelector(".msg-bubble");
  if (bubble) bubble.textContent = "";
  setProgress(node, "回复已按安全策略处理…");
}
// 同一批工具并行执行时 start/end 会交错到达，这里不做聚合——进度只需要「有在动」，
// 强行排序反而会让文案来回跳。也绝不显示错误状态：用户不关心哪个工具失败了，
// 模型会拿结构化错误自纠，最终答案里自然会说清楚。
/** 阶段文案。stage 是「没有工具调用时的等待」，覆盖的是那些进度条会空转的窗口。 */
function stageTextFor(stage) {
  if (stage === "thinking") return "正在思考…";
  if (stage === "vision") return "正在看图片…";
  return "处理中…";
}

function progressTextFor(data) {
  const label = data.label || data.tool || "处理中";
  if (data.phase === "end") return `${label}…完成`;
  return `${label}…`;
}

/* ------------------------------------------------------------------ *
 * 以图搜款：图片选择与上传
 *
 * 前端这里做的每一件事都是为了「别把时间和钱浪费在传输上」——
 * 原图压缩后才发出去，服务端仍有自己的校验（前端校验只是体验，不是防线）。
 * ------------------------------------------------------------------ */

const MAX_IMAGES = 3;
const MAX_RAW_BYTES = 12 * 1024 * 1024; // 读进内存前的上限：再大浏览器就开始卡了
const MAX_SENT_BYTES = 4 * 1024 * 1024; // 与后端 max-bytes-per-image 保持一致
const THUMB_EDGE = 1024; // 压缩后的最长边

/** 待发送的图片：{mime, data}，data 是不带 data URL 前缀的 base64。 */
let pendingImages = [];

/**
 * 压缩一张图片，返回可直接上传的形态。
 *
 * <p>三件事，每一件都决定这个功能能不能用：
 * <ol>
 *   <li><b>缩放</b>：视觉模型在 1024px 以上几乎没有额外收益，而图片是按尺寸计费的；
 *   <li><b>统一转 JPEG</b>：手机直出图动辄 5-10MB，base64 后再涨 33%，
 *       一次请求传十几兆只是为了让模型看清一个杯子；
 *   <li><b>先铺白底</b>：PNG / WebP 的透明通道转 JPEG 会变成黑块。
 * </ol>
 * GIF 只取第一帧：找同款不需要动画，视觉模型也只看静态帧。
 */
async function compressImage(file) {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, THUMB_EDGE / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close?.();
  const dataUrl = canvas.toDataURL("image/jpeg", 0.85);
  const data = dataUrl.slice(dataUrl.indexOf(",") + 1);
  // base64 每 4 个字符编码 3 个字节，据此还原出真实体积再判断是否超限。
  if ((data.length * 3) / 4 > MAX_SENT_BYTES) {
    throw new Error("压缩后仍然超过 4MB");
  }
  return { mime: "image/jpeg", data };
}

async function addImageFiles(fileList) {
  const incoming = Array.from(fileList || []);
  if (!incoming.length) return;
  const problems = [];
  for (const file of incoming) {
    if (pendingImages.length >= MAX_IMAGES) {
      problems.push(`一次最多 ${MAX_IMAGES} 张，多的没有添加`);
      break;
    }
    if (!file.type || !file.type.startsWith("image/")) {
      problems.push(`${file.name || "该文件"}不是图片`);
      continue;
    }
    if (file.size > MAX_RAW_BYTES) {
      problems.push(`${file.name || "该图片"}超过 12MB`);
      continue;
    }
    try {
      pendingImages.push(await compressImage(file));
    } catch (error) {
      problems.push(`${file.name || "该图片"}处理失败（${error.message}）`);
    }
  }
  renderImageTray();
  if (problems.length) toast(problems.join("；"), true);
}

function removeImageAt(index) {
  pendingImages.splice(index, 1);
  renderImageTray();
}

function renderImageTray() {
  const tray = $("#image-tray");
  if (!tray) return;
  if (!pendingImages.length) {
    tray.hidden = true;
    tray.innerHTML = "";
    return;
  }
  tray.hidden = false;
  tray.innerHTML = pendingImages
    .map(
      (item, index) => `<span class="image-chip">
        <img src="data:${item.mime};base64,${item.data}" alt="待发送的第 ${index + 1} 张图片" />
        <button type="button" class="image-chip-remove" data-image-remove="${index}"
          aria-label="移除第 ${index + 1} 张图片">×</button>
      </span>`,
    )
    .join("");
}

async function sendMessage(text) {
  if (busy || !currentId) return;
  busy = true;
  voice?.halt(); // 发送时停止录音与上一次朗读
  $("#chat-send").disabled = true;
  const area = $("#messages");
  const empty = area.querySelector(".chat-empty");
  if (empty) empty.remove();
  // 取出后立即清空：预览区在请求发出那一刻就该消失，
  // 否则用户会以为图片还在「待发送」，可能再点一次发送。
  const images = pendingImages;
  pendingImages = [];
  renderImageTray();
  const thumbs = images
    .map((item) => `<img class="msg-image" src="data:${item.mime};base64,${item.data}" alt="我上传的图片" />`)
    .join("");
  area.insertAdjacentHTML(
    "beforeend",
    `<div class="msg user"><span class="msg-role">我</span><div class="msg-bubble">${esc(text)}${
      thumbs ? `<span class="msg-images">${thumbs}</span>` : ""
    }</div></div>`,
  );
  area.scrollTop = area.scrollHeight;
  $("#chat-text").value = "";
  autoGrow($("#chat-text"));
  const progress = progressNode();
  area.appendChild(progress);
  area.scrollTop = area.scrollHeight;
  try {
    const payload = await streamTurn(currentId, text, (event, data) => {
      if (event === "stage") setProgress(progress, stageTextFor(data?.stage));
      else if (event === "tool") {
        demoteStreamedText(progress);
        setProgress(progress, progressTextFor(data));
      } else if (event === "delta") appendToken(progress, data?.text || "");
      else if (event === "guard") discardStreamedText(progress);
      area.scrollTop = area.scrollHeight;
    }, images);
    // 后端返回 {reply, decision, notice, pendingAction, suggestions}；对旧格式做一次兼容。
    const reply = payload && payload.reply ? payload.reply : payload;
    const decision = (payload && payload.decision) || "ALLOW";
    const notice = payload && payload.notice;
    // 流式期间那个「进度行」已经变成了正文气泡，就地定稿：
    // 权威文本是服务端过完输出守卫的版本，可能和刚才逐字显示的不一样——所以一定要用它覆盖。
    const streamed = progress.querySelector(".msg-bubble");
    if (streamed) {
      // 拆掉包裹层并把状态类摘掉，DOM 就与一个普通助手气泡完全一致，
      // 后续的朗读、商品卡片、追问按钮都按既有路径工作。
      progress.classList.remove("progress-msg", "streaming", "process");
      progress.querySelector(".progress-line")?.remove();
      progress.querySelector(".live-body").replaceWith(streamed);
      if (decision && decision !== "ALLOW") progress.classList.add("guarded");
      progress.insertAdjacentHTML(
        "beforeend",
        `${speakButton()}${renderCards(reply.toolData)}${askChips(payload && payload.suggestions)}`,
      );
    } else {
      // 一个增量都没收到（模型全程只在调工具，或服务端没走流式）：退回原来的整段渲染。
      progress.remove();
      area.insertAdjacentHTML(
        "beforeend",
        assistantBubble(reply.content, reply.toolData, decision, payload && payload.suggestions),
      );
    }
    const bubbleNode = streamed ? progress : area.lastElementChild;
    // 本轮若产生了待确认动作（如支付确认），在回复下方渲染确认卡片。
    if (payload && payload.pendingAction) {
      area.insertAdjacentHTML("beforeend", renderHitlCard(payload.pendingAction));
    }
    area.scrollTop = area.scrollHeight;
    if (notice) toast(notice, decision === "BLOCK" || decision === "RATE");
    // 播报开启时才朗读；关闭时 speak() 直接返回 false，气泡高亮会被撤销。
    speakBubble(bubbleNode.querySelector(".msg-bubble"), false);
    await loadConversations();
    // 偏好是轮后异步抽取的，可能比这条回复晚一两秒才落库，所以延迟刷一下面板。
    setTimeout(loadMemory, 1500);
    // 这一轮里助手可能替你关注了商品、也可能读到了新提醒。
    loadAlerts();
    // 店员盯着审计面板时，自己发起的拦截也应该立刻出现在列表里。
    if (!$("#audit-panel").hidden) loadAudit();
  } catch (e) {
    progress.remove();
    toast(e.message, true);
  } finally {
    busy = false;
    $("#chat-send").disabled = false;
    $("#chat-text").focus();
  }
}

document.addEventListener("click", async (event) => {
  const alertRead = event.target.closest("[data-alert-read]");
  if (alertRead) {
    try {
      await api(`/api/alerts/${encodeURIComponent(alertRead.dataset.alertRead)}/read`, { method: "POST" });
      await loadAlerts();
    } catch (e) {
      toast(e.message, true);
    }
    return;
  }
  const forget = event.target.closest("[data-forget]");
  if (forget) {
    try {
      await api(`/api/assistant/memory/${encodeURIComponent(forget.dataset.forget)}`, {
        method: "DELETE",
      });
      toast("已忘记这条偏好");
      await loadMemory();
    } catch (e) {
      toast(e.message, true);
    }
    return;
  }
  const ask = event.target.closest("[data-ask]");
  if (ask) {
    // 点了快捷选项就当作用户输入了这句话；同一组按钮随即锁定，避免重复发送。
    ask
      .closest(".ask-chips")
      ?.querySelectorAll("button")
      .forEach((b) => (b.disabled = true));
    await sendMessage(ask.dataset.ask);
    return;
  }
  const del = event.target.closest("[data-delete]");
  if (del) {
    event.stopPropagation();
    const id = del.dataset.delete;
    if (!confirm("删除这个会话？")) return;
    try {
      await api(`/api/assistant/conversations/${id}`, { method: "DELETE" });
      if (id === currentId) {
        currentId = null;
        renderMessages([]);
        enableInput(false);
      }
      await loadConversations();
      toast("会话已删除");
    } catch (e) {
      toast(e.message, true);
    }
    return;
  }
  const addBtn = event.target.closest("[data-add-card]");
  if (addBtn) {
    const product = findProductInMessages(addBtn.dataset.addCard);
    if (product) await addToCart(product);
    return;
  }
  const checkoutBtn = event.target.closest("[data-checkout-card]");
  if (checkoutBtn) {
    const product = findProductInMessages(checkoutBtn.dataset.checkoutCard);
    if (product) {
      await addToCart(product);
      location.href = "/#catalog";
    }
    return;
  }
  const watchBtn = event.target.closest("[data-watch-card]");
  if (watchBtn) {
    const product = findProductInMessages(watchBtn.dataset.watchCard);
    if (product) await watchProduct(product);
    return;
  }
  const speakBtn = event.target.closest("[data-speak]");
  if (speakBtn) {
    speakBubble(speakBtn.closest(".msg")?.querySelector(".msg-bubble"), true);
    return;
  }
  const hitlConfirm = event.target.closest("[data-hitl-confirm]");
  if (hitlConfirm) {
    await resolveHitl(hitlConfirm.dataset.hitlConfirm, true);
    return;
  }
  const hitlCancel = event.target.closest("[data-hitl-cancel]");
  if (hitlCancel) {
    await resolveHitl(hitlCancel.dataset.hitlCancel, false);
    return;
  }
  const item = event.target.closest("[data-conv]");
  if (item) await selectConversation(item.dataset.conv);
});

$("#new-conversation").addEventListener("click", newConversation);
$("#audit-refresh").addEventListener("click", loadAudit);
$("#alert-read-all").addEventListener("click", async () => {
  try {
    await api("/api/alerts/read-all", { method: "POST" });
    await loadAlerts();
  } catch (e) {
    toast(e.message, true);
  }
});
$("#memory-clear").addEventListener("click", async () => {
  if (!confirm("清空助手记住的全部偏好？")) return;
  try {
    await api("/api/assistant/memory/ALL", { method: "DELETE" });
    toast("已清空全部偏好记忆");
    await loadMemory();
  } catch (e) {
    toast(e.message, true);
  }
});
$("#chat-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const text = $("#chat-text").value.trim();
  // 只发图不打字是正当用法（拍下来问有没有同款），不该被「不能发空消息」挡住。
  if (!text && !pendingImages.length) return;
  sendMessage(text);
});
// 多行输入：Enter 发送，Shift+Enter 换行；中文输入法组词时不拦截。
$("#chat-text").addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    $("#chat-form").requestSubmit();
  }
});
$("#chat-text").addEventListener("input", (event) => autoGrow(event.target));

/* —— 以图搜款：选择 / 拖拽 / 粘贴 / 移除 —— */
$("#image-pick").addEventListener("click", () => $("#image-file").click());
$("#image-file").addEventListener("change", (event) => {
  addImageFiles(event.target.files);
  event.target.value = ""; // 清空后才能重复选同一个文件
});

// 找同款最常见的动作就是「把手头这张图丢进来」。要求用户先点按钮、
// 再在文件选择器里翻找，中间的摩擦足以让这个功能形同不存在——所以拖拽和粘贴都支持。
// 监听挂在 document 上而不是某个面板：拖到窗口任意位置都该接住，
// 而 drop 上的 preventDefault 是必须的，否则浏览器会直接打开这张图片、把会话丢掉。
["dragenter", "dragover"].forEach((type) =>
  document.addEventListener(type, (event) => {
    if (!event.dataTransfer?.types?.includes("Files")) return;
    event.preventDefault();
    document.body.classList.add("dropping");
  }),
);
["dragleave", "dragend"].forEach((type) =>
  document.addEventListener(type, () => document.body.classList.remove("dropping")),
);
document.addEventListener("drop", (event) => {
  document.body.classList.remove("dropping");
  const files = event.dataTransfer?.files;
  if (!files || !files.length) return;
  event.preventDefault();
  if (busy) return toast("上一条还在回答，请稍候", true);
  addImageFiles(files);
});

$("#chat-text").addEventListener("paste", (event) => {
  const files = Array.from(event.clipboardData?.files || []);
  if (!files.length) return;
  event.preventDefault(); // 不贴一个巨大的文件名进输入框
  if (busy) return toast("上一条还在回答，请稍候", true);
  addImageFiles(files);
});

$("#image-tray").addEventListener("click", (event) => {
  const button = event.target.closest("[data-image-remove]");
  if (button) removeImageAt(Number(button.dataset.imageRemove));
});

window.addEventListener("beforeunload", () => voice?.destroy());

initVoice();
enableInput(false); // 未选会话前输入框与麦克风都不可用
loadStatus();
loadMemory();
loadAlerts();
loadConversations().then(() => {
  // 从商品详情页带种子进入：自动开新会话并发送首条引导消息。
  const seed = new URLSearchParams(location.search).get("seed");
  if (seed && seed.startsWith("product:")) {
    const productId = seed.slice("product:".length);
    newConversation().then(() => {
      if (currentId) sendMessage(`请介绍一下编号 ${productId} 这件商品，帮我看看是否值得买。`);
    });
  }
});
