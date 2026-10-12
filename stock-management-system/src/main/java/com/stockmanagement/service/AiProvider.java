package com.stockmanagement.service;

/**
 * Supported AI providers. Claude aur Gemini ka apna API style hai,
 * baaki sab OpenAI-compatible hain (sirf base URL alag).
 */
public enum AiProvider {

    CLAUDE("Claude (Anthropic)", "https://api.anthropic.com", Style.ANTHROPIC),
    OPENAI("OpenAI (GPT)", "https://api.openai.com/v1", Style.OPENAI),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com", Style.GEMINI),
    MISTRAL("Mistral AI", "https://api.mistral.ai/v1", Style.OPENAI),
    GROQ("Groq", "https://api.groq.com/openai/v1", Style.OPENAI),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", Style.OPENAI),
    XAI("xAI (Grok)", "https://api.x.ai/v1", Style.OPENAI),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", Style.OPENAI),
    TOGETHER("Together AI", "https://api.together.xyz/v1", Style.OPENAI),
    CUSTOM("Custom (OpenAI-compatible)", "", Style.OPENAI);

    public enum Style { ANTHROPIC, GEMINI, OPENAI }

    private final String label;
    private final String defaultBaseUrl;
    private final Style style;

    AiProvider(String label, String defaultBaseUrl, Style style) {
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.style = style;
    }

    public String getLabel() { return label; }
    public String getDefaultBaseUrl() { return defaultBaseUrl; }
    public Style getStyle() { return style; }
    public boolean isBaseUrlRequired() { return this == CUSTOM; }

    public static AiProvider parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Choose a provider.");
        }
        try {
            return AiProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown provider.");
        }
    }
}