package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Argo CD apps of deleted projects are removed; live projects and doubtful reads are never touched. */
class OrphanAppSweeperTest {

    record H(OrphanAppSweeper sweeper, ArgoCdService argo, ProjectRepo projects, GitHubClient github, AuditService audit) {}

    static Project project(String name) {
        Project p = new Project();
        p.name = name;
        return p;
    }

    static List<ArgoCdService.ProjectApp> apps(String... projects) {
        List<ArgoCdService.ProjectApp> out = new ArrayList<>();
        for (String p : projects) for (String env : List.of("dev", "staging", "prod")) out.add(new ArgoCdService.ProjectApp(p + "-" + env, p, env, "helm/app"));
        return out;
    }

    @Test
    void neverTouchesAHandMadeAppThatSharesTheLabel() {
        // scalesafe-data: labelled like a project, no DB row, no values-<env>.yaml, but deploys apps/scalesafe-data
        // (Postgres + Keycloak). The 2026-11-21 first pass flagged it; only a 415 from Argo CD saved it.
        List<ArgoCdService.ProjectApp> inArgo = new ArrayList<>(apps("shop", "contri"));
        inArgo.add(new ArgoCdService.ProjectApp("scalesafe-data-dev", "scalesafe-data", "dev", "apps/scalesafe-data"));
        H h = harness(List.of(project("shop")), inArgo);
        Map<String, List<String>> removed = h.sweeper().sweep();
        assertEquals(java.util.Set.of("contri"), removed.keySet());
        verify(h.argo(), never()).deleteApp("scalesafe-data-dev");
    }

    @Test
    void anAppWithoutASourcePathIsLeftAlone() {
        List<ArgoCdService.ProjectApp> inArgo = List.of(new ArgoCdService.ProjectApp("odd-dev", "odd", "dev"));
        H h = harness(List.of(project("shop")), inArgo);
        assertTrue(h.sweeper().sweep().isEmpty());
        verify(h.argo(), never()).deleteApp(anyString());
    }

    H harness(List<Project> inDb, List<ArgoCdService.ProjectApp> inArgo) {
        ArgoCdService argo = mock(ArgoCdService.class);
        when(argo.projectApps()).thenReturn(inArgo);
        ProjectRepo projects = mock(ProjectRepo.class);
        when(projects.findAll()).thenReturn(inDb);
        GitHubClient github = mock(GitHubClient.class);
        AuditService audit = mock(AuditService.class);
        var sweeper = new OrphanAppSweeper(argo, projects, github, EnvironmentsTest.liveProps(), EnvironmentsTest.registry(), audit);
        return new H(sweeper, argo, projects, github, audit);
    }

    @Test
    void removesTheAppsOfADeletedProjectOnly() {
        H h = harness(List.of(project("shop"), project("blog")), apps("shop", "blog", "contri"));
        Map<String, List<String>> removed = h.sweeper().sweep();
        assertEquals(Map.of("contri", List.of("contri-dev", "contri-staging", "contri-prod")), removed);
        verify(h.argo()).deleteApp("contri-dev");
        verify(h.argo()).deleteApp("contri-staging");
        verify(h.argo()).deleteApp("contri-prod");
        verify(h.argo(), never()).deleteApp(startsWith("shop"));
        verify(h.argo(), never()).deleteApp(startsWith("blog"));
        verify(h.audit()).record(eq("aio-platform"), eq("project.orphan-apps-removed"), eq("contri"), anyString());
    }

    @Test
    void keepsAppsWhoseValuesAreStillInGit() {
        H h = harness(List.of(project("shop")), apps("shop", "half-deleted"));
        when(h.github().getFile(eq("o"), eq("aio-gitops"), eq("apps/half-deleted/values-staging.yaml"), eq("main")))
                .thenReturn(new GitHubClient.FileContent("image:\n  tag: \"1\"\n", "sha"));
        assertTrue(h.sweeper().sweep().isEmpty());
        verify(h.argo(), never()).deleteApp(anyString());
    }

    @Test
    void removesNothingWhenTheProjectTableReadsEmpty() {
        H h = harness(List.of(), apps("shop", "blog"));
        assertTrue(h.sweeper().sweep().isEmpty());
        verify(h.argo(), never()).deleteApp(anyString());
        verify(h.argo(), never()).projectApps();
    }

    @Test
    void holdsWhenTooManyProjectsLookOrphanedAtOnce() {
        H h = harness(List.of(project("shop")), apps("shop", "a", "b", "c", "d", "e", "f"));
        assertTrue(h.sweeper().sweep().isEmpty());
        verify(h.argo(), never()).deleteApp(anyString());
        verify(h.audit()).record(eq("aio-platform"), eq("project.orphan-apps-held"), anyString(), anyString());
    }

    @Test
    void anUnreachableArgoCdOnlySkipsThePass() {
        H h = harness(List.of(project("shop")), List.of());
        when(h.argo().projectApps()).thenThrow(new RuntimeException("connection refused"));
        assertDoesNotThrow(() -> h.sweeper().scheduled());
        verify(h.argo(), never()).deleteApp(anyString());
    }
}
