package com.aio.platform.web;

import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "not found");
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<Map<String, String>> upstream(RestClientResponseException e) {
        log.warn("upstream error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
        return body(HttpStatus.BAD_GATEWAY, "upstream call failed: HTTP " + e.getStatusCode().value());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(io.fabric8.kubernetes.client.KubernetesClientException.class)
    ResponseEntity<Map<String, String>> kubernetes(io.fabric8.kubernetes.client.KubernetesClientException e) {
        // API errors can include request objects. Never log or return a Secret request body.
        log.warn("Kubernetes operation failed with HTTP {}", e.getCode());
        return body(HttpStatus.BAD_GATEWAY, "Kubernetes operation failed; check access and cluster availability");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus s, String msg) {
        return ResponseEntity.status(s).body(Map.of("message", msg == null ? s.getReasonPhrase() : msg));
    }
}
