// 语音组件单元测试：在 Node 里加载浏览器脚本 voice.js，注入假的识别/合成实现。
// 运行：node --test tests/voice-kit.test.cjs
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const VoiceKit = require(path.resolve(__dirname, "../src/main/resources/static/js/voice.js"));
const { cleanText, splitForSpeech, SpeechInput, Broadcast } = VoiceKit;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** 假识别器：手动触发 onresult / onerror / onend，验证中间结果合并与自动重启。 */
class FakeRecognition {
  constructor() {
    this.lang = "";
    this.continuous = false;
    this.interimResults = false;
    this.started = 0;
    this.stopped = 0;
    FakeRecognition.last = this;
  }
  start() {
    this.started += 1;
  }
  stop() {
    this.stopped += 1;
    if (this.onend) this.onend();
  }
  /** parts: [{ text, final }] 模拟一次识别回调。 */
  emit(parts) {
    const results = parts.map((p) => {
      const result = [{ transcript: p.text, confidence: 0.9 }];
      result.isFinal = !!p.final;
      return result;
    });
    this.onresult({ resultIndex: 0, results });
  }
  fail(code) {
    this.onerror({ error: code });
  }
  end() {
    if (this.onend) this.onend();
  }
}

class FakeUtterance {
  constructor(text) {
    this.text = text;
    this.lang = "";
    this.rate = 1;
    this.onend = null;
    this.onerror = null;
  }
}

function fakeSynthesis() {
  return {
    spoken: [],
    cancelled: 0,
    speak(u) {
      this.spoken.push(u);
    },
    cancel() {
      this.cancelled += 1;
    },
    getVoices() {
      return [
        { voiceURI: "zh-f", name: "中文女声", lang: "zh-CN", localService: true },
        { voiceURI: "en-m", name: "English", lang: "en-US", localService: true },
      ];
    },
  };
}

function fakeStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    keys: () => [...map.keys()],
  };
}

test("cleanText 去掉 Markdown、链接与表情，金额读成元", () => {
  const raw = "**推荐**：云朵马克杯 ¥128.50 😀\n- 适合送礼\nhttps://example.com/x";
  const text = cleanText(raw);
  assert.ok(!text.includes("**"), "应去掉加粗标记");
  assert.ok(!text.includes("😀"), "应去掉表情符号");
  assert.ok(!text.includes("http"), "应去掉链接");
  assert.ok(text.includes("128.50元"), "金额符号应读成元，实际：" + text);
  assert.ok(text.includes("适合送礼"), "列表内容应保留");
  assert.ok(!text.includes("**推荐**"), "标题强调符号不应残留");
});

test("cleanText 超长文本按句子边界截断", () => {
  const raw = "第一句介绍。".repeat(60);
  const text = cleanText(raw, 50);
  assert.ok(text.length <= 50, "截断后不应超过上限");
  assert.ok(text.endsWith("。"), "应在句号处收尾，实际结尾：" + text.slice(-5));
});

test("splitForSpeech 按句打包且不超限", () => {
  const chunks = splitForSpeech("今天天气晴朗。适合出门散步。记得带伞；也可能下雨！", 20);
  assert.ok(chunks.length >= 2, "长文本应切成多段");
  chunks.forEach((c) => assert.ok(c.length <= 20, "每段不应超过上限：" + c));
  assert.equal(chunks.join(""), "今天天气晴朗。适合出门散步。记得带伞；也可能下雨！");
});

test("splitForSpeech 超长单句硬切且不破坏代理对", () => {
  const chunks = splitForSpeech("好".repeat(25), 10);
  assert.deepEqual(
    chunks.map((c) => c.length),
    [10, 10, 5],
  );
  assert.deepEqual(splitForSpeech("", 10), []);
});

test("SpeechInput 中间结果流式回填、确认结果累加", () => {
  const events = [];
  const input = new SpeechInput({
    recognitionFactory: () => FakeRecognition,
    onTranscript: (payload) => events.push(payload),
    onState: () => {},
  });
  assert.equal(SpeechInput.supported(() => FakeRecognition), true);
  input.start();
  const recognition = FakeRecognition.last;
  assert.equal(recognition.lang, "zh-CN");
  assert.equal(recognition.continuous, true);
  assert.equal(recognition.interimResults, true);

  recognition.emit([{ text: "今天", final: false }]);
  assert.equal(input.transcript, "今天", "临时结果应实时可见");
  recognition.emit([
    { text: "今天", final: true },
    { text: "天气怎么样", final: false },
  ]);
  assert.equal(input.transcript, "今天天气怎么样");
  assert.equal(input.confirmed, "今天");
  assert.equal(events.at(-1).interim, "天气怎么样");

  input.stop();
  assert.equal(input.listening, false);
  assert.equal(recognition.stopped, 1);
});

