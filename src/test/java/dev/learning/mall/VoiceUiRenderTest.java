package dev.learning.mall;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 语音交互界面渲染测试。
 *
 * 语音识别与合成都由浏览器原生 Web Speech API 完成，后端不参与，因此这里验证的是
 * 「组件装配的前置条件」：助手页面必须输出麦克风按钮、实时转写条、播报开关等挂载点，
 * 且 /js/voice.js 能以匿名静态资源访问。任一缺失都会让前端组件静默失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoiceUiRenderTest {

    @Autowired MockMvc mvc;

    @Test
    void assistantPageRendersVoiceComponents() throws Exception {
        mvc.perform(get("/assistant").with(user("student")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"voice-mic\"")))
                .andExpect(content().string(containsString("id=\"voice-transcript\"")))
                .andExpect(content().string(containsString("id=\"voice-transcript-text\"")))
                .andExpect(content().string(containsString("id=\"voice-toggle\"")))
                .andExpect(content().string(containsString("id=\"voice-settings\"")))
                .andExpect(content().string(containsString("id=\"voice-picker\"")))
                .andExpect(content().string(containsString("id=\"voice-stop\"")))
                .andExpect(content().string(containsString("/js/voice.js")));
    }

    @Test
    void voiceScriptIsServedAsPublicStaticResource() throws Exception {
        // 未登录也应能取到脚本（SecurityConfig 放行 /js/**），否则助手页永远装配不出组件。
        mvc.perform(get("/js/voice.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mountVoiceUi")))
                .andExpect(content().string(containsString("webkitSpeechRecognition")))
                .andExpect(content().string(containsString("speechSynthesis")));
    }

    @Test
    void unauthenticatedVisitorIsSentToLoginAndSeesNoVoiceUi() throws Exception {
        // 浏览器（Accept: text/html）会被表单登录重定向到 /login；非 HTML 客户端拿到 401 JSON。
        mvc.perform(get("/assistant").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/assistant"))
                .andExpect(status().is4xxClientError())
                .andExpect(content().string(not(containsString("id=\"voice-mic\""))));
    }
}
