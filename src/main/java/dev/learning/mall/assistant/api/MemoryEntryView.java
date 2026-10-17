package dev.learning.mall.assistant.api;

/**
 * 一条助手记忆，供用户自己查看。
 *
 * <p>让用户看得见、删得掉，是记忆功能的一部分而不是附加项：用户不知道助手记了什么，
 * 就会在第三次被推荐同一类东西时感到不安。透明度换来的是信任，而信任是复购的前提。
 *
 * <p>{@code source} 区分两类来源：{@code MODEL} = 你明确说过的；{@code AUTO} = 助手根据
 * 以往对话自己推断的。两者在页面上用不同标记呈现——推断出来的偏好必须一眼看得出是推断，
 * 否则用户会以为「我没说过这个啊，它怎么知道」。这正是自动抽取能被接受的前提。
 */
public record MemoryEntryView(String key, String label, String value, String source, String updatedAt) {}
