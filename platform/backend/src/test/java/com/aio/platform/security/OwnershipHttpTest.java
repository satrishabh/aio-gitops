package com.aio.platform.security;

import com.aio.platform.config.OwnershipConfig;
import com.aio.platform.domain.*;
import com.aio.platform.repo.*;
import com.aio.platform.service.*;
import com.aio.platform.web.ApiErrors;
import com.aio.platform.web.RunController;
import jakarta.servlet.http.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OwnershipHttpTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void guessedRunLogsStreamsAndActionsAreDeniedBeforeServiceAccess() throws Exception {
        var projects = mock(ProjectRepo.class); var runs = mock(CiRunRepo.class); var steps = mock(RunStepRepo.class);
        var tekton = mock(TektonService.class); var service = mock(RunService.class);
        var ownership = new OwnershipConfig(projects, runs, mock(ScanRunRepo.class), mock(PromotionRepo.class));
        var mvc = MockMvcBuilders.standaloneSetup(new RunController(runs, steps, projects, tekton, service))
                .setControllerAdvice(new ApiErrors()).addInterceptors(new HandlerInterceptor() {
                    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        return ownership.authorize(request);
                    }
                }).build();
        Project project = new Project(); project.id = 1L; project.ownerSubject = "https://identity/realms/aio|alice";
        CiRun run = new CiRun(); run.projectId = project.id;
        when(projects.findById(1L)).thenReturn(Optional.of(project)); when(runs.findById(9L)).thenReturn(Optional.of(run));
        OwnershipTest.signIn("bob", AioAuthorization.BUSINESS);
        for (String url : java.util.List.of("/api/runs/9", "/api/runs/9/stream", "/api/runs/9/steps/20/logs"))
            mvc.perform(get(url)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("not found"));
        mvc.perform(post("/api/runs/9/cancel")).andExpect(status().isNotFound());
        mvc.perform(post("/api/runs/9/rerun")).andExpect(status().isNotFound());
        verifyNoInteractions(tekton, service, steps);
    }
}
