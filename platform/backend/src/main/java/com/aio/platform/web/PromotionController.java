package com.aio.platform.web;

import com.aio.platform.domain.Promotion;
import com.aio.platform.repo.ProjectRepo;
import com.aio.platform.repo.PromotionRepo;
import com.aio.platform.service.PromotionService;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/promotions")
public class PromotionController {
    private final PromotionRepo promotions;
    private final ProjectRepo projects;
    private final PromotionService service;

    public PromotionController(PromotionRepo promotions, ProjectRepo projects, PromotionService service) {
        this.promotions = promotions;
        this.projects = projects;
        this.service = service;
    }

    @GetMapping
    public List<Promotion> list(@RequestParam(required = false) String status) {
        return status == null
                ? promotions.findAllByOrderByIdDesc(PageRequest.of(0, 200))
                : promotions.findByStatusOrderByIdDesc(status);
    }

    @PostMapping("/{id}/approve")
    public Promotion approve(@PathVariable Long id, @AuthenticationPrincipal OAuth2User user) {
        Promotion pr = promotions.findById(id).orElseThrow();
        return service.approve(projects.findById(pr.projectId).orElseThrow(), pr, Me.login(user));
    }

    public record RejectRequest(String reason) {}

    @PostMapping("/{id}/reject")
    public Promotion reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest req,
                            @AuthenticationPrincipal OAuth2User user) {
        Promotion pr = promotions.findById(id).orElseThrow();
        return service.reject(pr, Me.login(user), req == null ? null : req.reason());
    }
}
