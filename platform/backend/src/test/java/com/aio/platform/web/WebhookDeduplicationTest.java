package com.aio.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.WebhookDeliveryRepo;
import com.aio.platform.service.RunService;
import com.aio.platform.service.WebhookInbox;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The controller with a real inbox: one build per X-GitHub-Delivery, whatever replays it. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "logging.level.org.hibernate.SQL=OFF", "spring.datasource.url=jdbc:h2:mem:webhookdedup;MODE=PostgreSQL"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookDeduplicationTest {
    private static final String SECRET = "s3cret";
    private static final byte[] PUSH = """
            {"ref":"refs/heads/main","after":"0123456789abcdef0123456789abcdef01234567","sender":{"login":"dev"}}"""
            .getBytes(StandardCharsets.UTF_8);

    @Autowired WebhookDeliveryRepo deliveries;
    RunService runs;
    WebhookController controller;
    Project project;

    @BeforeEach
    void setUp() {
        deliveries.deleteAll();
        project = new Project();
        project.id = 7L;
        project.name = "demo";
        project.defaultBranch = "main";
        project.webhookSecret = SECRET;
        ProjectRepo projects = mock(ProjectRepo.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        runs = mock(RunService.class);
        CiRun run = new CiRun();
        run.id = 99L;
        when(runs.trigger(any(), anyString(), anyString(), anyString(), anyBoolean())).thenReturn(run);
        controller = new WebhookController(projects, runs, new ObjectMapper(),
                new WebhookInbox(deliveries, Duration.ofMinutes(2), Duration.ofDays(14)));
    }

    @Test
    void aReplayedPushStartsOneBuildAndGetsTheSameReply() throws Exception {
        var first = controller.receive(7L, "push", "d-1", sign(PUSH), PUSH);
        var replay = controller.receive(7L, "push", "d-1", sign(PUSH), PUSH);

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getBody()).isEqualTo("queued");
        assertThat(replay.getHeaders().getFirst("X-AIO-Webhook-Replay")).isEqualTo("true");
        verify(runs, times(1)).trigger(eq(project), eq("0123456789abcdef0123456789abcdef01234567"), eq("PUSH"), eq("github:dev"), eq(true));
        assertThat(deliveries.findByProjectIdAndDeliveryId(7L, "d-1").orElseThrow().runId).isEqualTo(99L);
    }

    @Test
    void differentDeliveriesOfTheSamePushEachBuild() throws Exception {
        controller.receive(7L, "push", "d-2", sign(PUSH), PUSH);
        controller.receive(7L, "push", "d-3", sign(PUSH), PUSH);
        verify(runs, times(2)).trigger(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void anIgnoredEventIsReplayedAsIgnored() throws Exception {
        byte[] ping = "{\"zen\":\"hi\"}".getBytes(StandardCharsets.UTF_8);
        controller.receive(7L, "ping", "d-4", sign(ping), ping);
        var replay = controller.receive(7L, "ping", "d-4", sign(ping), ping);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo("ignored event");
        verifyNoInteractions(runs);
    }

    @Test
    void aFailedAttemptReleasesTheDeliveryForARetry() throws Exception {
        when(runs.trigger(any(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Legacy builds are still active"))
                .thenReturn(new CiRun());
        assertThatThrownBy(() -> controller.receive(7L, "push", "d-5", sign(PUSH), PUSH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(deliveries.findByProjectIdAndDeliveryId(7L, "d-5")).isEmpty();

        assertThat(controller.receive(7L, "push", "d-5", sign(PUSH), PUSH).getStatusCode().value()).isEqualTo(202);
        verify(runs, times(2)).trigger(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void anUnsignedRequestNeverReachesTheInbox() throws Exception {
        var reply = controller.receive(7L, "push", "d-6", "sha256=00", PUSH);
        assertThat(reply.getStatusCode().value()).isEqualTo(401);
        assertThat(deliveries.count()).isZero();
        verifyNoInteractions(runs);
    }

    @Test
    void aDeliveryWithoutAnIdStillBuildsAsBefore() throws Exception {
        controller.receive(7L, "push", null, sign(PUSH), PUSH);
        controller.receive(7L, "push", null, sign(PUSH), PUSH);
        verify(runs, times(2)).trigger(any(), anyString(), anyString(), anyString(), anyBoolean());
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void aReplayDuringTheFirstAttemptAsksTheBufferToRetry() throws Exception {
        // the buffer timed out after 5 s and replays while the first request is still starting the build
        new WebhookInbox(deliveries, Duration.ofMinutes(2), Duration.ofDays(14)).claim(7L, "d-8", "push", PUSH);
        var reply = controller.receive(7L, "push", "d-8", sign(PUSH), PUSH);
        assertThat(reply.getStatusCode().value()).isEqualTo(429);
        assertThat(reply.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        verifyNoInteractions(runs);
    }

    @Test
    void aReusedIdWithAnotherPayloadIsRejected() throws Exception {
        byte[] other = new String(PUSH, StandardCharsets.UTF_8).replace("01234567\"", "76543210\"").getBytes(StandardCharsets.UTF_8);
        controller.receive(7L, "push", "d-7", sign(PUSH), PUSH);
        var reply = controller.receive(7L, "push", "d-7", sign(other), other);
        assertThat(reply.getStatusCode().value()).isEqualTo(409);
        verify(runs, times(1)).trigger(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    private static String sign(byte[] body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
