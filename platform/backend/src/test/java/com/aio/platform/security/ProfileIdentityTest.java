package com.aio.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class ProfileIdentityTest {
    @Test
    void normalizesGithubBrokerUsernameAndReadsProfileClaims() {
        var user = user(Map.of(
                "sub", "7f1a",
                "preferred_username", "github.satrishabh",
                "email", "person@example.com",
                "name", "Profile User",
                "picture", "https://images.example/avatar.png"));

        assertThat(ProfileIdentity.login(user)).isEqualTo("satrishabh");
        assertThat(ProfileIdentity.name(user)).isEqualTo("Profile User");
        assertThat(ProfileIdentity.avatarUrl(user)).isEqualTo("https://images.example/avatar.png");
        assertThat(ProfileIdentity.allowlistCandidates(user))
                .contains("github.satrishabh", "satrishabh", "person@example.com", "7f1a");
    }

    @Test
    void extractsLowercaseRealmRoles() {
        var user = user(Map.of(
                "sub", "7f1a",
                "preferred_username", "operator",
                "realm_access", Map.of("roles", List.of("AIO-User", "profile-admin"))));

        assertThat(ProfileIdentity.realmRoles(user)).containsExactly("aio-user", "profile-admin");
    }

    @Test
    void extractsOnlyRolesForTheAioClient() {
        var user = user(Map.of(
                "sub", "7f1a",
                "preferred_username", "operator",
                "resource_access", Map.of(
                        "aio-platform", Map.of("roles", List.of("AIO-Business", "aio-approver")),
                        "another-app", Map.of("roles", List.of("admin")))));

        assertThat(ProfileIdentity.clientRoles(user, "aio-platform"))
                .containsExactly("aio-business", "aio-approver");
    }

    private static DefaultOAuth2User user(Map<String, Object> attributes) {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "sub");
    }
}
