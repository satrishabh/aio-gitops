package com.aio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.CiRun;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.RunStepRepo;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** LEGACY runs whose PipelineRun name was never saved used to stay PENDING forever. */
class StuckRunRecoveryTest {
    final CiRunRepo runs = mock(CiRunRepo.class);
    final TektonService tekton = mock(TektonService.class);
    final PipelineWatcher watcher = new PipelineWatcher(runs, mock(RunStepRepo.class), tekton, mock(AuditService.class));

    @Test
    void legacyRunIsReattachedByLabel() {
        CiRun run = legacy(Instant.now());
        when(tekton.recoverRunByLabel(run)).thenReturn("shop-ci-x7k2p");
        watcher.sync(run);
        assertThat(run.pipelineRunName).isEqualTo("shop-ci-x7k2p");
        verify(runs).save(run);
        verify(tekton).getPipelineRun("shop-ci-x7k2p");
        verify(tekton, never()).recoverIndependentRun(any());
    }

    @Test
    void legacyRunThatNeverStartedFailsAfterTenMinutes() {
        CiRun run = legacy(Instant.now().minus(Duration.ofMinutes(11)));
        watcher.sync(run);
        assertThat(run.status).isEqualTo(CiRun.FAILED);
        assertThat(CiRun.ACTIVE).doesNotContain(run.status);   // no longer blocks independent delivery
    }

    @Test
    void youngLegacyRunWithoutNameJustWaits() {
        CiRun run = legacy(Instant.now());
        watcher.sync(run);
        assertThat(run.status).isEqualTo(CiRun.PENDING);
        verify(runs, never()).save(any());
    }

    private static CiRun legacy(Instant created) {
        CiRun r = new CiRun();
        r.id = 42L;
        r.projectId = 1L;
        r.deliveryMode = "LEGACY";
        r.status = CiRun.PENDING;
        r.createdAt = created;
        return r;
    }
}
