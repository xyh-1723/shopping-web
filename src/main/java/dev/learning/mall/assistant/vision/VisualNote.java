package dev.learning.mall.assistant.vision;

/**
 * 「看图」这一步的结果，会以文字形式注入本轮上下文。
 *
 * <p>两种取值，界线必须清楚：
 *
 * <ul>
 *   <li>{@code available=true}：视觉模型真的看了图，{@code text} 是它的识别结果；
 *   <li>{@code available=false}：没有可用的视觉模型，{@code text} 是给模型的<b>指令</b>
 *       ——告诉它「用户发了图但我看不了，请如实说明并请用户用文字描述」。
 * </ul>
 *
 * <p>第二种情况<b>不能</b>退化成让对话模型自己想象图片内容。让一个看不懂图的模型
 * 描述一张它没见过的图，产出的每一句都是编的，而用户无从分辨——这比直接说「看不了」糟得多。
 */
public record VisualNote(boolean available, String text) {

    /** 没有可用的视觉模型时给模型的指引。措辞是「如实告知」，不是「猜一下」。 */
    private static final String UNAVAILABLE_HINT =
            "用户这次发来了图片，但当前没有接入视觉模型，我无法看到图片内容。"
                    + "请如实告诉用户：暂时看不了图，并请ta用文字描述一下想找什么（比如品类、颜色、用途），"
                    + "或者说出预算，你照样能帮ta挑。**绝对不要**编造图片内容，也不要假装看到了图片。";

    public static VisualNote unavailable() {
        return new VisualNote(false, UNAVAILABLE_HINT);
    }

    public static VisualNote described(String text) {
        return new VisualNote(true, text);
    }
}
