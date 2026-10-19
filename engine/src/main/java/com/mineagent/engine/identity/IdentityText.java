package com.mineagent.engine.identity;

/** Small bilingual boundary; engine/config integration chooses the runtime language. */
public final class IdentityText {
    private IdentityText() {}
    public static String text(String language, String english, String portuguese) {
        return "pt_br".equalsIgnoreCase(language) ? portuguese : english;
    }
}
