package com.aio.platform.service;

import com.aio.platform.domain.Project;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-project runtime config (env vars + CPU/memory) and how it is rendered into a values file.
 * Pure helpers so they can be unit tested without GitHub.
 */
public final class RuntimeConfig {
    private RuntimeConfig() {}

    public record Env(String name, String value) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    private static final Pattern CPU = Pattern.compile("^(\\d{1,6})(\\.\\d{1,3})?(m?)$");
    private static final Pattern MEMORY = Pattern.compile("^(\\d{1,6})(Ki|Mi|Gi)$");
    static final int MAX_ENV = 100, MAX_VALUE = 4096;

    // ------------------------------------------------------------------ validation

    /** Validates and normalises the list; throws IllegalArgumentException with a user-facing message. */
    public static List<Env> validEnv(List<Env> in) {
        if (in == null) return List.of();
        if (in.size() > MAX_ENV) throw new IllegalArgumentException("at most " + MAX_ENV + " environment variables");
        List<Env> out = new ArrayList<>();
        var seen = new HashSet<String>();
        for (Env e : in) {
            String name = e == null || e.name() == null ? "" : e.name().trim();
            if (name.isEmpty()) continue; // blank rows from the editor are ignored
            if (!ENV_NAME.matcher(name).matches())
                throw new IllegalArgumentException("invalid variable name '" + name + "' (letters, digits and _; must not start with a digit)");
            if (!seen.add(name)) throw new IllegalArgumentException("duplicate variable " + name);
            String value = e.value() == null ? "" : e.value();
            if (value.length() > MAX_VALUE) throw new IllegalArgumentException(name + " is longer than " + MAX_VALUE + " characters");
            out.add(new Env(name, value));
        }
        return out;
    }

    /** CPU in millicores, e.g. "250m" -> 250, "0.5" -> 500, "2" -> 2000. */
    static long cpuMillis(String v, String field) {
        Matcher m = CPU.matcher(v == null ? "" : v.trim());
        if (!m.matches()) throw new IllegalArgumentException(field + " must look like 250m or 0.5 (cores)");
        double n = Double.parseDouble(m.group(1) + (m.group(2) == null ? "" : m.group(2)));
        long millis = Math.round(m.group(3).isEmpty() ? n * 1000 : n);
        if (millis < 10 || millis > 16000) throw new IllegalArgumentException(field + " must be between 10m and 16 cores");
        return millis;
    }

    /** Memory in MiB, e.g. "512Mi" -> 512, "1Gi" -> 1024. */
    static long memoryMiB(String v, String field) {
        Matcher m = MEMORY.matcher(v == null ? "" : v.trim());
        if (!m.matches()) throw new IllegalArgumentException(field + " must look like 256Mi or 1Gi");
        long n = Long.parseLong(m.group(1));
        long mib = switch (m.group(2)) { case "Ki" -> n / 1024; case "Gi" -> n * 1024; default -> n; };
        if (mib < 16 || mib > 65536) throw new IllegalArgumentException(field + " must be between 16Mi and 64Gi");
        return mib;
    }

    /** Validates all four values and that each request does not exceed its limit (Kubernetes rejects that). */
    public static void validateResources(String cpuReq, String cpuLim, String memReq, String memLim) {
        if (cpuMillis(cpuReq, "CPU request") > cpuMillis(cpuLim, "CPU limit"))
            throw new IllegalArgumentException("CPU request cannot be higher than the CPU limit");
        if (memoryMiB(memReq, "Memory request") > memoryMiB(memLim, "Memory limit"))
            throw new IllegalArgumentException("memory request cannot be higher than the memory limit");
    }

    // ------------------------------------------------------------------ (de)serialisation

    public static List<Env> readEnv(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return JSON.readValue(json, new TypeReference<List<Env>>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }

    public static String writeEnv(List<Env> env) {
        try { return JSON.writeValueAsString(env); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ YAML

    /** JSON strings are valid YAML double-quoted scalars, so this is a safe quoting for any value. */
    private static String quote(String s) {
        try { return JSON.writeValueAsString(s); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    static String envBlock(List<Env> env) {
        if (env.isEmpty()) return "env: []\n";
        StringBuilder b = new StringBuilder("env:\n");
        for (Env e : env) b.append("  - name: ").append(e.name()).append("\n    value: ").append(quote(e.value())).append('\n');
        return b.toString();
    }

    static String resourcesBlock(Project p) {
        return "resources:\n"
                + "  requests: { cpu: " + quote(p.cpuRequest) + ", memory: " + quote(p.memoryRequest) + " }\n"
                + "  limits: { cpu: " + quote(p.cpuLimit) + ", memory: " + quote(p.memoryLimit) + " }\n";
    }

    /** Replaces (or appends) a top-level YAML block: the "key:" line plus every indented line after it. */
    static String setBlock(String yaml, String key, String block) {
        Pattern p = Pattern.compile("(?m)^" + Pattern.quote(key) + ":.*(?:\\R|$)(?:[ \\t]+.*(?:\\R|$)|-.*(?:\\R|$))*");
        Matcher m = p.matcher(yaml);
        if (m.find()) return yaml.substring(0, m.start()) + block + yaml.substring(m.end());
        return (yaml.isEmpty() || yaml.endsWith("\n") ? yaml : yaml + "\n") + block;
    }

    /** Writes this project's env: and resources: into a values file. */
    public static String apply(String yaml, Project p) {
        return setBlock(setBlock(yaml, "env", envBlock(readEnv(p.envVars))), "resources", resourcesBlock(p));
    }
}
