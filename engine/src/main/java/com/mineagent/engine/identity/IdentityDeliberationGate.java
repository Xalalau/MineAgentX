package com.mineagent.engine.identity;

/**
 * Pure, in-memory admission policy; it neither schedules inference nor chooses an action.
 * Keep one instance per loop lifetime and pass validated identity snapshots. Only a new goal,
 * or a newer revision citing previously unoffered literal origin/source/text, is eligible.
 * Unlinked observations, new evidence IDs/ticks alone and elapsed time never reopen a goal.
 * Saturation fails closed instead of evicting deduplication history; restarting a loop resets it.
 */
public final class IdentityDeliberationGate {
    public record Opportunity(String subject, long revision, java.util.List<String> evidenceIds) {
        public Opportunity { evidenceIds = java.util.List.copyOf(evidenceIds); }
    }
    public static final long COOLDOWN_TICKS = 1_200L;
    private static final int MAX_CITED_EVIDENCE = 384;
    private int retainedEvidence;
    private long lastOpportunityTick = -1;
    private record EvidenceKey(String origin, String source, String text) {}
    private record Offered(long revision, java.util.Set<EvidenceKey> evidence) {}
    private final java.util.Map<String, Offered> offered = new java.util.HashMap<>();

    /** Returns and consumes one opportunity, or null without consuming; call only at real admission. */
    public synchronized Opportunity admit(long tick, EvolvingIdentity.State state,
                                           boolean activeTask, boolean suspended, boolean deliberating) {
        if (tick < 0 || activeTask || suspended || deliberating) return null;
        if (lastOpportunityTick >= 0 && (tick < lastOpportunityTick
                || tick - lastOpportunityTick < COOLDOWN_TICKS)) return null;
        var evidence = new java.util.HashMap<String, EvidenceKey>();
        for (var item : state.evidence()) {
            evidence.put(item.id(), new EvidenceKey(item.origin(), item.source(), item.text()));
        }
        for (var entry : state.entries()) {
            if (!entry.kind().equals("goal")) continue;
            Offered previous = offered.get(entry.subject());
            if (previous != null && entry.revision() <= previous.revision()) continue;
            var cited = new java.util.HashSet<EvidenceKey>();
            for (String id : entry.evidenceIds()) cited.add(evidence.get(id));
            if (cited.isEmpty() || cited.contains(null)) continue;
            if (previous != null && previous.evidence().containsAll(cited)) continue;
            if (previous != null) cited.addAll(previous.evidence());
            int added = cited.size() - (previous == null ? 0 : previous.evidence().size());
            if (retainedEvidence + added > MAX_CITED_EVIDENCE) continue;
            retainedEvidence += added;
            offered.put(entry.subject(), new Offered(entry.revision(), java.util.Set.copyOf(cited)));
            lastOpportunityTick = tick;
            return new Opportunity(entry.subject(), entry.revision(), entry.evidenceIds());
        }
        return null;
    }
}
