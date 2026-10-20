package com.aio.platform.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Null-safe navigation over the untyped JSON maps returned by Tekton / Argo CD / GitHub. */
public final class J {
    private J() {}

    public static Object get(Object root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }

    public static String str(Object root, String... path) {
        Object v = get(root, path);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Object root, String... path) {
        Object v = get(root, path);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    public static Instant time(Object root, String... path) {
        String s = str(root, path);
        return s == null || s.isBlank() ? null : Instant.parse(s);
    }
}
