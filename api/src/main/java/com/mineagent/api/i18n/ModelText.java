package com.mineagent.api.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Objects;

/** Render trusted framing separately from arbitrary player/stored text. Not a translator. */
public final class ModelText {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    private ModelText() {}

    /** Natural attribution belongs to trusted framing, not a player-supplied dialogue protocol. */
    public static String playerMessage(Language language, String speaker, String text) {
        return Messages.text(language, "model.player_message",
                JSON.toJson(Objects.requireNonNull(speaker, "speaker")),
                JSON.toJson(Objects.requireNonNull(text, "text")));
    }

    public static String companionMessage(Language language, String speaker, String text) {
        return Messages.text(language, "model.companion_message",
                JSON.toJson(Objects.requireNonNull(speaker, "speaker")),
                JSON.toJson(Objects.requireNonNull(text, "text")));
    }

    /** JSON quoting preserves content and prevents forged line boundaries; it is not a security sandbox. */
    public static String external(Language language, String text) {
        return Messages.text(language, "model.external", JSON.toJson(Objects.requireNonNull(text, "text")));
    }
}
