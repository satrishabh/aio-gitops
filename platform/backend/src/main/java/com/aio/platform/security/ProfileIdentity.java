package com.aio.platform.security;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** Normalizes identity claims shared by Keycloak-backed AIO sessions. */
public final class ProfileIdentity {
    private static final Set<String> BROKER_PREFIXES = Set.of("github", "google");

    private ProfileIdentity() {}

    public static String login(OAuth2User user) {
        if (user == null) throw new SecurityException("not logged in");
        String raw = first(user, "preferred_username", "email", "sub");
        if (raw == null || raw.isBlank()) throw new SecurityException("OIDC identity has no usable username");
        return withoutBrokerPrefix(raw);
    }

    public static String name(OAuth2User user) {
        return first(user, "name", "preferred_username", "email");
    }

    public static String avatarUrl(OAuth2User user) {
        return first(user, "picture", "avatar_url");
    }

    public static Set<String> allowlistCandidates(OAuth2User user) {
        Set<String> values = new LinkedHashSet<>();
        for (String claim : new String[] {"preferred_username", "email", "sub"}) {
            String value = claim(user, claim);
            if (value != null && !value.isBlank()) {
                values.add(value.toLowerCase(Locale.ROOT));
                values.add(withoutBrokerPrefix(value).toLowerCase(Locale.ROOT));
            }
        }
        return values;
    }

    public static Set<String> realmRoles(OAuth2User user) {
        Object access = user.getAttributes().get("realm_access");
        if (!(access instanceof Map<?, ?> map)) return Set.of();
        Object roles = map.get("roles");
        if (!(roles instanceof Iterable<?> iterable)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object role : iterable) {
            if (role != null) result.add(String.valueOf(role).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    public static Set<String> clientRoles(OAuth2User user, String clientId) {
        Object access = user.getAttributes().get("resource_access");
        if (!(access instanceof Map<?, ?> resources)) return Set.of();
        Object client = resources.get(clientId);
        if (!(client instanceof Map<?, ?> clientAccess)) return Set.of();
        Object roles = clientAccess.get("roles");
        if (!(roles instanceof Iterable<?> iterable)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object role : iterable) {
            if (role != null) result.add(String.valueOf(role).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static String first(OAuth2User user, String... claims) {
        for (String claim : claims) {
            String value = claim(user, claim);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String claim(OAuth2User user, String name) {
        Object value = user.getAttributes().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String withoutBrokerPrefix(String value) {
        int separator = value.indexOf('.');
        if (separator <= 0) return value;
        String prefix = value.substring(0, separator).toLowerCase(Locale.ROOT);
        return BROKER_PREFIXES.contains(prefix) ? value.substring(separator + 1) : value;
    }
}
