package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Removes Argo CD apps left behind by a deleted project. Deleting a project removes its apps best-effort; when Argo CD
 * is unreachable at that moment (e.g. scaled to zero by Sablier) the project, its git files and its row are gone but
 * the apps stay, showing "Unknown" forever (contri, my-spring-boot and scalesafe-investors on 2026-11-19).
 *
 * <p>An app is removed only when all of these hold: it carries the platform's {@code aio.platform/project} label in
 * the apps AppProject; no project of that name exists in the database; and none of that project's values files exist
 * in git. Safety stops: nothing is removed when the project table reads empty, and when more than
 * {@link #MAX_PROJECTS_PER_PASS} projects look orphaned at once (that smells like a bad read, not cleanup); then it
 * only logs and audits so an admin can look.
 */
@Service
public class OrphanAppSweeper {
    private static final Logger log = LoggerFactory.getLogger(OrphanAppSweeper.class);
    static final int MAX_PROJECTS_PER_PASS = 5;
    static final String ACTOR = "aio-platform";

    private final ArgoCdService argo;
    private final ProjectRepo projects;
    private final GitHubClient github;
    private final AppProperties props;
    private final EnvironmentRegistry environments;
    private final AuditService audit;

    @Value("${app.projects.orphan-sweep:true}")
    boolean enabled = true;

    public OrphanAppSweeper(ArgoCdService argo, ProjectRepo projects, GitHubClient github, AppProperties props,
                            EnvironmentRegistry environments, AuditService audit) {
        this.argo = argo;
        this.projects = projects;
        this.github = github;
        this.props = props;
        this.environments = environments;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.projects.orphan-sweep-ms:3600000}",
               initialDelayString = "${app.projects.orphan-sweep-initial-ms:300000}")
    public void scheduled() {
        if (!enabled) return;
        try {
            Map<String, List<String>> removed = sweep();
            if (!removed.isEmpty()) log.info("removed Argo CD apps of deleted projects: {}", removed);
        } catch (RuntimeException e) {
            log.warn("orphan app sweep skipped: {}", e.getMessage());   // e.g. Argo CD asleep; next pass retries
        }
    }

    /** One pass; returns project -> removed app names. */
    public Map<String, List<String>> sweep() {
        List<Project> all = projects.findAll();
        if (all.isEmpty()) {
            log.warn("orphan app sweep: the project table reads empty, nothing removed");
            return Map.of();
        }
        Set<String> known = all.stream().map(p -> p.name).collect(Collectors.toSet());
        Map<String, List<ArgoCdService.ProjectApp>> byProject = argo.projectApps().stream()
                .filter(a -> !known.contains(a.project()))
                .collect(Collectors.groupingBy(ArgoCdService.ProjectApp::project, LinkedHashMap::new, Collectors.toList()));
        // only projects with no values file left in git
        byProject.keySet().removeIf(this::hasValuesInGit);
        if (byProject.isEmpty()) return Map.of();
        if (byProject.size() > MAX_PROJECTS_PER_PASS) {
            log.warn("orphan app sweep: {} projects look orphaned, more than {}; nothing removed: {}",
                    byProject.size(), MAX_PROJECTS_PER_PASS, byProject.keySet());
            audit.record(ACTOR, "project.orphan-apps-held", String.join(",", byProject.keySet()),
                    "more than " + MAX_PROJECTS_PER_PASS + " projects at once; check before removing");
            return Map.of();
        }
        Map<String, List<String>> removed = new LinkedHashMap<>();
        byProject.forEach((project, apps) -> {
            List<String> names = new ArrayList<>();
            for (ArgoCdService.ProjectApp a : apps) {
                try {
                    argo.deleteApp(a.name());
                    names.add(a.name());
                } catch (RuntimeException e) {
                    log.warn("could not remove orphan app {}: {}", a.name(), e.getMessage());
                }
            }
            if (!names.isEmpty()) {
                removed.put(project, names);
                audit.record(ACTOR, "project.orphan-apps-removed", project, String.join(", ", names));
            }
        });
        return removed;
    }

    private boolean hasValuesInGit(String project) {
        AppProperties.GitOps g = props.gitops();
        for (String env : environments.names()) {
            if (github.getFile(g.owner(), g.repo(), GitOpsService.valuesPath(g, project, env), g.branch()) != null) return true;
        }
        return false;
    }
}
