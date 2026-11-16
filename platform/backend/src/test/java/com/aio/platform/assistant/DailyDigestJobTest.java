package com.aio.platform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aio.platform.domain.AssistantReport;
import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.Project;
import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.AssistantReportRepo;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import com.aio.platform.service.AuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DailyDigestJobTest {
    private final CiRunRepo runs = mock(CiRunRepo.class);
    private final PromotionRepo promotions = mock(PromotionRepo.class);
    private final ProjectRepo projects = mock(ProjectRepo.class);
    private final AssistantReportRepo reports = mock(AssistantReportRepo.class);
    private final List<List<LlmClient.Message>> sent = new ArrayList<>();
    private final LlmClient llm = (messages, max) -> {
        sent.add(messages);
        return new LlmClient.Reply("- shop: deployment to prod is waiting for a decision [1]", "gemini-2.5-flash", 900, 40);
    };

    private DailyDigestJob job(String utcNow, LlmClient client) {
        Clock clock = Clock.fixed(Instant.parse(utcNow), ZoneOffset.UTC);
        var service = new AssistantService(new KnowledgeBase(Map.of()), client, mock(AuditService.class), clock, true,
                "gemini-2.5-flash", "generativelanguage.googleapis.com", 2000, 800, 20, 300, 5);
        @SuppressWarnings("unchecked") ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable(any())).thenReturn(clock);
        when(reports.save(any())).thenAnswer(i -> i.getArgument(0));
        return new DailyDigestJob(service, runs, promotions, projects, reports, clocks, true, 9, "Asia/Kolkata", Duration.ofMinutes(1));
    }

    private static CiRun run(long id, String status, String message) {
        CiRun r = new CiRun();
        r.id = id;
        r.projectId = 1L;
        r.status = status;
        r.trigger = "PUSH";
        r.message = message;
        return r;
    }

    private static Promotion promotion(long id, String status) {
        Promotion p = new Promotion();
        p.id = id;
        p.projectId = 1L;
        p.fromEnv = "staging";
        p.toEnv = "prod";
        p.kind = "PROMOTE";
        p.status = status;
        p.requestedBy = "ann";
        p.message = "waiting for an approver";
        return p;
    }

    @Test
    void notBeforeTheConfiguredHourInIndia() {
        // 03:00 UTC = 08:30 IST
        assertThat(job("2026-11-16T03:00:00Z", llm).runIfDue()).isNull();
        verify(runs, never()).findByCreatedAtAfterOrderByIdAsc(any());
    }

    @Test
    void onceADay() {
        when(reports.existsByKindAndDay(DailyDigestJob.KIND, LocalDate.of(2026, 11, 16))).thenReturn(true);
        assertThat(job("2026-11-16T05:00:00Z", llm).runIfDue()).isNull();
        assertThat(sent).isEmpty();
    }

    @Test
    void aQuietDayCostsNoModelCall() {
        AssistantReport r = job("2026-11-16T05:00:00Z", llm).runIfDue();
        assertThat(r.text).isEqualTo(DailyDigestJob.QUIET);
        assertThat(r.model).isEqualTo("none");
        assertThat(sent).isEmpty();
    }

    @Test
    void summarisesFailuresAndPendingDecisionsWithoutSecrets() {
        Project shop = new Project();
        shop.id = 1L;
        shop.name = "shop";
        when(projects.findAllById(any())).thenReturn(List.of(shop));
        when(runs.findByCreatedAtAfterOrderByIdAsc(any())).thenReturn(List.of(
                run(7, "SUCCEEDED", null), run(8, "FAILED", "push failed: token ghp_abcdefghijklmnopqrstuvwxyz0123456789 rejected")));
        when(promotions.findByRequestedAtAfterOrderByIdAsc(any())).thenReturn(List.of(promotion(3, "PENDING")));

        AssistantReport r = job("2026-11-16T05:00:00Z", llm).runIfDue();

        assertThat(r.day).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(r.basis).isEqualTo("runs=2, deployments=1");
        assertThat(r.text).contains("waiting for a decision");
        String prompt = sent.get(0).get(1).content();
        assertThat(prompt).contains("failed run #8 shop").contains("deployment #3 shop staging to prod")
                .contains("{FAILED=1, SUCCEEDED=1}").doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123456789");
        assertThat(sent.get(0).get(0).content()).contains("morning summary").contains("reference DATA");
    }

    @Test
    void aFailingProviderIsTriedAtMostThreeTimesADay() {
        when(runs.findByCreatedAtAfterOrderByIdAsc(any())).thenReturn(List.of(run(8, "FAILED", "x")));
        int[] calls = {0};
        var job = job("2026-11-16T05:00:00Z", (m, max) -> { calls[0]++; throw new LlmClient.RateLimited(null); });
        for (int i = 0; i < 5; i++) job.tick();
        assertThat(calls[0]).isEqualTo(3);
        verify(reports, never()).save(any());
    }
}
