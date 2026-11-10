package io.github.khaledx5.notifications.api.domain;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable notification content with {@code {{placeholder}}} variables.
 *
 * @param key stable identifier chosen by the client, for example {@code order-shipped}
 * @param subject optional; channels such as SMS have none
 * @param body text with zero or more placeholders
 */
public record Template(String key, String subject, String body) {

    public static final int MAX_KEY_LENGTH = 64;

    /** Lowercase words separated by single hyphens. */
    public static final String KEY_PATTERN = "[a-z0-9]+(-[a-z0-9]+)*";

    private static final Pattern KEY = Pattern.compile(KEY_PATTERN);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    public Template {
        if (key == null || key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("key must be lowercase words separated by hyphens, at most %d characters"
                    .formatted(MAX_KEY_LENGTH));
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        subject = subject == null || subject.isBlank() ? null : subject;
    }

    /** Names of every placeholder used in the subject and the body, in order of first use. */
    public Set<String> placeholders() {
        Set<String> names = new LinkedHashSet<>();
        collectPlaceholders(subject, names);
        collectPlaceholders(body, names);
        return names;
    }

    /**
     * Fills in the placeholders.
     *
     * @throws MissingTemplateVariableException if a placeholder has no value; lists all missing names
     */
    public RenderedTemplate render(Map<String, String> variables) {
        Set<String> missing = new LinkedHashSet<>(placeholders());
        missing.removeIf(name -> variables.get(name) != null);
        if (!missing.isEmpty()) {
            throw new MissingTemplateVariableException(key, missing);
        }
        return new RenderedTemplate(fill(subject, variables), fill(body, variables));
    }

    private static void collectPlaceholders(String text, Set<String> names) {
        if (text == null) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }

    private static String fill(String text, Map<String, String> variables) {
        if (text == null) {
            return null;
        }
        return PLACEHOLDER.matcher(text).replaceAll(match -> Matcher.quoteReplacement(variables.get(match.group(1))));
    }
}
