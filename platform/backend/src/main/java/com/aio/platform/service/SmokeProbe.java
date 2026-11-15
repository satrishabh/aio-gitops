package com.aio.platform.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * One HTTP sample of a deployed app, used by the promotion saga's smoke-test step (a small version of Argo Rollouts
 * analysis / Harness continuous verification). Redirects are not followed: a 3xx to a login page counts as up.
 */
@Component
public class SmokeProbe {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** HTTP status code of a GET to {@code url}, or -1 when the app could not be reached. */
    public int status(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "aio-smoke-test").GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
