package com.aio.platform.web;

import com.aio.platform.domain.Project;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.security.UserScope;
import com.aio.platform.service.AuditService;
import com.aio.platform.service.ExportBundle;
import com.aio.platform.service.ProjectExportService;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Project → Export: a zip that runs the project on any Kubernetes cluster without AIO
 * (docs/architecture/PROJECT-SERVICES.md, section 5). Same access as viewing or changing the project: the
 * /api/projects/** rule (admin or business) plus the ownership check (OwnershipConfig, repeated here).
 */
@RestController
@RequestMapping("/api/projects/{id}/export")
public class ExportController {
    private final ProjectRepo projects;
    private final ProjectExportService exports;
    private final AuditService audit;

    public ExportController(ProjectRepo projects, ProjectExportService exports, AuditService audit) {
        this.projects = projects;
        this.exports = exports;
        this.audit = audit;
    }

    /** What the download will contain (for the Export dialog). */
    @GetMapping("/preview")
    public Map<String, Object> preview(@PathVariable Long id) {
        return exports.preview(UserScope.project(projects, id));
    }

    @GetMapping(produces = {"application/zip", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<StreamingResponseBody> export(@PathVariable Long id,
                                                        @RequestParam(defaultValue = "false") boolean allConnectors,
                                                        @RequestParam(required = false) String cloud,
                                                        @AuthenticationPrincipal OAuth2User user) {
        Project p = UserScope.project(projects, id);
        // read everything first: a GitHub failure becomes an API error, not a truncated zip
        ExportBundle.Inputs inputs = exports.prepare(p, allConnectors, cloud);
        audit.record(Me.login(user), "project.export", p.name,
                "allConnectors=" + allConnectors + " cloud=" + inputs.cloud() + " connectors="
                        + inputs.connectors().stream().map(c -> c.connector().id()).toList());
        String name = ExportBundle.fileName(p, inputs.at());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(out -> ExportBundle.write(inputs, out));
    }
}
