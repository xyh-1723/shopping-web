// 语音交互浏览器验收：注入假的 Web Speech API，跑通
// 「语音流式转文字 → 发送 → 助手回复 → 语音播报」整条链路。
//
// 先启动商城（建议关掉大模型，回复内容才可预期）：
//   java -jar target/learning-mall-1.0.0.jar --server.port=8091 \
//     --mall.assistant.enabled=false --spring.datasource.url='jdbc:h2:mem:voicesmoke;DB_CLOSE_DELAY=-1'
// 再运行（未下载 Playwright 自带浏览器时用系统 Edge）：
//   MALL_URL=http://127.0.0.1:8091 MALL_BROWSER_CHANNEL=msedge node tests/voice-smoke.cjs
const { chromium, expect } = require(
  process.env.PLAYWRIGHT_MODULE || "@playwright/test",
);
const fs = require("node:fs");
const path = require("node:path");
const base = process.env.MALL_URL || "http://localhost:8080";
const output = path.resolve(__dirname, "../artifacts");
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.MALL_BROWSER_CHANNEL
      ? { channel: process.env.MALL_BROWSER_CHANNEL }
      : {}),
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1050 },
    deviceScaleFactor: 1,
    permissions: ["microphone"],
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));

  // 假语音 API：识别结果由测试主动触发，播报内容被记录，避免依赖真实麦克风与云端识别。
  await page.addInitScript(() => {
    const state = { spoken: [], starts: 0, cancels: 0, instance: null, autoEnd: true };
    window.__voice = state;
    class FakeRecognition {
      start() {
        state.starts += 1;
        state.instance = this;
      }
      stop() {
        if (this.onend) this.onend();
      }
      abort() {
        if (this.onend) this.onend();
      }
    }
    state.emit = (parts) => {
      if (!state.instance || !state.instance.onresult) throw new Error("识别尚未启动");
      const results = parts.map((p) => {
        const result = [{ transcript: p.text, confidence: 0.95 }];
        result.isFinal = !!p.final;
        return result;
      });
      state.instance.onresult({ resultIndex: 0, results });
    };
    class FakeUtterance {
      constructor(text) {
        this.text = text;
      }
    }
    const synthesis = {
      speak(u) {
        state.spoken.push(u.text);
        if (state.autoEnd) setTimeout(() => u.onend && u.onend(), 10);
      },
      cancel() {
        state.cancels += 1;
      },
      getVoices() {
        return [
          { voiceURI: "zh-test", name: "测试中文音色", lang: "zh-CN", localService: true },
          { voiceURI: "en-test", name: "Test English", lang: "en-US", localService: true },
        ];
      },
      addEventListener() {},
      removeEventListener() {},
    };
    Object.defineProperty(window, "SpeechSynthesisUtterance", {
      value: FakeUtterance,
      configurable: true,
      writable: true,
    });
    Object.defineProperty(window, "speechSynthesis", { value: synthesis, configurable: true });
    // Edge/Chrome 同时提供标准名与 webkit 前缀名，两个都要覆盖（voice.js 优先取标准名）。
    Object.defineProperty(window, "SpeechRecognition", {
      value: FakeRecognition,
      configurable: true,
      writable: true,
    });
    Object.defineProperty(window, "webkitSpeechRecognition", {
      value: FakeRecognition,
      configurable: true,
      writable: true,
    });
  });
  const spokenCount = () => page.evaluate(() => window.__voice.spoken.length);

  try {
    // 1. 登录并进入助手页
    await page.goto(base + "/login");
    await page.getByLabel("密码", { exact: true }).fill("learn12345");
    await page.getByRole("button", { name: "登录商城" }).click();
    await page.goto(base + "/assistant");
    await page.locator("#new-conversation").click();

    const mic = page.locator("#voice-mic");
    await expect(mic).toBeVisible();
    await expect(mic).toBeEnabled();

    // 2. 点麦克风进入聆听态：转写条出现，识别被拉起
    await mic.click();
    await expect(page.locator("#voice-transcript")).toBeVisible();
    await expect(mic).toHaveClass(/listening/);
    expect(await page.evaluate(() => window.__voice.starts)).toBe(1);

    // 3. 语音流式转文字：临时结果实时回填，确认结果继续累加
    await page.evaluate(() => window.__voice.emit([{ text: "今天的天气", final: false }]));
    await expect(page.locator("#chat-text")).toHaveValue("今天的天气");
    await expect(page.locator("#voice-transcript-text")).toHaveText("今天的天气");
    await page.screenshot({ path: path.join(output, "语音输入.png"), fullPage: true });
    await page.evaluate(() =>
      window.__voice.emit([
        { text: "今天的天气", final: true },
        { text: "怎么样", final: false },
      ]),
    );
    await expect(page.locator("#chat-text")).toHaveValue("今天的天气怎么样");

    // 4. 发送：录音自动停止，消息上屏，助手回复出现
    await page.locator("#chat-send").click();
    await expect(page.locator(".msg.user .msg-bubble").last()).toHaveText("今天的天气怎么样");
    await expect(page.locator(".msg.assistant .msg-bubble").last()).not.toBeEmpty();
    await expect(page.locator("#voice-transcript")).toBeHidden();
    await expect(mic).not.toHaveClass(/listening/);

    // 5. 默认不自动播报，但手动「朗读」按钮可用
    expect(await spokenCount()).toBe(0);
    await page.locator(".speak-button").last().click();
    expect(await spokenCount()).toBe(1);
    const manual = await page.evaluate(() => window.__voice.spoken[0]);
    expect(manual.length).toBeGreaterThan(0);
    expect(manual).not.toContain("**");

    // 6. 打开播报开关：开关态、设置面板与偏好持久化
    await page.locator("#voice-toggle").click();
    await expect(page.locator("#voice-toggle")).toHaveClass(/on/);
    await expect(page.locator("#voice-toggle-text")).toHaveText("播报开启");
    await expect(page.locator("#voice-settings")).toBeVisible();
    // 音色下拉只列中文语音包：系统默认 + 中文音色（英文音色被过滤掉）
    await expect(page.locator("#voice-picker option")).toHaveCount(2);
    await expect(page.locator("#voice-picker option").nth(1)).toContainText("测试中文音色");
    const saved = await page.evaluate(() =>
      JSON.parse(localStorage.getItem("little-mall:student:voice-broadcast")),
    );
    expect(saved.enabled).toBe(true);

    // 7. 播报开启后，助手回复自动朗读
    await page.locator("#chat-text").fill("帮我推荐一个礼物");
    await page.locator("#chat-send").click();
    const reply = page.locator(".msg.assistant .msg-bubble").last();
    await expect(reply).not.toBeEmpty();
    await expect.poll(spokenCount).toBe(2);
    const spokenReply = await page.evaluate(() => window.__voice.spoken[1]);
    expect(await reply.innerText()).toContain(spokenReply.slice(0, 6));
    await page.screenshot({ path: path.join(output, "语音助手.png"), fullPage: true });

    // 8. 试听中途可以停止朗读
    await page.evaluate(() => {
      window.__voice.autoEnd = false;
    });
    await page.locator("#voice-preview").click();
    await expect(page.locator("#voice-stop")).toBeVisible();
    await page.locator("#voice-stop").click();
    await expect(page.locator("#voice-stop")).toBeHidden();
    expect(await spokenCount()).toBe(3);
    await page.evaluate(() => {
      window.__voice.autoEnd = true;
    });

    // 9. 关闭播报：设置面板收起，新回复不再自动发声
    await page.locator("#voice-toggle").click();
    await expect(page.locator("#voice-toggle-text")).toHaveText("播报关闭");
    await expect(page.locator("#voice-settings")).toBeHidden();
    await page.locator("#chat-text").fill("再推荐一个");
    await page.locator("#chat-send").click();
    await expect(page.locator(".msg.assistant .msg-bubble").last()).not.toBeEmpty();
    expect(await spokenCount()).toBe(3);
    expect(
      await page.evaluate(() =>
        JSON.parse(localStorage.getItem("little-mall:student:voice-broadcast")).enabled,
      ),
    ).toBe(false);

    expect(errors).toEqual([]);
    fs.writeFileSync(
      path.join(output, "voice-result.json"),
      JSON.stringify(
        {
          passed: true,
          browser: process.env.MALL_BROWSER_CHANNEL || "Chromium",
          checks: [
            "voice-input-listening",
            "streaming-transcript",
            "send-after-voice",
            "manual-speak",
            "broadcast-toggle-persist",
            "auto-broadcast-reply",
            "stop-speaking",
            "broadcast-off-silent",
          ],
          pageErrors: errors,
        },
        null,
        2,
      ),
    );
    console.log(
      "Voice flow passed: mic input, streaming transcript, send, auto broadcast, manual speak, toggle persistence.",
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
