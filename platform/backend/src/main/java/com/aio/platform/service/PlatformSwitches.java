package com.aio.platform.service;

import com.aio.platform.domain.PlatformSwitch;
import com.aio.platform.repo.PlatformSwitchRepo;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Switches for features that are installed with the platform but stay off until an admin switches them on.
 * Each is named after its catalog connector, so the Connectors page switches it together with that connector's phase.
 * Everything is off by default (no row) and when the database cannot be read.
 */
@Service
public class PlatformSwitches {
    private static final Logger log = LoggerFactory.getLogger(PlatformSwitches.class);

    /** Core connectors that are CI pipeline steps, switched on and off here (not installed through Argo CD). */
    public static final Set<String> PIPELINE_STEPS = Set.of("biome");

    /** Pipeline parameter and value each switch sets while on. Off = not passed: the pipeline default is off. */
    static final Map<String, String[]> PIPELINE_PARAMS = Map.of("biome", new String[] {"lint", "auto"});

    private final PlatformSwitchRepo repo;

    public PlatformSwitches(PlatformSwitchRepo repo) {
        this.repo = repo;
    }

    public boolean on(String name) {
        try {
            return repo.findById(name).map(s -> s.enabled).orElse(false);
        } catch (RuntimeException e) {
            log.warn("switch {} unreadable, treated as off: {}", name, e.getMessage());
            return false;
        }
    }

    public void set(String name, boolean enabled, String actor) {
        if (!PIPELINE_STEPS.contains(name)) throw new IllegalArgumentException("Unknown switch " + name);
        PlatformSwitch s = repo.findById(name).orElseGet(PlatformSwitch::new);
        s.name = name;
        s.enabled = enabled;
        s.changedBy = actor == null ? "unknown" : actor;
        s.changedAt = Instant.now();
        repo.save(s);
    }

    /**
     * Pipeline parameters of the switches that are on (e.g. lint=auto once Biome is on). Switches that are off pass
     * nothing, so a pipeline that does not declare the parameter yet keeps working.
     */
    public Map<String, String> pipelineParams() {
        Map<String, String> m = new java.util.TreeMap<>();
        PIPELINE_PARAMS.forEach((name, p) -> { if (on(name)) m.put(p[0], p[1]); });
        return m;
    }
}
