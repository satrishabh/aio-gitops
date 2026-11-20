package com.aio.platform.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a project's image is built: the pipeline parameter {@code build-strategy}, passed to the build-image task as
 * BUILD_STRATEGY (tekton/tasks/build-image.yaml). {@code buildah} (the Dockerfile built with Buildah) is held off with
 * its catalog connector, Podman / Buildah (phase 6): it can be chosen, and runs, only while that switch is on.
 */
public final class BuildStrategies {
    public static final String AUTO = "auto";
    public static final String DOCKERFILE = "dockerfile";
    public static final String BUILDPACKS = "buildpacks";
    public static final String BUILDAH = "buildah";

    /** Every strategy the build-image task understands, in display order. */
    public static final List<String> ALL = List.of(AUTO, DOCKERFILE, BUILDPACKS, BUILDAH);

    /** Strategies that need a platform switch, by switch name. */
    static final Map<String, String> GATED = Map.of(BUILDAH, PlatformSwitches.PODMAN);

    private BuildStrategies() {}

    /** Whether the strategy may be used now; switches null (not wired) = every gated strategy is off. */
    public static boolean available(String strategy, PlatformSwitches switches) {
        String gate = GATED.get(strategy);
        return ALL.contains(strategy) && (gate == null || (switches != null && switches.on(gate)));
    }

    /** Each strategy with whether it can be chosen now (the Settings form offers only those). */
    public static Map<String, Boolean> choices(PlatformSwitches switches) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String s : ALL) m.put(s, available(s, switches));
        return m;
    }

    /**
     * Normalises a chosen strategy (blank = auto) and rejects unknown values and gated ones whose switch is off.
     * Also used when a run starts: an admin may have switched Buildah off after a project chose it.
     */
    public static String validate(String value, PlatformSwitches switches) {
        String v = value == null || value.isBlank() ? AUTO : value.trim().toLowerCase(Locale.ROOT);
        if (!ALL.contains(v)) throw new IllegalArgumentException("buildStrategy must be one of " + ALL + " (got \"" + value + "\")");
        if (!available(v, switches))
            throw new IllegalArgumentException("Build strategy \"" + v + "\" is off until an admin switches on Podman / Buildah"
                    + " (phase 6) under Connectors > Phases. Choose auto, dockerfile or buildpacks instead.");
        return v;
    }
}
