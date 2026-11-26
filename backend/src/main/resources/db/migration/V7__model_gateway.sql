-- V7：模型配置支持真实网关接入（2026-11-22 用户决策：支持主流美国与中国大模型协议）。
-- protocol：openai_compatible 覆盖 OpenAI / DeepSeek / Kimi / 智谱 / 通义 / 豆包 / 百度 v2 / 混元 / Gemini 兼容层；
-- anthropic 为 Anthropic 原生 Messages 协议。base_url 为网关根地址；model_name 为该网关的模型 ID（请求体里的 model）。
ALTER TABLE model_config ADD COLUMN protocol VARCHAR(32) NOT NULL DEFAULT 'openai_compatible'
  CHECK (protocol IN ('openai_compatible', 'anthropic'));
ALTER TABLE model_config ADD COLUMN base_url VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE model_config ADD COLUMN model_name VARCHAR(120) NOT NULL DEFAULT '';
