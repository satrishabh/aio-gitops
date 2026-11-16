package com.aio.platform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * POST {base}/chat/completions. The key is sent only as a bearer header and never logged; provider error bodies are
 * not passed on (they can echo the prompt).
 *
 * <p>Works with Google Gemini's OpenAI-compatible endpoint ({@code https://generativelanguage.googleapis.com/v1beta/openai}),
 * Groq, OpenRouter, Cerebras, Mistral and Ollama. {@code reasoningEffort} (optional) is sent as {@code reasoning_effort};
 * for Gemini 2.5 Flash, {@code none} turns thinking off so thinking tokens do not use up the answer budget.
 */
public class OpenAiCompatibleClient implements LlmClient {
    private final RestClient http;
    private final String model;
    private final String reasoningEffort;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, null);
    }

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, Duration timeout, String reasoningEffort) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) b.defaultHeader("Authorization", "Bearer " + apiKey);
        this.http = b.build();
        this.model = model;
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.trim();
    }

    static Map<String, Object> requestBody(String model, List<Message> messages, int maxTokens, String reasoningEffort) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort);
        return body;
    }

    /** Retry-After in seconds (the only form the providers use); null when absent or unreadable. */
    static Duration retryAfter(org.springframework.http.HttpHeaders headers) {
        String v = headers == null ? null : headers.getFirst("Retry-After");
        if (v == null) return null;
        try {
            long s = (long) Math.ceil(Double.parseDouble(v.trim()));
            return s > 0 && s <= 86_400 ? Duration.ofSeconds(s) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String answerText(JsonNode res) {
        if (res == null || !res.path("choices").isArray() || res.path("choices").isEmpty())
            throw new IllegalStateException("The AI provider returned no answer");
        String text = res.path("choices").get(0).path("message").path("content").asText("");
        if (text.isBlank())
            throw new IllegalStateException("The AI provider returned an empty answer; for Gemini 2.5 set "
                    + "AIO_ASSISTANT_REASONING_EFFORT=none or raise AIO_ASSISTANT_MAX_OUTPUT_TOKENS");
        return text;
    }

    @Override
    public Reply complete(List<Message> messages, int maxTokens) {
        Map<String, Object> body = requestBody(model, messages, maxTokens, reasoningEffort);
        JsonNode res;
        try {
            res = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 429) throw new RateLimited(retryAfter(e.getResponseHeaders()));
            throw new IllegalStateException("The AI provider answered HTTP " + code);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new IllegalStateException("The AI provider did not answer in time or could not be reached; try again later");
        }
        String text = answerText(res);
        JsonNode usage = res.path("usage");
        return new Reply(text, res.path("model").asText(model),
                usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
    }
}
