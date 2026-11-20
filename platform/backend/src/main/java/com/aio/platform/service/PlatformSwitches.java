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
 * <p>
 * A switch either sets a pipeline parameter while on (Biome: {@code lint=auto}) or gates a choice the platform
 * validates itself (Podman / Buildah: the {@code buildah} build strategy, see {@link BuildStrategies}); the latter
 * passes nothing to the pipeline.
 */
@Service
public class PlatformSwitches {
    private static final Logger log = LoggerFactory.getLogger(PlatformSwitches.class);

    /** Biome lint step in CI (phase 5). */
    public static final String BIOME = "biome";
    /** Buildah build strategy (catalog connector "Podman / Buildah (CI step)", phase 6). */
    public static final String PODMAN = "podman";

    /** Core connectors that are CI pipeline steps, switched on and off here (not installed through Argo CD). */
    public static final Set<String> PIPELINE_STEPS = Set.of(BIOME, PODMAN);

    /** A pipeline parameter and the value a switch sets while it is on. */
    record PipelineParam(String name, String value) {}

    /**
     * Parameters of the switches that set one; a switch missing here only gates a platform choice. Off = not passed:
     * the pipeline default is off.
     */
    static final Map<String, PipelineParam> PIPELINE_PARAMS = Map.of(BIOME, new PipelineParam("lint", "auto"));

    static {
        if (!PIPELINE_STEPS.containsAll(PIPELINE_PARAMS.keySet()))
            throw new IllegalStateException("every pipeline parameter needs its switch in PIPELINE_STEPS");
    }

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
     * nothing, so a pipeline that does not declare the parameter yet keeps working; switches without a parameter
     * (podman) never pass anything.
     */
    public Map<String, String> pipelineParams() {
        Map<String, String> m = new java.util.TreeMap<>();
        PIPELINE_PARAMS.forEach((name, p) -> { if (on(name)) m.put(p.name(), p.value()); });
        return m;
    }
}
