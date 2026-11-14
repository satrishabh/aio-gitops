package com.aio.platform.service;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RuntimeWorkerGateTest {
    @Test
    void disabledWorkersReturnBeforeAccessingRepositoriesOrExternalClients() {
        // Real methods, deliberately absent collaborators: any unguarded worker fails.
        var deployment = mock(DeploymentPoller.class, CALLS_REAL_METHODS);
        var domains = mock(DomainWatcher.class, CALLS_REAL_METHODS);
        var pipeline = mock(PipelineWatcher.class, CALLS_REAL_METHODS);
        var scan = mock(ScanWatcher.class, CALLS_REAL_METHODS);
        var release = mock(BuildReleaseWorker.class, CALLS_REAL_METHODS);
        var cleanup = mock(RegistryRunCleanup.class, CALLS_REAL_METHODS);
        var cluster = mock(ClusterService.class, CALLS_REAL_METHODS);
        for (Object worker : new Object[]{deployment, domains, pipeline, scan, release, cleanup, cluster}) {
            ReflectionTestUtils.setField(worker, "workersEnabled", false);
        }
        deployment.poll(); domains.poll(); pipeline.poll(); scan.poll(); scan.prune();
        release.poll(); cleanup.cleanup(); cluster.prune(); cluster.reconcileAll();
    }
}
