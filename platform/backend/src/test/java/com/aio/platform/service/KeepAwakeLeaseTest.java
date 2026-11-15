package com.aio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.CiRun;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ScanRunRepo;
import com.aio.platform.saga.SagaEngine;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeepAwakeLeaseTest {
    final SagaEngine sagas = mock(SagaEngine.class);
    final CiRunRepo runs = mock(CiRunRepo.class);
    final ScanRunRepo scans = mock(ScanRunRepo.class);
    final List<String> pokes = new CopyOnWriteArrayList<>();
    HttpServer sablier;
    String url;

    @BeforeEach
    void fakeSablier() throws Exception {
        sablier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sablier.createContext("/api/strategies/poke", ex -> {
            pokes.add(ex.getRequestURI().getQuery());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        sablier.start();
        url = "http://127.0.0.1:" + sablier.getAddress().getPort() + "/";
        when(runs.findByStatusIn(any())).thenReturn(List.of());
        when(scans.findByStatusIn(any())).thenReturn(List.of());
    }

    @AfterEach
    void stop() {
        sablier.stop(0);
    }

    @Test
    void idlePlatformIsLeftToSleep() {
        new KeepAwakeLease(sagas, runs, scans, url, "aio", "30m").renewIfBusy();
        assertThat(pokes).isEmpty();
    }

    @Test
    void activeSagaRenewsTheGroupSession() {
        when(sagas.anyActive()).thenReturn(true);
        new KeepAwakeLease(sagas, runs, scans, url, "aio", "30m").renewIfBusy();
        assertThat(pokes).containsExactly("group=aio&session_duration=30m");
    }

    @Test
    void runningBuildRenewsTheGroupSession() {
        when(runs.findByStatusIn(CiRun.ACTIVE)).thenReturn(List.of(new CiRun()));
        new KeepAwakeLease(sagas, runs, scans, url, "aio", "30m").renewIfBusy();
        assertThat(pokes).hasSize(1);
    }

    @Test
    void disabledWithoutUrl() {
        when(sagas.anyActive()).thenReturn(true);
        new KeepAwakeLease(sagas, runs, scans, "", "aio", "30m").renewIfBusy();
        assertThat(pokes).isEmpty();
        verifyNoInteractions(runs);
    }

    @Test
    void sablierDownIsLoggedNotThrown() {
        when(sagas.anyActive()).thenReturn(true);
        sablier.stop(0);
        new KeepAwakeLease(sagas, runs, scans, url, "aio", "30m").renewIfBusy();   // must not throw
    }

    @Test
    void pokeUrlIsEncoded() {
        assertThat(KeepAwakeLease.pokeUrl("http://sablier:10000", "my group", "30m"))
                .isEqualTo("http://sablier:10000/api/strategies/poke?group=my+group&session_duration=30m");
    }
}
