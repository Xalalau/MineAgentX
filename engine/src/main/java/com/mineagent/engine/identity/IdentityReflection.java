package com.mineagent.engine.identity;

/** Sparse reflection piggybacks existing decision rounds; this class never schedules HTTP work. */
public final class IdentityReflection {
    public static final long INTERVAL_TICKS = 1_200L;
    private final EvolvingIdentity identity;
    private final java.util.function.IntSupplier configuredInterval;
    private long reviewedSequence;
    private long lastOpportunityTick = -1;
    private long generation;
    private int remaining;

    public synchronized EvolvingIdentity.Entry revise(long currentGeneration, String kind,
            String subject, String statement, String reason, java.util.List<String> evidenceIds,
            double confidence, long expectedRevision, String reconsideration) {
        if (configuredInterval.getAsInt() == 0) close();
        if (remaining <= 0 || generation != currentGeneration) {
            throw new IllegalArgumentException("reflection_closed_or_stale");
        }
        var entry = identity.revise(kind, subject, statement, reason, evidenceIds,
                confidence, expectedRevision, reconsideration);
        remaining--;
        return entry;
    }

    /** Cancels admission immediately; no mutation can begin after this returns. */
    public synchronized void close() { remaining = 0; }
    public record State(long reviewedSequence, long lastOpportunityTick) {}
    public synchronized State exportState() { return new State(reviewedSequence, lastOpportunityTick); }
    public synchronized void importState(State state) {
        close();
        reviewedSequence = state == null ? 0 : state.reviewedSequence();
        lastOpportunityTick = state == null ? -1 : state.lastOpportunityTick();
    }

    public IdentityReflection(EvolvingIdentity identity) {
        this(identity, () -> com.mineagent.api.config.MineAgentConfig.ReflectionConfig.DEFAULTS.intervalTicks());
    }

    public IdentityReflection(EvolvingIdentity identity, java.util.function.IntSupplier configuredInterval) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.configuredInterval = java.util.Objects.requireNonNull(configuredInterval, "configuredInterval");
    }

    public synchronized String context(String language, int maxChars) {
        if (configuredInterval.getAsInt() == 0) close();
        if (remaining <= 0 || maxChars <= 0) return "";
        String text = IdentityText.text(language,
                "A brief reflection opportunity is available in this decision turn. If useful, retrieve relevant evidence with identity_memory and retain at most " + remaining
                        + " justified updates. Unchanged beliefs and uncertainty are acceptable; do not invent reasons or delay urgent work.\n",
                "Há uma oportunidade breve de reflexão neste turno de decisão. Se útil, recupere evidências relevantes por identity_memory e retenha no máximo " + remaining
                        + " atualizações justificadas. Manter crenças e incerteza é aceitável; não invente motivos nem atrase trabalho urgente.\n");
        return text.length() <= maxChars ? text : "";
    }

    public synchronized boolean open(long tick, long generation, boolean bodyBusy) {
        int interval = new com.mineagent.api.config.MineAgentConfig.ReflectionConfig(configuredInterval.getAsInt()).intervalTicks();
        if (interval == 0) { close(); return false; }
        if (bodyBusy || identity.evidenceSequence() <= reviewedSequence) return false;
        if (lastOpportunityTick >= 0 && tick >= lastOpportunityTick
                && tick - lastOpportunityTick < interval) return false;
        reviewedSequence = identity.evidenceSequence();
        lastOpportunityTick = Math.max(0, tick);
        this.generation = generation;
        remaining = 2;
        return true;
    }
}
