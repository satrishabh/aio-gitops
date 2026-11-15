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
 */
public class OpenAiCompatibleClient implements LlmClient {
    private final RestClient http;
    private final String model;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) b.defaultHeader("Authorization", "Bearer " + apiKey);
        this.http = b.build();
        this.model = model;
    }

    @Override
    public Reply complete(List<Message> messages, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        JsonNode res;
        try {
            res = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            throw new IllegalStateException(code == 429 ? "The AI provider's free quota is used up for now; try again later"
                    : "The AI provider answered HTTP " + code);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new IllegalStateException("The AI provider did not answer in time or could not be reached; try again later");
        }
        if (res == null || !res.path("choices").isArray() || res.path("choices").isEmpty())
            throw new IllegalStateException("The AI provider returned no answer");
        String text = res.path("choices").get(0).path("message").path("content").asText("");
        JsonNode usage = res.path("usage");
        return new Reply(text, res.path("model").asText(model),
                usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
    }
}
