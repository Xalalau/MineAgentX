package com.mineagent.api.i18n;

/** Supported runtime languages. Technical IDs are deliberately not locale aliases. */
public enum Language {
    EN_US("en_us"),
    PT_BR("pt_br");

    private final String id;

    Language(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** An explicit unsupported selection must never silently select another language. */
    public static final class UnsupportedLanguageException extends IllegalArgumentException {
        public UnsupportedLanguageException(String id) {
            super("Unsupported runtime language: " + id);
        }
    }

    public static Language fromId(String id) {
        for (Language language : values()) {
            if (language.id.equals(id)) return language;
        }
        throw new UnsupportedLanguageException(id);
    }
}
