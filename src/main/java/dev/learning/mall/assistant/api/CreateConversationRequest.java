package dev.learning.mall.assistant.api;

/** 新建会话请求，title 可省略，缺省为「新会话」。 */
public record CreateConversationRequest(String title) {}
