package com.aio.platform.security;

import com.aio.platform.config.OwnershipConfig;
import com.aio.platform.domain.*;
import com.aio.platform.repo.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.servlet.HandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnershipTest {
    final ProjectRepo projects = mock(ProjectRepo.class);
    final CiRunRepo runs = mock(CiRunRepo.class);
    final ScanRunRepo scans = mock(ScanRunRepo.class);
    final PromotionRepo promotions = mock(PromotionRepo.class);
    final OwnershipConfig guard = new OwnershipConfig(projects, runs, scans, promotions);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    static void signIn(String sub, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        // Intentionally the same display name: ownership must use issuer/subject.
        var user = new DefaultOAuth2User(authorities, Map.of("iss", "https://identity/realms/aio", "sub", sub,
                "preferred_username", "same-name"), "sub");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "", authorities));
    }
    boolean request(String path, long id) {
        var request = new MockHttpServletRequest("GET", path);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", Long.toString(id)));
        return guard.authorize(request);
    }
    @Test void nonAdminCannotReadOrOperateOnAnotherUsersResources() {
        Project p = new Project(); p.id = 42L; p.ownerSubject = "https://identity/realms/aio|alice";
        when(projects.findById(42L)).thenReturn(Optional.of(p));
        CiRun run = new CiRun(); run.projectId = 42L;
        when(runs.findById(3L)).thenReturn(Optional.of(run));
        Promotion promotion = new Promotion(); promotion.projectId = 42L;
        when(promotions.findById(4L)).thenReturn(Optional.of(promotion));
        ScanRun scan = new ScanRun(); scan.projectId = 42L;
        when(scans.findById(5L)).thenReturn(Optional.of(scan));
        signIn("bob", AioAuthorization.BUSINESS);
        for (String path : List.of("/api/projects/42", "/api/projects/42/runs", "/api/projects/42/domains/8",
                "/api/projects/42/environments/prod/cd/pods/pod/logs", "/api/projects/42/environments/prod/rollback"))
            assertThrows(NoSuchElementException.class, () -> request(path, 42), path);
        for (String path : List.of("/api/runs/3", "/api/runs/3/stream", "/api/runs/3/steps/9/logs", "/api/runs/3/cancel"))
            assertThrows(NoSuchElementException.class, () -> request(path, 3), path);
        for (String path : List.of("/api/scans/5/stream", "/api/scans/5/report.json", "/api/scans/5/findings", "/api/scans/5/rerun"))
            assertThrows(NoSuchElementException.class, () -> request(path, 5), path);
        assertThrows(NoSuchElementException.class, () -> request("/api/promotions/4/approve", 4));
        signIn("alice", AioAuthorization.BUSINESS);
        assertTrue(request("/api/projects/42", 42));
        assertTrue(request("/api/runs/3/stream", 3));
        assertTrue(request("/api/scans/5/report.json", 5));
        signIn("admin", AioAuthorization.ADMIN);
        assertTrue(request("/api/projects/42", 42));
        assertTrue(request("/api/runs/3/cancel", 3));
    }
    @Test void standaloneScansAndUnassignedLegacyResourcesFailClosed() {
        ScanRun scan = new ScanRun(); scan.ownerSubject = "https://identity/realms/aio|alice";
        when(scans.findById(1L)).thenReturn(Optional.of(scan));
        Project legacy = new Project();
        when(projects.findById(2L)).thenReturn(Optional.of(legacy));
        signIn("bob", AioAuthorization.APPROVER);
        assertThrows(NoSuchElementException.class, () -> request("/api/scans/1/steps/5/logs", 1));
        assertThrows(NoSuchElementException.class, () -> request("/api/projects/2", 2));
        signIn("alice", AioAuthorization.BUSINESS);
        assertTrue(request("/api/scans/1/report.json", 1));
        SecurityContextHolder.clearContext();
        assertFalse(UserScope.owns(null));
        assertThrows(NoSuchElementException.class, () -> request("/api/projects/2", 2));
    }
    @Test void subjectIncludesIssuerAndCannotBeReplacedByUsername() {
        signIn("alice", AioAuthorization.BUSINESS);
        assertEquals("https://identity/realms/aio|alice", UserScope.subject());
        assertFalse(UserScope.owns("https://identity/realms/other|alice"));
        assertFalse(UserScope.owns("same-name"));
    }
    @Test void platformTokenDoesNotGrantOtherUsersPrivateRepositoryAccess() {
        signIn("alice", AioAuthorization.BUSINESS);
        Project project = new Project(); project.repoOwner = "org"; project.repoName = "mine";
        when(projects.findByOwnerSubjectOrderByNameAsc(UserScope.subject())).thenReturn(List.of(project));
        assertDoesNotThrow(() -> UserScope.repository(projects, Map.of("private", false, "full_name", "org/public")));
        assertDoesNotThrow(() -> UserScope.repository(projects, Map.of("private", true, "full_name", "org/mine")));
        assertThrows(SecurityException.class, () -> UserScope.repository(projects, Map.of("private", true, "full_name", "org/theirs")));
        signIn("admin", AioAuthorization.ADMIN);
        assertDoesNotThrow(() -> UserScope.repository(projects, Map.of("private", true, "full_name", "org/theirs")));
    }
}
