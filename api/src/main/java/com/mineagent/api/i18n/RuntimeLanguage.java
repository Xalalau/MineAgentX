package com.mineagent.api.i18n;

/** Explicit server-owned selection, independent of the JVM and client UI locales. */
public final class RuntimeLanguage {
    private static volatile Language language = Language.EN_US;

    private RuntimeLanguage() {}

    public static Language language() {
        return language;
    }

    /** Invalid input leaves the current selection unchanged. Call at initialization/reload. */
    public static void configure(String id) {
        language = Language.fromId(id);
    }
}
