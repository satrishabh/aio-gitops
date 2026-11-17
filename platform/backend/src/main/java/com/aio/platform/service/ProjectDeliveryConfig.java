package com.aio.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Strict, non-secret repository contract. A requested trust tier is never evidence of that tier. */
public record ProjectDeliveryConfig(String mode, String host, String repository, String pushConnection,
                                    String pullConnection, String profile, String requestedTier, String hash) {
    public static ProjectDeliveryConfig parse(String yaml) {
        if (yaml == null || yaml.isBlank() || yaml.getBytes(StandardCharsets.UTF_8).length > 16_384)
            throw new IllegalArgumentException("Project configuration must contain 1 to 16384 bytes");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(12);
        options.setCodePointLimit(16_384);
        Object document;
        try { document = new Yaml(new SafeConstructor(options)).load(yaml); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid project configuration YAML"); }
        Map<?, ?> root = mapping(document, Set.of("apiVersion", "kind", "spec"));
        require("aio.platform/v1alpha1".equals(root.get("apiVersion")), "Unsupported apiVersion");
        require("ProjectDelivery".equals(root.get("kind")), "Expected kind ProjectDelivery");
        Map<?, ?> spec = mapping(root.get("spec"), Set.of("registry", "build", "artifacts"));
        Map<?, ?> registry = mapping(spec.get("registry"), Set.of("mode", "host", "repository", "credentials"));
        String mode = text(registry, "mode");
        require(Set.of("managed", "external").contains(mode), "Registry mode must be managed or external");
        String host = null, repository = null, push = null, pull = null;
        if ("external".equals(mode)) {
            host = text(registry, "host"); repository = text(registry, "repository");
            require(host.matches("(?=.{1,253}$)[a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z]{2,}"), "Registry host must be a DNS hostname without a scheme or port");
            require(repository.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*")
                    && repository.length() <= 200, "Invalid registry repository");
            Map<?, ?> credentials = mapping(registry.get("credentials"), Set.of("pushConnection", "pullConnection"));
            push = text(credentials, "pushConnection"); pull = text(credentials, "pullConnection");
            require(push.matches("[a-z][a-z0-9-]{0,31}") && pull.matches("[a-z][a-z0-9-]{0,31}"), "Invalid project connection name (maximum 32 characters)");
            require(!push.equals(pull), "Push and pull connections must be separate");
        } else {
            require(registry.size() == 1, "Managed registry cannot specify host, repository, or credentials");
        }
        String profile = "standard-v1", tier = "normal";
        if (spec.containsKey("build")) profile = text(mapping(spec.get("build"), Set.of("profile")), "profile");
        if (spec.containsKey("artifacts")) tier = text(mapping(spec.get("artifacts"), Set.of("requestedTier")), "requestedTier");
        require("standard-v1".equals(profile), "Unapproved build profile");
        require(Set.of("normal", "verified", "golden").contains(tier), "Unknown artifact tier");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml.getBytes(StandardCharsets.UTF_8)));
            return new ProjectDeliveryConfig(mode, host, repository, push, pull, profile, tier, hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static Map<?, ?> mapping(Object value, Set<String> allowed) {
        require(value instanceof Map<?, ?>, "Expected YAML mapping");
        Map<?, ?> map = (Map<?, ?>) value;
        require(map.keySet().stream().allMatch(allowed::contains), "Unknown configuration field; credentials must be stored separately");
        return map;
    }
    private static String text(Map<?, ?> map, String key) {
        require(map.get(key) instanceof String, "Missing or invalid " + key);
        return (String) map.get(key);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
