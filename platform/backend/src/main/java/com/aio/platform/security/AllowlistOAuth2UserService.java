package com.aio.platform.security;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.AppUser;
import com.aio.platform.repo.AppUserRepo;
import com.aio.platform.service.AuditService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** GitHub login restricted to an allowlist of users and/or org members; records users in the DB. */
@Service
public class AllowlistOAuth2UserService extends DefaultOAuth2UserService {
    private static final Logger log = LoggerFactory.getLogger(AllowlistOAuth2UserService.class);

    private final AppProperties props;
    private final AppUserRepo users;
    private final AuditService audit;

    public AllowlistOAuth2UserService(AppProperties props, AppUserRepo users, AuditService audit) {
        this.props = props;
        this.users = users;
        this.audit = audit;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(request);
        String login = String.valueOf(user.getAttributes().get("login"));

        if (!isAllowed(login, request.getAccessToken().getTokenValue())) {
            audit.record(login, "login.denied", null, null);
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "User not allowed: " + login);
        }

        AppUser u = users.findByGithubLogin(login).orElseGet(AppUser::new);
        u.githubLogin = login;
        u.name = (String) user.getAttributes().get("name");
        u.avatarUrl = (String) user.getAttributes().get("avatar_url");
        u.lastLoginAt = Instant.now();
        users.save(u);
        audit.record(login, "login", null, null);
        return user;
    }

    private boolean isAllowed(String login, String token) {
        var sec = props.security();
        if (sec.allowedUsers().isEmpty() && sec.allowedOrgs().isEmpty()) {
            log.warn("No allowed-users/allowed-orgs configured; allowing GitHub user {}", login);
            return true;
        }
        if (sec.allowedUsers().contains(login.toLowerCase())) return true;
        if (sec.allowedOrgs().isEmpty()) return false;

        List<Map<String, Object>> orgs = RestClient.create(props.github().apiUrl()).get()
                .uri("/user/orgs?per_page=100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return orgs != null && orgs.stream()
                .map(o -> String.valueOf(o.get("login")).toLowerCase())
                .anyMatch(sec.allowedOrgs()::contains);
    }
}
