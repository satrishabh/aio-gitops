package com.aio.platform.security;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.AppUser;
import com.aio.platform.repo.AppUserRepo;
import com.aio.platform.service.AuditService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/** Loads a User Profile Manager identity, enforces AIO access policy, and records the user. */
@Service
public class ProfileOidcUserService extends OidcUserService {
    private static final Logger log = LoggerFactory.getLogger(ProfileOidcUserService.class);

    private final AppProperties props;
    private final AppUserRepo users;
    private final AuditService audit;

    public ProfileOidcUserService(AppProperties props, AppUserRepo users, AuditService audit) {
        this.props = props;
        this.users = users;
        this.audit = audit;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(request);
        String login = ProfileIdentity.login(user);

        boolean allowlistedUser = isAllowlistedUser(user);
        Set<String> identityRoles = new LinkedHashSet<>(ProfileIdentity.realmRoles(user));
        Set<String> clientRoles = ProfileIdentity.clientRoles(user, "aio-platform");
        identityRoles.addAll(clientRoles);
        if (!isAllowed(user, identityRoles)) {
            audit.record(login, "login.denied", null, "issuer=user-profile-manager");
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"),
                    "User Profile Manager account is not allowed to access AIO");
        }

        String subject = user.getIssuer() + "|" + user.getSubject();
        AppUser appUser = users.findBySubject(subject)
                .or(() -> users.findByLogin(login).filter(existing -> existing.subject == null))
                .orElseGet(AppUser::new);
        if (appUser.id == null && users.findByLogin(login).filter(existing -> existing.subject != null
                && !subject.equals(existing.subject)).isPresent()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "Account identity conflict; contact an administrator");
        }
        appUser.subject = subject;
        appUser.login = login;
        appUser.name = ProfileIdentity.name(user);
        appUser.avatarUrl = ProfileIdentity.avatarUrl(user);
        appUser.lastLoginAt = Instant.now();
        users.save(appUser);
        audit.record(login, "login", null, "issuer=user-profile-manager", subject);
        return withAioAuthorities(user, allowlistedUser, clientRoles);
    }

    private boolean isAllowed(OidcUser user, Set<String> identityRoles) {
        var security = props.security();
        if (!security.allowedOrgs().isEmpty()) {
            log.warn("ALLOWED_ORGS is ignored for OIDC login; use ALLOWED_USERS or ALLOWED_ROLES");
        }
        boolean noPolicy = security.allowedUsers().isEmpty() && security.allowedRoles().isEmpty();
        if (noPolicy) {
            if (!security.allowedOrgs().isEmpty()) return false;
            log.warn("No allowed-users/allowed-roles configured; allowing OIDC user {}",
                    ProfileIdentity.login(user));
            return true;
        }
        boolean userAllowed = ProfileIdentity.allowlistCandidates(user).stream()
                .anyMatch(security.allowedUsers()::contains);
        boolean roleAllowed = identityRoles.stream().anyMatch(security.allowedRoles()::contains);
        return userAllowed || roleAllowed;
    }

    private boolean isAllowlistedUser(OidcUser user) {
        return ProfileIdentity.allowlistCandidates(user).stream()
                .anyMatch(props.security().allowedUsers()::contains);
    }

    private OidcUser withAioAuthorities(OidcUser user, boolean allowlistedUser, Set<String> clientRoles) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
        if (allowlistedUser || clientRoles.contains("aio-admin")) {
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.ADMIN));
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.BUSINESS));
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.COMMUNITY));
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.APPROVER));
        } else if (clientRoles.contains("aio-business")) {
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.BUSINESS));
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.COMMUNITY));
        } else {
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.COMMUNITY));
        }
        if (clientRoles.contains("aio-approver")) {
            authorities.add(new SimpleGrantedAuthority(AioAuthorization.APPROVER));
        }
        if (user.getUserInfo() == null) {
            return new DefaultOidcUser(authorities, user.getIdToken(), "preferred_username");
        }
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "preferred_username");
    }
}
