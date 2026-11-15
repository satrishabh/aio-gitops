package com.aio.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.WebhookDeliveryRepo;
import com.aio.platform.service.RunService;
import com.aio.platform.service.WebhookInbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:webhookinbox;MODE=PostgreSQL;NON_KEYWORDS=TRIGGER"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookIdempotencyTest {
    @Autowired WebhookDeliveryRepo deliveries;
    @Autowired CiRunRepo runRepo;

    final ProjectRepo projects = mock(ProjectRepo.class);
    final RunService runs = mock(RunService.class);
    WebhookInbox inbox;
    WebhookController controller;
    Project shop;

    static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    static final byte[] PUSH = ("{\"ref\":\"refs/heads/main\",\"after\":\"" + SHA + "\",\"sender\":{\"login\":\"dev\"}}")
            .getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        deliveries.deleteAll();
        runRepo.deleteAll();
        inbox = new WebhookInbox(deliveries);
        controller = new WebhookController(projects, runs, new ObjectMapper(), inbox, runRepo);
        shop = new Project();
        shop.id = 7L;
        shop.name = "shop";
        shop.defaultBranch = "main";
        shop.webhookSecret = "s3cret";
        when(projects.findById(7L)).thenReturn(Optional.of(shop));
    }

    @Test
    void inboxAcceptsADeliveryOnce() {
        assertThat(inbox.firstTime("d-1", 7L, "push")).isTrue();
        assertThat(inbox.firstTime("d-1", 7L, "push")).isFalse();
        assertThat(inbox.firstTime("d-2", 7L, "push")).isTrue();
    }

    @Test
    void redeliveredWebhookTriggersOnlyOneBuild() throws Exception {
        var first = controller.receive(7L, "push", sign(PUSH), "delivery-1", PUSH);
        var again = controller.receive(7L, "push", sign(PUSH), "delivery-1", PUSH);
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(again.getBody()).startsWith("duplicate delivery");
        verify(runs, times(1)).trigger(any(), eq(SHA), eq("PUSH"), any(), eq(true));
        assertThat(deliveries.findById("delivery-1").orElseThrow().outcome).startsWith("202");
    }

    @Test
    void sameCommitAlreadyBuildingIsNotBuiltAgainEvenWithANewDeliveryId() throws Exception {
        CiRun running = new CiRun();
        running.projectId = 7L;
        running.gitRef = SHA;
        running.deploy = true;
        running.status = CiRun.RUNNING;
        runRepo.save(running);
        var r = controller.receive(7L, "push", sign(PUSH), "delivery-2", PUSH);
        assertThat(r.getBody()).isEqualTo("commit already built or building");
        verify(runs, never()).trigger(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void failedCommitCanBeRebuiltByAPush() throws Exception {
        CiRun failed = new CiRun();
        failed.projectId = 7L;
        failed.gitRef = SHA;
        failed.deploy = true;
        failed.status = CiRun.FAILED;
        runRepo.save(failed);
        controller.receive(7L, "push", sign(PUSH), "delivery-3", PUSH);
        verify(runs).trigger(any(), eq(SHA), eq("PUSH"), any(), eq(true));
    }

    @Test
    void processingFailureForgetsTheDeliverySoTheRetryIsProcessed() throws Exception {
        when(runs.trigger(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Kubernetes unavailable"))
                .thenReturn(new CiRun());
        assertThatThrownBy(() -> controller.receive(7L, "push", sign(PUSH), "delivery-4", PUSH))
                .hasMessageContaining("Kubernetes unavailable");
        assertThat(deliveries.existsById("delivery-4")).isFalse();
        var retry = controller.receive(7L, "push", sign(PUSH), "delivery-4", PUSH);
        assertThat(retry.getStatusCode().value()).isEqualTo(202);
        verify(runs, times(2)).trigger(any(), eq(SHA), eq("PUSH"), any(), eq(true));
    }

    @Test
    void badSignatureIsRejectedBeforeTheInbox() throws Exception {
        var r = controller.receive(7L, "push", "sha256=00", "delivery-5", PUSH);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(deliveries.existsById("delivery-5")).isFalse();   // forged requests cannot burn delivery ids
    }

    private static String sign(byte[] body) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
