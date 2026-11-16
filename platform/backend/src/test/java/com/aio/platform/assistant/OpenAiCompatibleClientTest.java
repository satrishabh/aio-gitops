package com.aio.platform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<LlmClient.Message> messages = List.of(new LlmClient.Message("user", "hi"));

    @Test
    void reasoningEffortIsSentOnlyWhenConfigured() {
        assertThat(OpenAiCompatibleClient.requestBody("gemini-2.5-flash", messages, 800, "none"))
                .containsEntry("reasoning_effort", "none").containsEntry("max_tokens", 800);
        assertThat(OpenAiCompatibleClient.requestBody("llama", messages, 800, null)).doesNotContainKey("reasoning_effort");
    }

    @Test
    void readsAGeminiStyleAnswer() throws Exception {
        var res = json.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Approve it [1].\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}");
        assertThat(OpenAiCompatibleClient.answerText(res)).isEqualTo("Approve it [1].");
    }

    @Test
    void anEmptyAnswerIsAClearErrorNotABlankReply() throws Exception {
        // what a thinking model returns when thinking used the whole max_tokens budget
        var res = json.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"length\"}]}");
        assertThatThrownBy(() -> OpenAiCompatibleClient.answerText(res)).hasMessageContaining("REASONING_EFFORT=none");
        assertThatThrownBy(() -> OpenAiCompatibleClient.answerText(json.readTree("{\"choices\":[]}")))
                .hasMessageContaining("no answer");
    }
}
