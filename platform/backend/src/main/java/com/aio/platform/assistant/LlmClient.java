package com.aio.platform.assistant;

import java.time.Duration;
import java.util.List;

/**
 * Port to a chat model. The first adapter speaks the OpenAI-compatible chat completions protocol that Google Gemini
 * (the default, free tier), Groq, OpenRouter, Cerebras and Mistral share and that a local Ollama serves, so switching
 * provider is configuration. A Claude adapter on the official Anthropic Java SDK is the planned paid-quality option
 * (docs/architecture/AIO-GENAI-PLAN.md).
 */
public interface LlmClient {
    record Message(String role, String content) {}

    record Reply(String text, String model, Integer inputTokens, Integer outputTokens) {}

    Reply complete(List<Message> messages, int maxTokens);

    /** The provider answered 429. {@code retryAfter} comes from its Retry-After header, or null. */
    class RateLimited extends IllegalStateException {
        public final Duration retryAfter;

        public RateLimited(Duration retryAfter) {
            super("The AI provider's free quota is used up for now; try again later");
            this.retryAfter = retryAfter;
        }
    }
}
