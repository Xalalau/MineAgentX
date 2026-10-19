package com.mineagent.tools.i18n;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.RuntimeLanguage;
import java.util.Locale;
import java.util.Objects;

/** Pure renderers for observed base-tool data, without guessing translations of arbitrary text. */
public final class ToolOutput {
    private ToolOutput() {}

    /** Keep custom names verbatim; use exact registry IDs when no translated display name is available. */
    public static String itemLabel(String registryId, String observedName, boolean customName) {
        Objects.requireNonNull(registryId, "registryId");
        Objects.requireNonNull(observedName, "observedName");
        return customName || RuntimeLanguage.language() == Language.EN_US ? observedName : registryId;
    }

    /** Keep numeric protocol formatting independent of the JVM locale. */
    public static String formatted(String key, Object... arguments) {
        return String.format(Locale.ROOT, ToolMessages.text(key), arguments);
    }
}
