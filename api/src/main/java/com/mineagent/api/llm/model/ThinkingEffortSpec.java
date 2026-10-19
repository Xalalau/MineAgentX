package com.mineagent.api.llm.model;

import com.mineagent.api.i18n.LocalizedValidationException;

import java.util.List;
import java.util.Locale;

/** Local reasoning policy. The launcher owns thinking budgets and chat templates. */
public final class ThinkingEffortSpec {
    public static final String AUTO = "auto";
    public static final List<String> DEFAULT_LEVELS = List.of(AUTO);
    private final String model;

    private ThinkingEffortSpec(String model) {
        this.model = model == null ? "" : model;
    }

    /** Normalize user input without overriding the local server's template or budget. */
    public static String normalize(String effort) {
        if (effort == null || effort.isBlank() || AUTO.equalsIgnoreCase(effort.trim())) {
            return AUTO;
        }
        throw new LocalizedValidationException("validation.effort",
                "Local reasoning is server-managed; use auto or leave the field empty.", null);
    }

    /**
     * Migrate only recognized legacy budgets for the two supported local adapters.
     * The caller must still validate the saved endpoint; no connection fields change.
     */
    public static Restoration restore(String providerId, String effort) {
        if (!"openai-compatible".equals(providerId) && !"ollama".equals(providerId)) {
            throw new LocalizedValidationException("validation.saved_provider",
                    "Unsupported saved provider; select a supported local provider explicitly.", null);
        }
        String legacy = effort == null ? "" : effort.trim().toLowerCase(Locale.ROOT);
        if (List.of("off", "low", "medium", "high", "xhigh", "max").contains(legacy)) {
            return new Restoration(AUTO, "Legacy local reasoning effort '" + legacy
                    + "' migrated to auto; reasoning and chat templates remain server-managed.");
        }
        return new Restoration(normalize(effort), "");
    }

    public record Restoration(String effort, String diagnostic) {}

    public static ThinkingEffortSpec forModel(String model) { return new ThinkingEffortSpec(model); }
    public String model() { return model; }
    public List<String> supportedLevels() { return DEFAULT_LEVELS; }
    public String defaultLevel() { return AUTO; }
    public boolean canDisable() { return false; }
    public boolean supportsEffort() { return false; }
    public String hint() { return "auto (server reasoning defaults)"; }
}
