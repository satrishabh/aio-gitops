package com.aio.platform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the React app for client-side routes. */
@Controller
public class SpaController {
    @GetMapping({"/", "/login", "/new", "/approvals", "/audit", "/metrics", "/projects", "/projects/{id}", "/runs/{id}"})
    public String index() {
        return "forward:/index.html";
    }
}
