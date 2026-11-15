package com.aio.platform.service;

import com.aio.platform.domain.CiRun;
import com.aio.platform.domain.ScanRun;
import com.aio.platform.repo.CiRunRepo;
import com.aio.platform.repo.ScanRunRepo;
import com.aio.platform.saga.SagaEngine;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the scale-to-zero group awake while AIO has work in flight (docs/architecture/AIO-V2-ARCHITECTURE.md §3.4).
 *
 * <p>Sablier puts AIO, Argo CD and Tekton to sleep 30 minutes after the last HTTP request through Traefik. A build
 * or a promotion can outlive that when the browser tab is closed, and then Tekton stops advancing the pipeline and
 * the saga stalls. While a saga, CI run or scan is active, this renews the group's session through Sablier's
 * "poke" endpoint every few minutes. With nothing in flight it does nothing, so the group sleeps on schedule.
 *
 * <p>Disabled unless {@code app.keep-awake.sablier-url} is set (env {@code AIO_KEEP_AWAKE_SABLIER_URL}).
 */
@Component
public class KeepAwakeLease {
    private static final Logger log = LoggerFactory.getLogger(KeepAwakeLease.class);

    private final SagaEngine sagas;
    private final CiRunRepo runs;
    private final ScanRunRepo scans;
    private final String sablierUrl;
    private final String group;
    private final String session;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public KeepAwakeLease(SagaEngine sagas, CiRunRepo runs, ScanRunRepo scans,
                          @Value("${app.keep-awake.sablier-url:}") String sablierUrl,
                          @Value("${app.keep-awake.group:aio}") String group,
                          @Value("${app.keep-awake.session:30m}") String session) {
        this.sagas = sagas;
        this.runs = runs;
        this.scans = scans;
        this.sablierUrl = sablierUrl == null ? "" : sablierUrl.replaceAll("/+$", "");
        this.group = group;
        this.session = session;
    }

    /** True when something would stall if the group went to sleep now. */
    public boolean busy() {
        return sagas.anyActive() || !runs.findByStatusIn(CiRun.ACTIVE).isEmpty() || !scans.findByStatusIn(ScanRun.ACTIVE).isEmpty();
    }

    /** Every 5 minutes (well inside the 30 min session): renew only if busy. */
    @Scheduled(fixedDelayString = "${app.keep-awake.interval-ms:300000}", initialDelayString = "${app.keep-awake.initial-delay-ms:60000}")
    public void renewIfBusy() {
        if (sablierUrl.isEmpty()) return;
        try {
            if (busy()) renew();
        } catch (Exception e) {
            log.warn("keep-awake check failed: {}", e.toString());
        }
    }

    void renew() throws Exception {
        URI uri = URI.create(pokeUrl(sablierUrl, group, session));
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() / 100 != 2) log.warn("Sablier poke for group {} answered {}", group, r.statusCode());
        else log.debug("renewed Sablier session for group {} ({})", group, session);
    }

    static String pokeUrl(String base, String group, String session) {
        return base + "/api/strategies/poke?group=" + URLEncoder.encode(group, StandardCharsets.UTF_8)
                + "&session_duration=" + URLEncoder.encode(session, StandardCharsets.UTF_8);
    }
}
