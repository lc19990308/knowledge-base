package dev.aiqa.cleaning;

/**
 * 数据源读出的一条原始记录。
 *
 * @param text 原始文本（尚未清洗）
 * @param location 来源位置，例如「客服语料/faq.csv:12」「手册.md#差旅」；可为空
 */
public record RawRecord(String text, String location) {}
