package com.mineagent.api.llm.model;

/** Defaults for local Bonsai inference; model aliases remain editable. */
public final class ModelFamilies {
    public static final String BONSAI_KATANA_MTP = "swift-bonsai2-27b-katana-mtp";

    private ModelFamilies() {}

    /** Leave space for server-side reasoning plus function calls and the final reply. */
    public static int defaultMaxTokens(String model) { return 4096; }
}
