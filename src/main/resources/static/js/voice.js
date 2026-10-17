/**
 * 语音交互组件（浏览器原生 Web Speech API，无需后端与密钥）。
 *
 * 两个组件：
 * 1. SpeechInput  —— 麦克风语音输入。说话时把临时结果（interim）实时回填到对话框，
 *                    确认后的文本（final）累加保留，点发送即作为普通消息交给助手。
 * 2. Broadcast    —— 助手回复语音播报。可开关、可选音色（语音包）与语速，
 *                    偏好写入 localStorage；长文本按句切分排队，规避 Chromium 约 15 秒截断。
 *
 * 设计约定：
 * - 语音识别需要安全上下文（http://localhost、http://127.0.0.1 或 HTTPS）与麦克风授权。
 * - Chrome/Edge 的识别由远端服务完成，断网或网络受限时会报 network 错误，此时给出中文提示。
 * - 识别/合成对象都可注入，纯逻辑与 DOM 分离，便于在 Node 中直接单测。
 */
(function (global) {
  "use strict";

  /** Chromium 对单条 utterance 有约 15 秒（约 200 字符）截断问题，按句切分规避。 */
  const SPEECH_CHUNK_LIMIT = 160;
  /** 播报长度上限：助手回复过长时只念开头，避免用户等待。 */
  const SPEECH_MAX_CHARS = 600;
  /** 支持的中文识别错误码 → 用户可读提示。 */
  const ERROR_TEXT = {
    "not-allowed": "麦克风权限被拒绝，请点击地址栏的麦克风图标允许后重试",
    "service-not-allowed": "浏览器拒绝了语音识别服务，请检查站点权限设置",
    "audio-capture": "没有检测到可用的麦克风设备",
    network: "语音识别服务不可达，请检查网络（国内访问 Chrome 识别服务常失败，建议改用 Edge）",
    "language-not-supported": "当前浏览器不支持中文语音识别",
    "no-speech": "没有听到声音，请靠近麦克风再说一次",
    aborted: "语音识别已停止",
    unknown: "语音识别失败，请重试",
  };

  function errorText(code) {
    return ERROR_TEXT[code] || "语音识别失败（" + code + "）";
  }

  /**
   * 把界面文本整理成适合朗读的纯文本：去掉 Markdown 标记、链接、表情符号，
   * 把金额符号读成「元」，并压缩空白。maxChars 之外按句子边界截断。
   */
  function cleanText(raw, maxChars) {
    let text = String(raw == null ? "" : raw);
    const limit = maxChars == null ? SPEECH_MAX_CHARS : maxChars;
    // 代码块、行内代码
    text = text.replace(/```[\s\S]*?```/g, " ").replace(/`([^`]*)`/g, "$1");
    // 链接与图片：保留可读标题
    text = text.replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1").replace(/https?:\/\/\S+/g, " ");
    // 金额：¥128 → 128元
    text = text.replace(/[¥￥]\s*(\d+(?:\.\d+)?)/g, "$1元").replace(/[¥￥]/g, "元");
    // 标题、引用、列表符号、强调符号
    text = text.replace(/^\s{0,3}#{1,6}\s*/gm, "").replace(/^\s{0,3}>\s?/gm, "");
    text = text.replace(/^\s{0,3}[-*+•]\s+/gm, "").replace(/^\s{0,3}\d+[.)]\s+/gm, "");
    text = text.replace(/(\*\*|__|\*|_|~~)/g, "");
    text = text.replace(/[\u200B-\u200D\uFEFF]/g, " ");
    // 表情符号与图形符号（只匹配象形符号，保留中文标点、数字与字母）
    try {
      text = text.replace(/\p{Extended_Pictographic}/gu, " ");
    } catch (ignored) {
      /* 老引擎不支持 Unicode 属性转义时跳过 */
    }
    text = text.replace(/\s+/g, " ").trim();
    if (limit > 0 && text.length > limit) {
      const head = text.slice(0, limit);
      const cut = Math.max(head.lastIndexOf("。"), head.lastIndexOf("！"), head.lastIndexOf("？"));
      text = cut > limit * 0.5 ? head.slice(0, cut + 1) : head;
    }
    return text;
  }

  /** 单句超长时按字符硬切，用 Array.from 切分避免拆坏代理对（emoji 等）。 */
  function hardSplit(sentence, limit) {
    const chars = Array.from(sentence);
    const pieces = [];
    while (chars.length > limit) pieces.push(chars.splice(0, limit).join(""));
    if (chars.length) pieces.push(chars.join(""));
    return pieces;
  }

  /**
   * 按句号/问号/感叹号等边界切句并打包成不超过 limit 的片段数组，
   * 交给 speechSynthesis 逐条排队播放，避免长文本被引擎截断。
   */
  function splitForSpeech(text, limit) {
    const size = limit == null ? SPEECH_CHUNK_LIMIT : limit;
    const clean = String(text == null ? "" : text).replace(/\s+/g, " ").trim();
    if (!clean) return [];
    const sentences = clean.split(/(?<=[。！？!?；;：:．.])/u).filter((s) => s.trim());
    const chunks = [];
    let buffer = "";
    const flush = () => {
      if (buffer.trim()) chunks.push(buffer.trim());
      buffer = "";
    };
    for (const sentence of sentences) {
      const piece = sentence.trim();
      if (piece.length > size) {
        flush();
        hardSplit(piece, size).forEach((p) => chunks.push(p));
        continue;
      }
      if ((buffer + piece).length > size) flush();
      buffer += piece;
    }
    flush();
    return chunks;
  }

  function clampRate(value) {
    const rate = Number(value);
    if (!Number.isFinite(rate)) return 1;
    return Math.min(2, Math.max(0.5, rate));
  }

  function safeStorage() {
    try {
      return typeof global.localStorage === "undefined" ? null : global.localStorage;
    } catch (ignored) {
      return null; // 隐私模式下访问 localStorage 会抛错
    }
  }

  function defaultRecognitionCtor() {
    return global.SpeechRecognition || global.webkitSpeechRecognition || null;
  }

  /**
   * 语音输入组件。持续监听：浏览器在静音后会自动结束识别，这里静默重启，
   * 用户主动停或出错才真正结束；临时结果通过 onTranscript 流式回调给界面。
   */
  class SpeechInput {
    constructor(options) {
      const opts = options || {};
      this.lang = opts.lang || "zh-CN";
      this.recognitionFactory = opts.recognitionFactory || defaultRecognitionCtor;
      this.onState = opts.onState || function () {};
      this.onTranscript = opts.onTranscript || function () {};
      this.onError = opts.onError || function () {};
      this.continuous = opts.continuous !== false;
      this.interimResults = opts.interimResults !== false;
      this.listening = false;
      this.finalText = "";
      this.interimText = "";
      this.recognition = null;
      this.restartTimer = null;
      this.stopRequested = false;
    }

    static supported(factory) {
      return !!(factory ? factory() : defaultRecognitionCtor());
    }

    /** 当前输入框应展示的内容：已确认文本 + 实时预览。 */
    get transcript() {
      return (this.finalText + this.interimText).replace(/\s+/g, " ").trim();
    }

    get confirmed() {
      return this.finalText.replace(/\s+/g, " ").trim();
    }

    toggle() {
      if (this.listening) this.stop();
      else this.start();
    }

    start() {
      if (this.listening) return;
      const Ctor = this.recognitionFactory();
      if (!Ctor) {
        this.onState("unsupported", {});
        return;
      }
      this.finalText = "";
      this.interimText = "";
      this.stopRequested = false;
      const recognition = new Ctor();
      recognition.lang = this.lang;
      recognition.continuous = this.continuous;
      recognition.interimResults = this.interimResults;
      recognition.maxAlternatives = 1;
      recognition.onresult = (event) => this.handleResult(event);
      recognition.onerror = (event) => this.handleError(event);
      recognition.onend = () => this.handleEnd();
      this.recognition = recognition;
      try {
        recognition.start();
      } catch (ignored) {
        /* 重复 start() 会抛 InvalidStateError，忽略即可 */
      }
      this.listening = true;
      this.onState("listening", {});
      this.onTranscript({ text: "", final: "", interim: "" });
    }

    handleResult(event) {
      let interim = "";
      for (let i = event.resultIndex || 0; i < event.results.length; i += 1) {
        const result = event.results[i];
        const alternative = result[0];
        const text = alternative && alternative.transcript ? alternative.transcript : "";
        if (result.isFinal) this.finalText += text;
        else interim += text;
      }
      this.interimText = interim;
      this.onTranscript({
        text: this.transcript,
        final: this.confirmed,
        interim: interim.replace(/\s+/g, " ").trim(),
      });
    }

    handleError(event) {
      const code = (event && event.error) || "unknown";
      if (code === "no-speech" || code === "aborted") return; // 静音/自然结束，交给 onend 处理
      this.stopRequested = true; // 真错误不再自动重启，避免弹窗循环
      this.onError(code, errorText(code));
      this.onState("error", { code: code });
    }

    handleEnd() {
      if (this.listening && !this.stopRequested) {
        // 浏览器静音超时自动结束：稍后重启，保持「一直能说」的体验。
        this.restartTimer = global.setTimeout(() => {
          if (!this.listening || this.stopRequested) return;
          try {
            this.recognition.start();
          } catch (ignored) {
            /* 重启失败则等待下一次 onend */
          }
        }, 250);
        return;
      }
      this.listening = false;
      this.recognition = null;
      this.onState("idle", {});
    }

    stop() {
      this.stopRequested = true;
      const wasListening = this.listening;
      this.listening = false;
      if (this.restartTimer) {
        global.clearTimeout(this.restartTimer);
        this.restartTimer = null;
      }
      if (this.recognition) {
        try {
          this.recognition.stop();
        } catch (ignored) {
          /* 未启动时 stop() 可能抛错 */
        }
      }
      this.recognition = null;
      if (wasListening) this.onState("idle", {});
    }

    destroy() {
      this.stop();
      this.onState = function () {};
      this.onTranscript = function () {};
      this.onError = function () {};
    }
  }

  /**
   * 语音播报组件。开启后调用 speak() 朗读助手回复；长文本分句排队，
   * 每句结束自动播下一句，cancel() 用「代次」标记让旧队列立即失效。
   */
  class Broadcast {
    constructor(options) {
      const opts = options || {};
      this.synthesis = opts.synthesis !== undefined ? opts.synthesis : global.speechSynthesis || null;
      this.Utterance = opts.Utterance !== undefined ? opts.Utterance : global.SpeechSynthesisUtterance || null;
      this.storage = opts.storage !== undefined ? opts.storage : safeStorage();
      this.storageKey = opts.storageKey || "little-mall:voice-broadcast";
      this.lang = opts.lang || "zh-CN";
      this.onState = opts.onState || function () {};
      this.onProgress = opts.onProgress || function () {};
      const saved = this.load();
      this.enabled = !!saved.enabled;
      this.rate = clampRate(saved.rate == null ? 1 : saved.rate);
      this.voiceURI = saved.voiceURI || null;
      this.queue = [];
      this.index = 0;
      this.speaking = false;
      this.generation = 0;
    }

    static supported(synthesis) {
      const engine = synthesis !== undefined ? synthesis : global.speechSynthesis;
      return !!(engine && global.SpeechSynthesisUtterance);
    }

    load() {
      if (!this.storage) return {};
      try {
        return JSON.parse(this.storage.getItem(this.storageKey)) || {};
      } catch (ignored) {
        return {};
      }
    }

    save() {
      if (!this.storage) return;
      try {
        this.storage.setItem(
          this.storageKey,
          JSON.stringify({ enabled: this.enabled, rate: this.rate, voiceURI: this.voiceURI }),
        );
      } catch (ignored) {
        /* 存储不可用时仅本次生效 */
      }
    }

    /** 可选音色列表：优先中文语音包，没有中文时退回全部。 */
    listVoices() {
      if (!this.synthesis) return [];
      let voices = [];
      try {
        voices = this.synthesis.getVoices() || [];
      } catch (ignored) {
        return [];
      }
      const zh = voices.filter((v) => String(v.lang || "").toLowerCase().indexOf("zh") === 0);
      return (zh.length ? zh : voices).map((v) => ({
        uri: v.voiceURI,
        name: v.name,
        lang: v.lang,
        local: !!v.localService,
      }));
    }

    pickVoice() {
      if (!this.synthesis) return null;
      let voices = [];
      try {
        voices = this.synthesis.getVoices() || [];
      } catch (ignored) {
        return null;
      }
      if (!voices.length) return null;
      if (this.voiceURI) {
        const matched = voices.find((v) => v.voiceURI === this.voiceURI);
        if (matched) return matched;
      }
      const zh = voices.filter((v) => String(v.lang || "").toLowerCase().indexOf("zh") === 0);
      return zh[0] || null; // 没有中文语音包时交给引擎默认音色
    }

    setEnabled(on) {
      this.enabled = !!on;
      this.save();
      if (!this.enabled) this.cancel();
      this.onState(this.enabled ? "enabled" : "disabled", {});
    }

    setRate(rate) {
      this.rate = clampRate(rate);
      this.save();
    }

    setVoice(uri) {
      this.voiceURI = uri || null;
      this.save();
    }

    /** 手动朗读（消息旁的小喇叭），即使总开关关闭也生效。 */
    speakNow(text) {
      return this.speak(text, true);
    }

    speak(text, force) {
      if (!this.Utterance || !this.synthesis) return false;
      if (!this.enabled && !force) return false;
      const chunks = splitForSpeech(cleanText(text, SPEECH_MAX_CHARS), SPEECH_CHUNK_LIMIT);
      if (!chunks.length) return false;
      this.cancel(true);
      const generation = this.generation;
      this.queue = chunks;
      this.index = 0;
      this.speaking = true;
      this.onState("speaking", { total: chunks.length });
      this.speakChunk(generation);
      return true;
    }

    speakChunk(generation) {
      if (generation !== this.generation) return; // 已被新的朗读/停止取代
      if (this.index >= this.queue.length) {
        this.speaking = false;
        this.onState("idle", {});
        return;
      }
      const utterance = new this.Utterance(this.queue[this.index]);
      utterance.lang = this.lang;
      utterance.rate = this.rate;
      utterance.pitch = 1;
      utterance.volume = 1;
      const voice = this.pickVoice();
      if (voice) utterance.voice = voice;
      utterance.onend = () => {
        if (generation !== this.generation) return;
        this.onProgress({ index: this.index + 1, total: this.queue.length });
        this.index += 1;
        this.speakChunk(generation);
      };
      utterance.onerror = () => {
        if (generation !== this.generation) return;
        this.index += 1; // 单句失败不阻断整段朗读
        this.speakChunk(generation);
      };
      try {
        this.synthesis.speak(utterance);
      } catch (ignored) {
        this.speaking = false;
        this.onState("idle", {});
      }
    }

    /** 停止朗读。silent=true 时不广播状态（供 speak() 内部复位用）。 */
    cancel(silent) {
      this.generation += 1;
      this.queue = [];
      this.index = 0;
      const wasSpeaking = this.speaking;
      this.speaking = false;
      if (this.synthesis) {
        try {
          this.synthesis.cancel();
        } catch (ignored) {
          /* 引擎未就绪时忽略 */
        }
      }
      if (!silent && wasSpeaking) this.onState("idle", {});
    }

    stop() {
      this.cancel();
    }
  }

  /**
   * 把页面上的静态标记接线成两个可用组件，并处理开关、音色/语速、实时转写条、
   * 计时、错误提示等界面细节。返回句柄供业务代码调用（发送后停止、回复后播报）。
   */
  function mountVoiceUi(options) {
    const opts = options || {};
    const el = opts.el || {};
    const onToast = opts.onToast || function () {};
    const storageKey = opts.storageKey || "little-mall:voice-broadcast";
    const lang = opts.lang || "zh-CN";

    let timerId = null;
    let startedAt = 0;

    const input = new SpeechInput({
      lang: lang,
      recognitionFactory: opts.recognitionFactory,
      onState: (state, detail) => {
        el.mic.classList.toggle("listening", state === "listening");
        el.mic.setAttribute("aria-pressed", state === "listening" ? "true" : "false");
        el.mic.title = state === "listening" ? "停止语音输入" : "语音输入";
        if (state === "listening") {
          el.transcript.hidden = false;
          startTimer();
        } else {
          stopTimer();
          if (el.transcript) el.transcript.hidden = true;
          if (state === "unsupported") onToast("当前浏览器不支持语音输入，建议使用 Edge 或 Chrome", true);
        }
        if (state === "error" && detail && detail.code) el.transcript.hidden = true;
      },
      onTranscript: (payload) => {
        const box = opts.getInput ? opts.getInput() : null;
        if (box) box.value = payload.text;
        if (el.transcriptText) {
          el.transcriptText.textContent = payload.text || "正在聆听…";
        }
        if (payload.text && box) {
          box.dispatchEvent(new Event("input", { bubbles: true }));
        }
      },
      onError: (code, message) => onToast(message, true),
    });

    const broadcast = new Broadcast({
      lang: lang,
      storageKey: storageKey,
      onState: (state, detail) => {
        const on = broadcast.enabled;
        el.toggle.classList.toggle("on", on);
        el.toggle.setAttribute("aria-pressed", on ? "true" : "false");
        if (el.toggleText) el.toggleText.textContent = on ? "播报开启" : "播报关闭";
        if (el.settings) el.settings.hidden = !on;
        if (el.stop) el.stop.hidden = state !== "speaking";
        if (opts.onBroadcastState) opts.onBroadcastState(state, detail);
      },
    });

    function startTimer() {
      startedAt = Date.now();
      if (el.timer) el.timer.textContent = "00:00";
      stopTimer();
      timerId = global.setInterval(() => {
        if (!el.timer) return;
        const seconds = Math.floor((Date.now() - startedAt) / 1000);
        el.timer.textContent =
          String(Math.floor(seconds / 60)).padStart(2, "0") + ":" + String(seconds % 60).padStart(2, "0");
      }, 500);
    }

    function stopTimer() {
      if (timerId) global.clearInterval(timerId);
      timerId = null;
    }

    function renderVoices() {
      if (!el.voicePicker) return;
      const voices = broadcast.listVoices();
      if (!voices.length) {
        el.voicePicker.innerHTML = '<option value="">系统默认音色</option>';
        el.voicePicker.disabled = true;
        return;
      }
      el.voicePicker.disabled = false;
      el.voicePicker.innerHTML =
        '<option value="">系统默认音色</option>' +
        voices
          .map(
            (v) =>
              '<option value="' +
              v.uri.replace(/"/g, "&quot;") +
              '">' +
              v.name.replace(/[<>&]/g, "") +
              " · " +
              v.lang +
              (v.local ? "" : " · 在线") +
              "</option>",
          )
          .join("");
      el.voicePicker.value = broadcast.voiceURI || "";
    }

    // 浏览器异步加载语音包，需要监听 voiceschanged 后再填充选项。
    if (global.speechSynthesis) {
      const refresh = () => renderVoices();
      renderVoices();
      if (typeof global.speechSynthesis.addEventListener === "function") {
        global.speechSynthesis.addEventListener("voiceschanged", refresh);
      } else {
        global.speechSynthesis.onvoiceschanged = refresh;
      }
    }

    if (el.mic) el.mic.addEventListener("click", () => input.toggle());
    if (el.cancel) {
      el.cancel.addEventListener("click", () => {
        input.stop();
        const box = opts.getInput ? opts.getInput() : null;
        if (box) box.value = "";
      });
    }
    if (el.toggle) el.toggle.addEventListener("click", () => broadcast.setEnabled(!broadcast.enabled));
    if (el.stop) el.stop.addEventListener("click", () => broadcast.cancel());
    if (el.voicePicker) el.voicePicker.addEventListener("change", (e) => broadcast.setVoice(e.target.value));
    if (el.ratePicker) {
      el.ratePicker.value = String(broadcast.rate);
      el.ratePicker.addEventListener("change", (e) => broadcast.setRate(e.target.value));
    }
    if (el.preview) {
      el.preview.addEventListener("click", () => broadcast.speakNow("你好，我是小拾，这就是我播报的声音。"));
    }

    // 初值同步：语音输入不可用时隐藏麦克风并说明原因，播报不可用时隐藏整条工具栏。
    const inputSupported = SpeechInput.supported(opts.recognitionFactory);
    if (el.mic) el.mic.hidden = !inputSupported;
    if (!inputSupported && el.hint) {
      el.hint.textContent = "当前浏览器不支持语音输入，建议使用 Edge 或 Chrome";
    }
    const broadcastSupported = Broadcast.supported();
    if (el.toolbar) el.toolbar.hidden = !broadcastSupported;
    if (!broadcastSupported && inputSupported && el.hint) {
      el.hint.textContent = "当前浏览器不支持语音播报";
    }
    el.toggle.classList.toggle("on", broadcast.enabled);
    el.toggle.setAttribute("aria-pressed", broadcast.enabled ? "true" : "false");
    if (el.toggleText) el.toggleText.textContent = broadcast.enabled ? "播报开启" : "播报关闭";
    if (el.settings) el.settings.hidden = !broadcast.enabled;

    return {
      input: input,
      broadcast: broadcast,
      refreshVoices: renderVoices,
      /** 发送消息、切换会话、离开页面时调用：停录音 + 停朗读。 */
      halt: function () {
        input.stop();
        broadcast.cancel();
        if (el.transcript) el.transcript.hidden = true;
        stopTimer();
      },
      destroy: function () {
        input.destroy();
        broadcast.cancel();
        stopTimer();
      },
    };
  }

  const VoiceKit = {
    cleanText,
    splitForSpeech,
    SpeechInput,
    Broadcast,
    mountVoiceUi,
    errorText,
    SPEECH_CHUNK_LIMIT,
    SPEECH_MAX_CHARS,
  };

  global.VoiceKit = VoiceKit;
  if (typeof module !== "undefined" && module.exports) module.exports = VoiceKit;
})(typeof window !== "undefined" ? window : globalThis);
