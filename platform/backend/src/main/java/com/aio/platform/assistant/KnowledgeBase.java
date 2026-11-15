package com.aio.platform.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Retrieval for the assistant: the curated Markdown under {@code classpath:knowledge/}, split at headings and ranked
 * with BM25. In memory, built once at startup, no embeddings, no vector database: zero extra infrastructure and no
 * text sent anywhere to be indexed. The corpus is an allowlist: only files placed in that folder can ever reach a
 * model. pgvector hybrid search is the planned upgrade once the corpus outgrows keyword ranking.
 */
@Component
public class KnowledgeBase {
    public record Chunk(int id, String source, String heading, String text) {
        public String ref() { return source + (heading == null || heading.isBlank() ? "" : " › " + heading); }
    }

    public record Hit(Chunk chunk, double score) {}

    static final int MAX_CHUNK = 1800;
    private static final double K1 = 1.2, B = 0.75;
    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are",
            "be", "it", "this", "that", "with", "as", "by", "at", "from", "how", "what", "do", "does", "i", "we", "you",
            "can", "my", "our", "when", "why", "which", "who", "will", "if", "not", "no", "so", "then", "there", "about");

    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Map<String, Integer>> termFreqs = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private double avgLen;

    public KnowledgeBase() throws IOException {
        this(load());
    }

    /** source name -> Markdown text (tests and other corpora). */
    public KnowledgeBase(Map<String, String> documents) {
        documents.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> split(e.getKey(), e.getValue()).forEach(this::add));
        avgLen = termFreqs.stream().mapToInt(m -> m.values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1);
    }

    private static Map<String, String> load() throws IOException {
        Map<String, String> docs = new HashMap<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/**/*.md")) {
            try (InputStream in = r.getInputStream()) {
                docs.put(r.getFilename(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return docs;
    }

    public int size() { return chunks.size(); }

    public long documents() { return chunks.stream().map(Chunk::source).distinct().count(); }

    public List<Hit> search(String query, int k) {
        List<String> q = tokens(query).stream().distinct().toList();
        if (q.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>();
        int n = chunks.size();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFreqs.get(i);
            int len = tf.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (String t : q) {
                Integer f = tf.get(t);
                if (f == null) continue;
                double idf = Math.log(1 + (n - docFreq.get(t) + 0.5) / (docFreq.get(t) + 0.5));
                score += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * len / avgLen));
            }
            if (score > 0) hits.add(new Hit(chunks.get(i), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > k ? List.copyOf(hits.subList(0, k)) : hits;
    }

    private void add(Chunk c) {
        Chunk numbered = new Chunk(chunks.size(), c.source(), c.heading(), c.text());
        chunks.add(numbered);
        Map<String, Integer> tf = new HashMap<>();
        // headings count double: they name what a section is about
        for (String t : tokens(c.heading() == null ? "" : c.heading() + " " + c.heading())) tf.merge(t, 1, Integer::sum);
        for (String t : tokens(c.text())) tf.merge(t, 1, Integer::sum);
        termFreqs.add(tf);
        tf.keySet().forEach(t -> docFreq.merge(t, 1, Integer::sum));
    }

    /** Splits Markdown at headings; long sections are cut at paragraph breaks. */
    static List<Chunk> split(String source, String markdown) {
        List<Chunk> out = new ArrayList<>();
        String heading = null;
        StringBuilder buf = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("#")) {
                flush(out, source, heading, buf);
                heading = line.replaceFirst("^#+\\s*", "").trim();
                continue;
            }
            buf.append(line).append('\n');
        }
        flush(out, source, heading, buf);
        return out;
    }

    private static void flush(List<Chunk> out, String source, String heading, StringBuilder buf) {
        String text = buf.toString().strip();
        buf.setLength(0);
        if (text.isEmpty()) return;
        StringBuilder part = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n")) {
            if (part.length() > 0 && part.length() + para.length() > MAX_CHUNK) {
                out.add(new Chunk(-1, source, heading, part.toString().strip()));
                part.setLength(0);
            }
            part.append(para).append("\n\n");
        }
        if (part.length() > 0) out.add(new Chunk(-1, source, heading, part.toString().strip()));
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() < 2 || STOP.contains(t)) continue;
            out.add(stem(t));
        }
        return out;
    }

    /** Tiny suffix stemmer so "promotions", "promoted" and "promote" meet. */
    static String stem(String t) {
        if (t.length() > 5 && t.endsWith("ing")) return t.substring(0, t.length() - 3);
        if (t.length() > 4 && t.endsWith("ed")) return t.substring(0, t.length() - 2);
        if (t.length() > 4 && t.endsWith("es")) return t.substring(0, t.length() - 2);
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) return t.substring(0, t.length() - 1);
        if (t.length() > 4 && t.endsWith("e")) return t.substring(0, t.length() - 1);
        return t;
    }
}
