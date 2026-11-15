package com.aio.platform.assistant;

import java.util.List;

/**
 * Port to a chat model. The first adapter speaks the OpenAI-compatible chat completions protocol that the free tiers
 * share (Groq, OpenRouter, Cerebras, Mistral) and that a local Ollama serves, so switching provider is configuration.
 * A Claude adapter on the official Anthropic Java SDK is the planned paid-quality option (docs/architecture/AIO-GENAI-PLAN.md).
 */
public interface LlmClient {
    record Message(String role, String content) {}

    record Reply(String text, String model, Integer inputTokens, Integer outputTokens) {}

    Reply complete(List<Message> messages, int maxTokens);
}
