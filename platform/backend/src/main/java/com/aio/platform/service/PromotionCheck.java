package com.aio.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Pre-promotion check and "Copy settings" merge between two env values files (pure, unit tested).
 *
 * <p>A promotion only moves {@code image.tag}; everything else in the target values file stays as it is. When the
 * source env runs with settings the target lacks (env vars, envFrom Secrets, mounted ConfigMaps, a different
 * command), the promoted image starts with the wrong configuration. The check lists those gaps; {@link #merge}
 * copies the runtime settings over without touching anything environment-specific.
 */
public final class PromotionCheck {
    private PromotionCheck() {}

    /** Settings copied as a whole (the source's block replaces the target's). */
    static final List<String> REPLACED = List.of("command", "args", "containerPort", "probes", "podSecurityContext");
    /** List settings merged by identity: target entries are kept, missing source entries are appended. */
    static final List<String> MERGED = List.of("env", "envFrom", "extraVolumes", "extraVolumeMounts");
    /** Never copied: environment-specific (hosts, size, placement) or the promotion itself (image). */
    public static final List<String> NEVER_COPIED = List.of("image", "ingress", "routes", "replicaCount", "resources",
            "spot", "services", "scaledDown", "blueGreen", "imagePullSecrets", "pdb");

    // Chart defaults (helm/app/values.yaml): a key missing from a values file means this value.
    static final int DEFAULT_PORT = 8080;
    static final String DEFAULT_PROBE_PATH = "/health";
    static final Map<String, Object> DEFAULT_POD_SECURITY = Map.of("runAsNonRoot", true, "fsGroup", 10001);

    /** One gap. kind: env | envFrom | volume | volumeMount (copyable) or secret | configMap (must exist in the namespace). */
    /**
     * One gap. kind: env | envFrom | volume | volumeMount (copyable) or secret | configMap (must exist in the namespace).
     * For env: {@code value} is the source's plain value (already in git; null when it comes from {@code valueFrom}),
     * {@code valueFrom} describes the reference instead (never a Secret's content), {@code envSpecific} flags a value
     * that likely names the source env (a host/URL, or dev/staging/prod) and should be checked for the target.
     */
    public record Finding(String kind, String name, String detail, boolean copyable, List<String> commands,
                          String value, String valueFrom, boolean envSpecific) {
        Finding(String kind, String name, String detail, boolean copyable, List<String> commands) {
            this(kind, name, detail, copyable, commands, null, null, false);
        }
    }

    /** A setting whose effective value differs between the two envs (fixed by Copy settings). */
    public record Difference(String key, String from, String to) {}

    /** A Secret or ConfigMap the workload references. */
    public record ObjectRef(String kind, String name) {}

    /** Values-level comparison (no cluster access). */
    public record ValuesDiff(List<Finding> missing, List<Difference> differs, Set<ObjectRef> sourceRefs, Set<ObjectRef> targetRefs) {}

    // ------------------------------------------------------------------ check

    public static ValuesDiff compare(String sourceYaml, String targetYaml, String from, String to) {
        Map<String, Object> src = load(sourceYaml), dst = load(targetYaml);
        List<Finding> missing = new ArrayList<>();

        Set<String> targetEnv = new LinkedHashSet<>();
        for (Map<String, Object> e : maps(dst.get("env"))) targetEnv.add(String.valueOf(e.get("name")));
        for (Map<String, Object> e : maps(src.get("env"))) {
            String name = String.valueOf(e.get("name"));
            if (!targetEnv.contains(name))
            {
                Object value = e.get("value");
                String plain = e.containsKey("valueFrom") || value == null ? null : String.valueOf(value);
                String ref = e.containsKey("valueFrom") ? describeValueFrom(e.get("valueFrom")) : null;
                missing.add(new Finding("env", name, "set in " + from + ", missing in " + to, true, List.of(),
                        plain, ref, plain != null && envSpecific(plain, from)));
            }
        }
        Set<String> targetEnvFrom = new LinkedHashSet<>();
        for (Map<String, Object> e : maps(dst.get("envFrom"))) targetEnvFrom.add(envFromLabel(e));
        for (Map<String, Object> e : maps(src.get("envFrom"))) {
            String label = envFromLabel(e);
            if (!targetEnvFrom.contains(label))
                missing.add(new Finding("envFrom", label, "all keys of this " + label.substring(0, label.indexOf(' '))
                        + " become env vars in " + from + "; " + to + " does not load it", true, List.of()));
        }
        Set<String> targetVolumes = new LinkedHashSet<>();
        for (Map<String, Object> v : maps(dst.get("extraVolumes"))) targetVolumes.add(String.valueOf(v.get("name")));
        for (Map<String, Object> v : maps(src.get("extraVolumes"))) {
            String name = String.valueOf(v.get("name"));
            if (!targetVolumes.contains(name))
                missing.add(new Finding("volume", name, volumeSource(v), true, List.of()));
        }
        Set<String> targetMounts = new LinkedHashSet<>();
        for (Map<String, Object> m : maps(dst.get("extraVolumeMounts"))) targetMounts.add(String.valueOf(m.get("mountPath")));
        for (Map<String, Object> m : maps(src.get("extraVolumeMounts"))) {
            String path = String.valueOf(m.get("mountPath"));
            if (!targetMounts.contains(path))
                missing.add(new Finding("volumeMount", path, "volume " + m.get("name") + " mounted in " + from, true, List.of()));
        }

        List<Difference> differs = new ArrayList<>();
        differ(differs, "command", show(src.get("command")), show(dst.get("command")));
        differ(differs, "args", show(src.get("args")), show(dst.get("args")));
        differ(differs, "containerPort", String.valueOf(port(src)), String.valueOf(port(dst)));
        for (String probe : List.of("liveness", "readiness", "startup"))
            differ(differs, "probes." + probe + ".path", probePath(src, probe), probePath(dst, probe));
        differ(differs, "podSecurityContext", show(podSecurity(src)), show(podSecurity(dst)));

        return new ValuesDiff(missing, differs, refs(src), refs(dst));
    }

    private static void differ(List<Difference> out, String key, String from, String to) {
        if (!Objects.equals(from, to)) out.add(new Difference(key, from, to));
    }

    /** Secrets/ConfigMaps a values file makes the pod depend on (non-optional references only). */
    static Set<ObjectRef> refs(Map<String, Object> doc) {
        Set<ObjectRef> out = new LinkedHashSet<>();
        for (Map<String, Object> e : maps(doc.get("envFrom"))) {
            addRef(out, "secret", e.get("secretRef"), "name");
            addRef(out, "configMap", e.get("configMapRef"), "name");
        }
        for (Map<String, Object> e : maps(doc.get("env"))) {
            Object from = e.get("valueFrom");
            addRef(out, "secret", J.get(from, "secretKeyRef"), "name");
            addRef(out, "configMap", J.get(from, "configMapKeyRef"), "name");
        }
        for (Map<String, Object> v : maps(doc.get("extraVolumes"))) {
            addRef(out, "secret", v.get("secret"), "secretName");
            addRef(out, "configMap", v.get("configMap"), "name");
            for (Map<String, Object> s : J.list(v.get("projected"), "sources")) {
                addRef(out, "secret", s.get("secret"), "name");
                addRef(out, "configMap", s.get("configMap"), "name");
            }
        }
        for (Map<String, Object> s : maps(doc.get("imagePullSecrets"))) addRef(out, "secret", s, "name");
        return out;
    }

    private static void addRef(Set<ObjectRef> out, String kind, Object ref, String key) {
        if (!(ref instanceof Map<?, ?> m) || Boolean.TRUE.equals(m.get("optional"))) return;
        Object name = m.get(key);
        if (name != null && !String.valueOf(name).isBlank()) out.add(new ObjectRef(kind, String.valueOf(name)));
    }

    /**
     * Findings for referenced objects that don't exist in the target namespace. {@code exists} answers by ref
     * (null: unknown); only {@code false} is reported. Never looks at Secret contents.
     */
    public static List<Finding> missingObjects(Set<ObjectRef> refs, Function<ObjectRef, Boolean> exists,
                                               String from, String to, String namespace, String sourceNamespace) {
        List<Finding> out = new ArrayList<>();
        for (ObjectRef r : refs) {
            if (!Boolean.FALSE.equals(exists.apply(r))) continue;
            out.add(new Finding(r.kind(), r.name(),
                    ("secret".equals(r.kind()) ? "Secret" : "ConfigMap") + " " + r.name() + " does not exist in namespace " + namespace
                            + " (it is not in git, so it can't be copied)",
                    false, kubectl(r, namespace, sourceNamespace)));
        }
        return out;
    }

    /** Commands to create the object: by hand, or copied from the source namespace (same cluster or kube context). */
    static List<String> kubectl(ObjectRef r, String namespace, String sourceNamespace) {
        boolean secret = "secret".equals(r.kind());
        String type = secret ? "secret" : "configmap";
        String create = secret
                ? "kubectl -n " + namespace + " create secret generic " + r.name() + " --from-literal=KEY=value"
                : "kubectl -n " + namespace + " create configmap " + r.name() + " --from-file=<file>";
        String copy = "kubectl -n " + sourceNamespace + " get " + type + " " + r.name() + " -o json"
                + " | jq 'del(.metadata.namespace,.metadata.uid,.metadata.resourceVersion,.metadata.creationTimestamp,.metadata.managedFields,.metadata.ownerReferences)'"
                + " | kubectl -n " + namespace + " apply -f -";
        return List.of(create, copy);
    }

    private static final Pattern ENV_WORD = Pattern.compile("(?i)(^|[^a-z0-9])(dev|develop|development|staging|stage|prod|production)([^a-z0-9]|$)");
    private static final Pattern URL_OR_HOST = Pattern.compile("(?i)(://|(^|[^a-z0-9.-])[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(:\\d+)?(/|$|[^a-z0-9.-]))");

    /** A value that probably differs per env: names the source env, an env word, or contains a URL / host name. */
    static boolean envSpecific(String value, String sourceEnv) {
        if (value == null || value.isBlank()) return false;
        if (sourceEnv != null && Pattern.compile("(?i)(^|[^a-z0-9])" + Pattern.quote(sourceEnv) + "([^a-z0-9]|$)").matcher(value).find()) return true;
        return ENV_WORD.matcher(value).find() || URL_OR_HOST.matcher(value).find();
    }

    /** "secretKeyRef db-creds/url" - names only, never values. */
    static String describeValueFrom(Object valueFrom) {
        if (J.get(valueFrom, "secretKeyRef") instanceof Map<?, ?> r) return "secretKeyRef " + r.get("name") + "/" + r.get("key");
        if (J.get(valueFrom, "configMapKeyRef") instanceof Map<?, ?> r) return "configMapKeyRef " + r.get("name") + "/" + r.get("key");
        if (J.get(valueFrom, "fieldRef") instanceof Map<?, ?> r) return "fieldRef " + r.get("fieldPath");
        if (J.get(valueFrom, "resourceFieldRef") instanceof Map<?, ?> r) return "resourceFieldRef " + r.get("resource");
        return "valueFrom";
    }

    // ------------------------------------------------------------------ copy settings

    /**
     * The target values file with the source's runtime settings copied in: {@link #REPLACED} keys take the source's
     * block; {@link #MERGED} lists keep every target entry (an env var the target already sets keeps its value) and
     * gain the source entries they lack. Nothing in {@link #NEVER_COPIED} is touched.
     */
    public static String merge(String sourceYaml, String targetYaml) {
        return merge(sourceYaml, targetYaml, Map.of());
    }

    /**
     * As {@link #merge(String, String)}, with {@code envOverrides} replacing the source value of env vars being added
     * (edited in the promote dialog). Only names that are added with a plain value may be overridden.
     */
    public static String merge(String sourceYaml, String targetYaml, Map<String, String> envOverrides) {
        // values files edited on Windows can come back from git with CRLF; the block edits below work on LF lines
        sourceYaml = sourceYaml == null ? "" : sourceYaml.replace("\r\n", "\n");
        targetYaml = targetYaml == null ? "" : targetYaml.replace("\r\n", "\n");
        Map<String, String> overrides = envOverrides == null ? Map.of() : envOverrides;
        Map<String, Object> src = load(sourceYaml);
        Map<String, Object> dst = load(targetYaml);
        String out = targetYaml;
        for (String key : REPLACED) {
            if (!src.containsKey(key)) continue;
            String block = rawBlock(sourceYaml, key);
            if (block != null && !Objects.equals(src.get(key), dst.get(key))) out = RuntimeConfig.setBlock(out, key, block);
        }
        out = mergeList(out, overrides.isEmpty() ? sourceYaml : null, "env", withOverrides(maps(src.get("env")), maps(dst.get("env")), overrides),
                maps(dst.get("env")), e -> String.valueOf(e.get("name")), true);
        out = mergeList(out, sourceYaml, "envFrom", maps(src.get("envFrom")), maps(dst.get("envFrom")), PromotionCheck::envFromLabel, false);
        out = mergeList(out, sourceYaml, "extraVolumes", maps(src.get("extraVolumes")), maps(dst.get("extraVolumes")), v -> String.valueOf(v.get("name")), false);
        out = mergeList(out, sourceYaml, "extraVolumeMounts", maps(src.get("extraVolumeMounts")), maps(dst.get("extraVolumeMounts")), m -> String.valueOf(m.get("mountPath")), false);
        load(out); // must still parse
        return out;
    }

    /** Source env entries with the overridden values; refuses names that aren't added as plain values. */
    static List<Map<String, Object>> withOverrides(List<Map<String, Object>> src, List<Map<String, Object>> dst,
                                                   Map<String, String> overrides) {
        if (overrides.isEmpty()) return src;
        Set<String> inTarget = new LinkedHashSet<>();
        dst.forEach(d -> inTarget.add(String.valueOf(d.get("name"))));
        Set<String> plainAdded = new LinkedHashSet<>();
        for (Map<String, Object> e : src) {
            String name = String.valueOf(e.get("name"));
            if (!inTarget.contains(name) && !e.containsKey("valueFrom")) plainAdded.add(name);
        }
        for (var o : overrides.entrySet()) {
            if (!plainAdded.contains(o.getKey()))
                throw new IllegalArgumentException("env var " + o.getKey() + " is not added by Copy settings with a plain value, so its value can't be set here");
            if (o.getValue() == null || o.getValue().length() > RuntimeConfig.MAX_VALUE)
                throw new IllegalArgumentException(o.getKey() + " must be a value of at most " + RuntimeConfig.MAX_VALUE + " characters");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : src) {
            String name = String.valueOf(e.get("name"));
            if (!overrides.containsKey(name)) { out.add(e); continue; }
            Map<String, Object> copy = new LinkedHashMap<>(e);
            copy.put("value", overrides.get(name));
            out.add(copy);
        }
        return out;
    }

    private static String mergeList(String yaml, String sourceYaml, String key, List<Map<String, Object>> src,
                                    List<Map<String, Object>> dst, Function<Map<String, Object>, String> id, boolean envStyle) {
        Set<String> have = new LinkedHashSet<>();
        for (Map<String, Object> d : dst) have.add(id.apply(d));
        List<Map<String, Object>> add = src.stream().filter(s -> !have.contains(id.apply(s))).toList();
        if (add.isEmpty()) return yaml;
        if (sourceYaml != null && !hasKey(yaml, key)) {
            // target has no such block: take the source's as written (keeps its comments and layout)
            String block = rawBlock(sourceYaml, key);
            if (block != null) return RuntimeConfig.setBlock(yaml, key, block);
        }
        String block = rawBlock(yaml, key);
        if (block == null || dst.isEmpty() || flowStyle(block, key)) {
            StringBuilder b = new StringBuilder(key).append(":\n");
            for (Map<String, Object> d : dst) b.append(item(d, "  ", envStyle));
            for (Map<String, Object> s : add) b.append(item(s, "  ", envStyle));
            return RuntimeConfig.setBlock(yaml, key, b.toString());
        }
        Matcher dash = Pattern.compile("(?m)^([ \\t]*)- ").matcher(block);
        String indent = dash.find() ? dash.group(1) : "  ";
        StringBuilder b = new StringBuilder(block.endsWith("\n") ? block : block + "\n");
        for (Map<String, Object> s : add) b.append(item(s, indent, envStyle));
        return RuntimeConfig.setBlock(yaml, key, b.toString());
    }

    /** One list entry: env vars as "- name: X / value: "..."", anything else as a flow mapping. */
    static String item(Map<String, Object> m, String indent, boolean envStyle) {
        if (!envStyle) return indent + "- " + flow(m) + "\n";
        StringBuilder b = new StringBuilder(indent).append("- name: ").append(m.get("name")).append('\n');
        for (var e : m.entrySet()) {
            if ("name".equals(e.getKey())) continue;
            Object v = e.getValue();
            b.append(indent).append("  ").append(e.getKey()).append(": ")
                    .append(v instanceof String s ? RuntimeConfigQuote.quote(s) : flow(v)).append('\n');
        }
        return b.toString();
    }

    private static final Yaml FLOW;
    static {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        o.setWidth(Integer.MAX_VALUE);
        FLOW = new Yaml(o);
    }

    static String flow(Object v) {
        return FLOW.dump(v).strip();
    }

    // ------------------------------------------------------------------ YAML helpers

    static Map<String, Object> load(String yaml) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml == null ? "" : yaml);
        if (doc == null) return Map.of();
        if (!(doc instanceof Map<?, ?> m)) throw new IllegalArgumentException("values file must be a YAML mapping");
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object list) {
        if (!(list instanceof List<?> l)) return List.of();
        return l.stream().filter(x -> x instanceof Map<?, ?>).map(x -> (Map<String, Object>) x).toList();
    }

    private static boolean hasKey(String yaml, String key) {
        return Pattern.compile("(?m)^" + Pattern.quote(key) + ":").matcher(yaml).find();
    }

    /** The top-level block of {@code key} exactly as written (same extent as {@link RuntimeConfig#setBlock}). */
    static String rawBlock(String yaml, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + ":.*(?:\\R|$)(?:[ \\t]+.*(?:\\R|$)|-.*(?:\\R|$))*").matcher(yaml);
        if (!m.find()) return null;
        String b = m.group();
        return b.endsWith("\n") ? b : b + "\n";
    }

    /** "env: []" / "envFrom: [{...}]": the value sits on the key line. */
    private static boolean flowStyle(String block, String key) {
        String first = block.lines().findFirst().orElse("");
        String rest = first.substring(Math.min(first.length(), key.length() + 1)).trim();
        return !rest.isEmpty() && !rest.startsWith("#");
    }

    static String envFromLabel(Map<String, Object> e) {
        if (e.get("secretRef") instanceof Map<?, ?> s) return "secret " + s.get("name");
        if (e.get("configMapRef") instanceof Map<?, ?> c) return "configMap " + c.get("name");
        return "entry " + flow(e);
    }

    private static String volumeSource(Map<String, Object> v) {
        if (v.get("configMap") instanceof Map<?, ?> c) return "ConfigMap " + c.get("name");
        if (v.get("secret") instanceof Map<?, ?> s) return "Secret " + s.get("secretName");
        if (v.containsKey("emptyDir")) return "emptyDir";
        if (v.containsKey("persistentVolumeClaim")) return "PersistentVolumeClaim " + J.str(v, "persistentVolumeClaim", "claimName");
        return "volume";
    }

    private static int port(Map<String, Object> doc) {
        return doc.get("containerPort") instanceof Number n ? n.intValue() : DEFAULT_PORT;
    }

    private static String probePath(Map<String, Object> doc, String probe) {
        if ("startup".equals(probe)) {
            Object startup = J.get(doc.get("probes"), "startup");
            return startup == null ? null : String.valueOf(J.get(startup, "path"));
        }
        Object p = J.get(doc.get("probes"), probe, "path");
        return p == null ? DEFAULT_PROBE_PATH : String.valueOf(p);
    }

    private static Map<String, Object> podSecurity(Map<String, Object> doc) {
        Map<String, Object> out = new LinkedHashMap<>(DEFAULT_POD_SECURITY);
        if (doc.get("podSecurityContext") instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return new java.util.TreeMap<>(out);
    }

    /** Readable one-line value; null/empty lists read as "(not set)". */
    private static String show(Object v) {
        if (v == null || (v instanceof List<?> l && l.isEmpty())) return "(not set)";
        return v instanceof String s ? s : flow(v);
    }

    /** JSON string quoting (a valid YAML double-quoted scalar), as RuntimeConfig writes env values. */
    private static final class RuntimeConfigQuote {
        private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

        static String quote(String s) {
            try { return JSON.writeValueAsString(s); }
            catch (tools.jackson.core.JacksonException e) { throw new IllegalStateException(e); }
        }
    }
}
