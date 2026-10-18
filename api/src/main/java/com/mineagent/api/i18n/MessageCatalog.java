package com.mineagent.api.i18n;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable bilingual catalog with no fallback and single-pass literal argument insertion. */
public final class MessageCatalog {
    private static final Pattern ARGUMENT = Pattern.compile("\\{([0-9]+)\\}");
    private final Map<Language, Map<String, String>> translations;

    /** Every supported language must define the same nonempty key set and argument positions. */
    public MessageCatalog(Map<Language, Map<String, String>> translations) {
        Objects.requireNonNull(translations, "translations");
        EnumMap<Language, Map<String, String>> copy = new EnumMap<>(Language.class);
        Set<String> keys = null;
        for (Language language : Language.values()) {
            Map<String, String> entries = translations.get(language);
            if (entries == null || entries.isEmpty()) {
                throw new IllegalArgumentException("Missing locale catalog: " + language.id());
            }
            for (var entry : entries.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new IllegalArgumentException("Blank locale entry: " + language.id() + "/" + entry.getKey());
                }
                Set<Integer> positions = positions(entry.getValue());
                if (!positions.isEmpty() && positions.stream().mapToInt(Integer::intValue).max().orElseThrow()
                        != positions.size() - 1) {
                    throw new IllegalArgumentException("Noncontiguous locale arguments: " + entry.getKey());
                }
            }
            if (keys == null) keys = Set.copyOf(entries.keySet());
            if (!keys.equals(entries.keySet())) {
                throw new IllegalArgumentException("Locale key mismatch: " + language.id());
            }
            copy.put(language, Map.copyOf(entries));
        }
        for (String key : keys) {
            Set<Integer> expected = positions(copy.get(Language.EN_US).get(key));
            for (Language language : Language.values()) {
                if (!expected.equals(positions(copy.get(language).get(key)))) {
                    throw new IllegalArgumentException("Locale argument mismatch: " + language.id() + "/" + key);
                }
            }
        }
        this.translations = Map.copyOf(copy);
    }

    public Set<String> keys() {
        return translations.get(Language.EN_US).keySet();
    }

    public String text(Language language, String key, Object... arguments) {
        Map<String, String> entries = translations.get(Objects.requireNonNull(language, "language"));
        String template = key == null ? null : entries.get(key);
        if (template == null) {
            throw new IllegalArgumentException("Missing locale key: " + language.id() + "/" + key);
        }
        Objects.requireNonNull(arguments, "arguments");
        int required = positions(template).size();
        if (arguments.length != required) {
            throw new IllegalArgumentException("Expected " + required + " locale arguments: " + key);
        }
        Matcher matcher = ARGUMENT.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = Objects.requireNonNull(arguments[Integer.parseInt(matcher.group(1))], "argument");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Set<Integer> positions(String template) {
        Set<Integer> positions = new TreeSet<>();
        Matcher matcher = ARGUMENT.matcher(template);
        while (matcher.find()) positions.add(Integer.parseInt(matcher.group(1)));
        return positions;
    }
}
