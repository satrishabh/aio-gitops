package com.aio.platform.security;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** Ownership uses the immutable issuer/subject, never a display name or email. */
public final class UserScope {
    private UserScope() {}
    public static boolean admin() { return AioAuthorization.currentUserHas(AioAuthorization.ADMIN); }
    public static String subject(OAuth2User user) {
        if (user == null) return null;
        Object issuer = user.getAttribute("iss"), sub = user.getAttribute("sub");
        return issuer == null || sub == null ? null : issuer + "|" + sub;
    }
    public static String subject() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof OAuth2User user ? subject(user) : null;
    }
    public static boolean owns(String subject) { return subject != null && subject.equals(subject()); }
    public static Project project(ProjectRepo projects, Long id) {
        Project project = projects.findById(id).orElseThrow();
        if (!admin() && !owns(project.ownerSubject)) throw new NoSuchElementException();
        return project;
    }
    public static List<Project> projects(ProjectRepo projects) {
        if (admin()) return projects.findAll(Sort.by("name"));
        String subject = subject();
        return subject == null ? List.of() : projects.findByOwnerSubjectOrderByNameAsc(subject);
    }
    public static List<Long> projectIds(ProjectRepo projects) { return projects(projects).stream().map(p -> p.id).toList(); }
    public static void repository(ProjectRepo projects, java.util.Map<String, Object> repo) {
        if (repo == null) throw new NoSuchElementException();
        if (admin() || !Boolean.TRUE.equals(repo.get("private"))) return;
        String fullName = String.valueOf(repo.get("full_name"));
        if (projects(projects).stream().noneMatch(p -> fullName.equalsIgnoreCase(p.repoOwner + "/" + p.repoName)))
            throw new SecurityException("Private repository access must be provisioned by an administrator before onboarding");
    }
}
