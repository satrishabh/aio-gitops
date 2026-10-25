package com.aio.platform.web;

import com.aio.platform.repo.AuditRepo;
import com.aio.platform.repo.AppUserRepo;
import com.aio.platform.service.PromotionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Me {
    private final PromotionService promotions;
    private final AuditRepo audit;
    private final AppUserRepo users;

    public Me(PromotionService promotions, AuditRepo audit, AppUserRepo users) {
        this.promotions = promotions;
        this.audit = audit;
        this.users = users;
    }

    static String login(OAuth2User user) {
        if (user == null) throw new SecurityException("not logged in");
        return String.valueOf(user.getAttributes().get("login"));
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal OAuth2User user) {
        String login = login(user);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("login", login);
        m.put("name", user.getAttributes().get("name"));
        m.put("avatarUrl", user.getAttributes().get("avatar_url"));
        m.put("approver", promotions.isApprover(login));
        m.put("canSelfApprove", promotions.canSelfApprove(login));
        return m;
    }

    @GetMapping("/api/audit")
    public Object audit() {
        return audit.findTop300ByOrderByIdDesc();
    }

    @GetMapping("/api/users")
    public List<?> users() {
        return users.findAll();
    }
}
