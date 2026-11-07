package com.aio.platform.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** Central AIO access tiers derived from client-scoped User Profile Manager roles. */
public final class AioAuthorization {
    public static final String ADMIN = "ROLE_AIO_ADMIN";
    public static final String BUSINESS = "ROLE_AIO_BUSINESS";
    public static final String COMMUNITY = "ROLE_AIO_COMMUNITY";
    public static final String APPROVER = "ROLE_AIO_APPROVER";

    private AioAuthorization() {}

    public static boolean currentUserHas(String... authorities) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return false;
        Set<String> expected = Set.of(authorities);
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(expected::contains);
    }

    public static String tier(OAuth2User user) {
        if (has(user, ADMIN)) return "admin";
        if (has(user, BUSINESS)) return "business";
        return "community";
    }

    public static List<String> roles(OAuth2User user) {
        Set<String> roles = new LinkedHashSet<>();
        if (has(user, ADMIN)) roles.add("admin");
        if (has(user, BUSINESS)) roles.add("business");
        if (has(user, COMMUNITY)) roles.add("community");
        if (has(user, APPROVER)) roles.add("approver");
        return List.copyOf(roles);
    }

    private static boolean has(OAuth2User user, String authority) {
        return user != null && user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }
}