test("SpeechInput 静音结束自动重启，权限错误则停止并提示中文", async () => {
  const notices = [];
  const input = new SpeechInput({
    recognitionFactory: () => FakeRecognition,
    onError: (code, message) => notices.push([code, message]),
  });
  input.start();
  const recognition = FakeRecognition.last;
  recognition.end(); // 浏览器静音超时自动结束
  await sleep(320);
  assert.equal(recognition.started, 2, "静音结束后应重新拉起识别继续监听");
  assert.equal(input.listening, true);

  recognition.fail("not-allowed");
  assert.deepEqual(notices, [["not-allowed", "麦克风权限被拒绝，请点击地址栏的麦克风图标允许后重试"]]);
  recognition.end();
  await sleep(320);
  assert.equal(recognition.started, 2, "出错后不应自动重启，避免提示循环");
  assert.equal(input.listening, false);
});

test("Broadcast 分句排队朗读，逐句推进后回到空闲", () => {
  const synthesis = fakeSynthesis();
  const states = [];
  const broadcast = new Broadcast({
    synthesis,
    Utterance: FakeUtterance,
    storage: fakeStorage(),
    onState: (state) => states.push(state),
  });
  // 两句各 91 字，合计超过 160 字上限，应被切成两条 utterance。
  const sentence = "今天天气晴朗适合出门散步记得带伞防晒".repeat(5) + "。";
  broadcast.setEnabled(true);
  assert.equal(broadcast.speak(sentence + sentence), true);
  assert.equal(synthesis.spoken.length, 1, "先播第一句");
  assert.equal(synthesis.spoken[0].text, sentence);
  assert.equal(synthesis.spoken[0].rate, 1);
  assert.equal(synthesis.spoken[0].lang, "zh-CN");
  assert.equal(synthesis.spoken[0].voice.voiceURI, "zh-f", "应优先选用中文语音包");

  synthesis.spoken[0].onend();
  assert.equal(synthesis.spoken.length, 2, "第一句结束后自动播下一句");
  assert.equal(synthesis.spoken[1].text, sentence);
  synthesis.spoken[1].onend();
  assert.equal(broadcast.speaking, false);
  assert.deepEqual(states, ["enabled", "speaking", "idle"]);
});

test("Broadcast 关闭时不朗读，手动朗读仍生效，取消后旧队列失效", () => {
  const synthesis = fakeSynthesis();
  const broadcast = new Broadcast({ synthesis, Utterance: FakeUtterance, storage: fakeStorage() });
  assert.equal(broadcast.speak("你好。"), false, "未开启时自动播报不发声");
  assert.equal(broadcast.speakNow("你好。"), true, "手动点击朗读忽略总开关");

  broadcast.setEnabled(true);
  broadcast.speak("第一句。第二句。");
  const first = synthesis.spoken.at(-1);
  broadcast.cancel();
  first.onend(); // 迟到的 onend 不应继续播放
  assert.equal(synthesis.spoken.length, 2, "取消后不再追加朗读");
  assert.equal(broadcast.speaking, false);
});

test("Broadcast 偏好写入 localStorage，音色与语速可恢复", () => {
  const storage = fakeStorage();
  const key = "little-mall:student:voice-broadcast";
  const first = new Broadcast({ synthesis: fakeSynthesis(), Utterance: FakeUtterance, storage, storageKey: key });
  first.setEnabled(true);
  first.setRate(1.25);
  first.setVoice("zh-f");
  assert.ok(storage.getItem(key).includes("\"enabled\":true"));

  const restored = new Broadcast({ synthesis: fakeSynthesis(), Utterance: FakeUtterance, storage, storageKey: key });
  assert.equal(restored.enabled, true);
  assert.equal(restored.rate, 1.25);
  assert.equal(restored.voiceURI, "zh-f");
  assert.equal(restored.listVoices().length, 1, "只列出中文语音包");
});

test("VoiceKit 导出组件与常量", () => {
  assert.equal(typeof VoiceKit.mountVoiceUi, "function");
  assert.equal(VoiceKit.SPEECH_CHUNK_LIMIT, 160);
  assert.equal(VoiceKit.errorText("network").includes("Edge"), true);
});
