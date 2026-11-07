package com.aio.platform.config;

import com.aio.platform.repo.*;
import com.aio.platform.security.UserScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Guards every operation, log, download and SSE subscription below an owned resource. */
@Configuration
public class OwnershipConfig implements WebMvcConfigurer {
    private final ProjectRepo projects;
    private final CiRunRepo runs;
    private final ScanRunRepo scans;
    private final PromotionRepo promotions;
    public OwnershipConfig(ProjectRepo projects, CiRunRepo runs, ScanRunRepo scans, PromotionRepo promotions) {
        this.projects = projects; this.runs = runs; this.scans = scans; this.promotions = promotions;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                return authorize(request);
            }
        }).addPathPatterns("/api/projects/**", "/api/runs/**", "/api/scans/**", "/api/promotions/**");
    }
    public boolean authorize(HttpServletRequest request) {
        if (UserScope.admin()) return true;
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map) || map.get("id") == null) return true;
        Long id = Long.valueOf(map.get("id").toString());
        String path = request.getRequestURI();
        if (path.startsWith("/api/projects/")) UserScope.project(projects, id);
        else if (path.startsWith("/api/runs/")) UserScope.project(projects, runs.findById(id).orElseThrow().projectId);
        else if (path.startsWith("/api/promotions/")) UserScope.project(projects, promotions.findById(id).orElseThrow().projectId);
        else if (path.startsWith("/api/scans/")) {
            var scan = scans.findById(id).orElseThrow();
            if (scan.projectId != null) UserScope.project(projects, scan.projectId);
            else if (!UserScope.owns(scan.ownerSubject)) throw new NoSuchElementException();
        }
        return true;
    }
}
