package com.aio.platform.web;

import com.aio.platform.service.MetricsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {
    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics(@RequestParam(defaultValue = "30") int days) {
        return metrics.overview(days);
    }
}
