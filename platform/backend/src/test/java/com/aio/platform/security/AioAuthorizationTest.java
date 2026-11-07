package com.aio.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class AioAuthorizationTest {
    @Test
    void reportsHighestAccessTierAndExplicitRoles() {
        var business = user(AioAuthorization.COMMUNITY, AioAuthorization.BUSINESS, AioAuthorization.APPROVER);
        var admin = user(AioAuthorization.COMMUNITY, AioAuthorization.BUSINESS, AioAuthorization.ADMIN);

        assertThat(AioAuthorization.tier(business)).isEqualTo("business");
        assertThat(AioAuthorization.roles(business)).containsExactly("business", "community", "approver");
        assertThat(AioAuthorization.tier(admin)).isEqualTo("admin");
    }

    private static DefaultOAuth2User user(String... roles) {
        return new DefaultOAuth2User(
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList(),
                Map.of("sub", "user-1", "preferred_username", "user"), "sub");
    }
}
