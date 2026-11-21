package com.aio.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code services:} block of a project's values file (app chart, Project -> Services). Like the other platform
 * edits ({@link RuntimeConfig#setBlock}) only that top-level block is rewritten: every other line of the file, its
 * comments included, stays byte for byte. The block itself is owned by the platform and re-emitted whole (comments
 * inside it are not kept), preceded by {@link #MARKER}. Every write is checked by parsing the result again.
 */
final class ServicesValues {
    static final String KEY = "services";
    static final String MARKER = "# Managed by AIO (Project → Services). Edit there, not by hand.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern PLAIN_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]*$");
    private static final Set<String> YAML_WORDS = Set.of("true", "false", "yes", "no", "on", "off", "null", "y", "n", "~");

    private ServicesValues() {}

    private static Yaml yaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /** connector id -> block of the services: key (empty when the file has none). */
    static Map<String, Object> read(String yaml) {
        Object doc = yaml().load(yaml);
        Map<String, Object> out = new LinkedHashMap<>();
        if (doc instanceof Map<?, ?> m && m.get(KEY) instanceof Map<?, ?> services)
            services.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * Returns the file with its services: block replaced by {@code services} (appended when missing, removed with its
     * marker when {@code services} is empty). Fails when the result would not parse back to exactly that.
     */
    static String write(String yaml, Map<String, Object> services) {
        String block = services.isEmpty() ? "" : MARKER + "\n" + block(services);
        List<String> lines = new ArrayList<>(List.of(yaml.split("(?<=\n)", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        int start = -1;
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).matches("^services:(\\s.*)?\\R?$") || lines.get(i).matches("^services:\\R?$")) {
                start = i;
                break;
            }
        String out;
        if (start < 0) {
            if (block.isEmpty()) return yaml;
            out = yaml.isEmpty() || yaml.endsWith("\n") ? yaml + block : yaml + "\n" + block;
        } else {
            int end = start + 1, lastContent = start;
            while (end < lines.size()) {
                String l = lines.get(end);
                if (l.isBlank()) {
                    end++;
                    continue;
                }
                if (l.startsWith(" ") || l.startsWith("\t") || l.startsWith("-")) {
                    lastContent = end;
                    end++;
                    continue;
                }
                break;
            }
            int from = start;
            if (from > 0 && lines.get(from - 1).strip().equals(MARKER)) from--;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < from; i++) sb.append(lines.get(i));
            if (!block.isEmpty() && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append(block);
            for (int i = lastContent + 1; i < lines.size(); i++) {
                if (i == lastContent + 1 && !block.isEmpty() && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append(lines.get(i));
            }
            out = sb.toString();
        }
        verify(yaml, out, services);
        return out;
    }

    /** The result must parse, carry exactly {@code services}, and leave every other top-level key as it was. */
    private static void verify(String before, String after, Map<String, Object> services) {
        Object a, b;
        try {
            a = yaml().load(after);
            b = yaml().load(before);
        } catch (RuntimeException e) {
            throw new IllegalStateException("services: edit produced invalid YAML: " + firstLine(e.getMessage()));
        }
        Map<Object, Object> am = a instanceof Map<?, ?> m ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
        Map<Object, Object> bm = b instanceof Map<?, ?> m ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
        Object written = am.remove(KEY);
        bm.remove(KEY);
        boolean ok = services.isEmpty() ? written == null : Objects.equals(written, services);
        if (!ok || !am.equals(bm)) throw new IllegalStateException("services: edit did not round-trip; values file left unchanged");
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    /** {@code services:} with one entry per connector (block style; multi-line strings as literal blocks). */
    static String block(Map<String, Object> services) {
        StringBuilder sb = new StringBuilder(KEY).append(":\n");
        emitMap(sb, services, 2);
        return sb.toString();
    }

    private static void emitMap(StringBuilder sb, Map<?, ?> m, int indent) {
        for (var e : m.entrySet()) {
            sb.append(" ".repeat(indent)).append(key(String.valueOf(e.getKey()))).append(':');
            emitValue(sb, e.getValue(), indent);
        }
    }

    /** The value after "key:" or "-" (the caller wrote that); nested content at {@code indent + 2}. */
    private static void emitValue(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) sb.append(" {}\n");
            else {
                sb.append('\n');
                emitMap(sb, m, indent + 2);
            }
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) sb.append(" []\n");
            else {
                sb.append('\n');
                emitList(sb, l, indent + 2);
            }
        } else if (v instanceof String s && s.contains("\n") && literalSafe(s)) {
            literal(sb, s, indent + 2);
        } else {
            sb.append(' ').append(scalar(v)).append('\n');
        }
    }

    private static void emitList(StringBuilder sb, List<?> l, int indent) {
        String pad = " ".repeat(indent);
        for (Object item : l) {
            if (item instanceof Map<?, ?> m && !m.isEmpty()) {
                StringBuilder inner = new StringBuilder();
                emitMap(inner, m, indent + 2);
                sb.append(pad).append("- ").append(inner.substring(indent + 2));
            } else {
                sb.append(pad).append('-');
                emitValue(sb, item, indent);
            }
        }
    }

    /** Literal blocks need the first line not to start with a space (no indentation indicator) and no odd characters. */
    private static boolean literalSafe(String s) {
        String first = s.lines().filter(x -> !x.isEmpty()).findFirst().orElse("");
        if (first.startsWith(" ") || first.startsWith("\t")) return false;
        for (char c : s.toCharArray())
            if (c != '\n' && c != '\t' && (c < 0x20 || c == 0x7f || c == '\u0085' || c == ' ' || c == ' ' || c == '﻿'))
                return false;
        return true;
    }

    private static void literal(StringBuilder sb, String s, int indent) {
        int trailing = 0;
        while (trailing < s.length() && s.charAt(s.length() - 1 - trailing) == '\n') trailing++;
        String body = s.substring(0, s.length() - trailing);
        sb.append(trailing == 0 ? " |-\n" : trailing == 1 ? " |\n" : " |+\n");
        String pad = " ".repeat(indent);
        for (String line : body.split("\n", -1)) sb.append(line.isEmpty() ? "" : pad + line).append('\n');
        for (int i = 1; i < trailing; i++) sb.append('\n');
    }

    private static String key(String k) {
        return PLAIN_KEY.matcher(k).matches() && !YAML_WORDS.contains(k.toLowerCase(java.util.Locale.ROOT)) ? k : quote(k);
    }

    private static String scalar(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) return v.toString();
        return quote(String.valueOf(v));
    }

    /** A JSON string is a valid YAML double-quoted scalar. */
    static String quote(String s) {
        return JSON.writeValueAsString(s);
    }
}
