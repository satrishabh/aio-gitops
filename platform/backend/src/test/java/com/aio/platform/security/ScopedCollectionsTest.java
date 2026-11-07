package com.aio.platform.security;

import com.aio.platform.domain.*;
import com.aio.platform.repo.*;
import com.aio.platform.service.*;
import com.aio.platform.web.Me;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScopedCollectionsTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void metricsAggregateOnlyOwnedProjectDataWhileAdminsKeepGlobalView() {
        var projects = mock(ProjectRepo.class); var runs = mock(CiRunRepo.class);
        var deployments = mock(DeploymentRepo.class); var promotions = mock(PromotionRepo.class);
        Project project = new Project(); project.id = 1L; project.name = "mine";
        CiRun own = new CiRun(); own.projectId = 1L; own.status = CiRun.SUCCEEDED;
        when(projects.findByOwnerSubjectOrderByNameAsc(anyString())).thenReturn(List.of(project));
        when(runs.findByProjectIdInAndCreatedAtAfterOrderByIdAsc(eq(List.of(1L)), any())).thenReturn(List.of(own));
        when(deployments.findByProjectIdInAndDeployedAtAfter(eq(List.of(1L)), any())).thenReturn(List.of());
        when(promotions.findVisible(eq(List.of(1L)), isNull(), any())).thenReturn(List.of());
        var metrics = new MetricsService(projects, runs, deployments, promotions);
        OwnershipTest.signIn("alice", AioAuthorization.BUSINESS);
        var result = metrics.overview(14);
        assertThat(((MetricsService.Dora) result.get("overall")).ciRuns()).isEqualTo(1);
        assertThat((Map<?, ?>) result.get("projects")).hasSize(1);
        verify(runs, never()).findByCreatedAtAfterOrderByIdAsc(any());
        verify(deployments, never()).findByDeployedAtAfter(any());
        OwnershipTest.signIn("admin", AioAuthorization.ADMIN);
        when(projects.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(project));
        when(runs.findByCreatedAtAfterOrderByIdAsc(any())).thenReturn(List.of(own, own));
        when(deployments.findByDeployedAtAfter(any())).thenReturn(List.of());
        when(promotions.findAllByOrderByIdDesc(any())).thenReturn(List.of());
        assertThat(((MetricsService.Dora) metrics.overview(14).get("overall")).ciRuns()).isEqualTo(2);
    }
    @Test void auditUsesSubjectForRegularUsersAndGlobalHistoryOnlyForAdmins() {
        var audit = mock(AuditRepo.class);
        var controller = new Me(mock(PromotionService.class), audit, mock(AppUserRepo.class));
        AuditEvent mine = new AuditEvent(); mine.actor = "alice";
        OwnershipTest.signIn("alice", AioAuthorization.BUSINESS);
        when(audit.findTop300ByActorSubjectOrderByIdDesc(UserScope.subject())).thenReturn(List.of(mine));
        assertThat(controller.audit()).isEqualTo(List.of(mine));
        verify(audit, never()).findTop300ByOrderByIdDesc();
        OwnershipTest.signIn("admin", AioAuthorization.ADMIN);
        when(audit.findTop300ByOrderByIdDesc()).thenReturn(List.of(mine));
        assertThat(controller.audit()).isEqualTo(List.of(mine));
        verify(audit).findTop300ByOrderByIdDesc();
    }
}
