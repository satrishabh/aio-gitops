package com.aio.platform.assistant;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data before text leaves AIO for an external model, and again on the model's answer
 * (OWASP LLM Top 10: LLM02 sensitive information disclosure). Deliberately over-eager: a masked word in an answer is
 * a small cost, a leaked token is not.
 */
public final class Redactor {
    private Redactor() {}

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), "[private key]"),
            new Rule(Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), "[github token]"),
            new Rule(Pattern.compile("\\b(?:sk|gsk|rk|pk)[-_](?:[A-Za-z0-9_-]{2,20}[-_])?[A-Za-z0-9]{20,}"), "[api key]"),
            new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "[aws key]"),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), "[jwt]"),
            new Rule(Pattern.compile("(?i)(AccountKey|SharedAccessSignature|sig)=([^;&\\s]+)"), "$1=[secret]"),
            new Rule(Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s:/@]+:[^\\s@/]+@"), "$1[credentials]@"),
            new Rule(Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_-]?key|client[_-]?secret)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)"), "$1$2[secret]"),
            new Rule(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"), "[email]"),
            new Rule(Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b"), "[ip]"));

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(out);
            out = m.replaceAll(r.replacement());
        }
        return out;
    }

    /** True when {@link #redact} would change the text. */
    public static boolean containsSensitive(String text) {
        return text != null && !text.equals(redact(text));
    }
}
