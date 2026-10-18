package com.mineagent.api.i18n;

import java.util.List;
import java.util.Objects;

/** Persist trusted message identity and literal data, then render at the consumption boundary. */
public record LocalizedMessage(String key, List<String> arguments) {
    public LocalizedMessage {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("A locale key is required");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    /** Use ModelText.external for legacy/user prose, never turn such prose into a catalog key. */
    public String render(Language language, MessageCatalog catalog) {
        return Objects.requireNonNull(catalog, "catalog").text(language, key, arguments.toArray());
    }
}
