package com.mineagent.engine.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-evidence ledger for world-changing actions.
 *
 * <p>A tool callback is an admission signal, not proof that Minecraft changed
 * state.  The ledger records the semantic-world checkpoint before dispatch and
 * closes the entry only when a correlated result, task terminal event, timeout,
 * or cancellation is observed.  It is deliberately bounded and contains no
 * live Minecraft objects, prompts, or credentials.</p>
 */
public final class ActionTransactionLedger {
    public enum Status { DISPATCHED, PENDING, VERIFIED, FAILED, UNVERIFIED, CANCELLED }

    public record Transaction(String id, String tool, String predictedDelta,
                              long startedTick, long updatedTick,
                              long preRevision, long preEventSequence,
                              String preStateHash, String observedDelta,
                              long postRevision, long postEventSequence,
                              String postStateHash, Status status) {
        public Transaction {
            id = normalize(id, "action");
            tool = normalize(tool, "unknown");
            predictedDelta = compact(predictedDelta, 512);
            observedDelta = compact(observedDelta, 512);
            startedTick = Math.max(0L, startedTick);
            updatedTick = Math.max(startedTick, updatedTick);
            preRevision = Math.max(0L, preRevision);
            preEventSequence = Math.max(0L, preEventSequence);
            postRevision = Math.max(0L, postRevision);
            postEventSequence = Math.max(0L, postEventSequence);
            preStateHash = normalize(preStateHash, "unknown");
            postStateHash = normalize(postStateHash, "unknown");
            status = status == null ? Status.DISPATCHED : status;
        }

        public boolean terminal() {
            return status == Status.VERIFIED || status == Status.FAILED
                    || status == Status.UNVERIFIED || status == Status.CANCELLED;
        }
    }

    public record State(List<Transaction> transactions, long revision) {}

    private static final int MAX_TRANSACTIONS = 256;
    private final LinkedHashMap<String, Transaction> transactions = new LinkedHashMap<>();
    private long revision;

    public synchronized Transaction begin(String id, String tool, String predictedDelta,
                                          SemanticWorldModel world, long gameTick) {
        if (id == null || id.isBlank() || transactions.containsKey(id)) {
            return id == null ? null : transactions.get(id);
        }
        long preRevision = world == null ? 0L : world.revision();
        long sequence = world == null ? 0L : world.latestSequence();
        String hash = world == null ? "unknown" : world.stateHash(gameTick);
        Transaction value = new Transaction(id, tool, predictedDelta, gameTick, gameTick,
                preRevision, sequence, hash, "", preRevision, sequence, hash,
                Status.DISPATCHED);
        transactions.put(id, value);
        trim();
        revision++;
        return value;
    }

    public synchronized Transaction markPending(String id, SemanticWorldModel world,
                                                 long gameTick, String evidence) {
        return update(id, Status.PENDING, evidence, world, gameTick);
    }

    public synchronized Transaction finish(String id, Status status, String evidence,
                                            SemanticWorldModel world, long gameTick) {
        if (status == null || (status == Status.DISPATCHED || status == Status.PENDING)) {
            status = Status.UNVERIFIED;
        }
        return update(id, status, evidence, world, gameTick);
    }

    /**
     * Close a synchronous action only when the tool reports a structured
     * postcondition that it read back from the authoritative server state.
     *
     * <p>Semantic revisions cannot be used as proof here: recording the tool
     * outcome itself advances that revision, and an unrelated entity or
     * inventory heartbeat can do the same.  The small set of synchronous
     * physical tools therefore re-read the slot/menu/inventory state they
     * changed and emit {@code postcondition_verified=true}.  Malformed or
     * legacy callbacks remain unverified instead of becoming false facts.</p>
     */
    public synchronized Transaction finishObserved(String id, boolean callbackSuccess,
                                                   String evidence,
                                                   SemanticWorldModel world, long gameTick) {
        Transaction old = transactions.get(id);
        if (old == null) return null;
        boolean postconditionVerified = callbackSuccess
                && structuredBoolean(evidence, "postcondition_verified");
        Status status = postconditionVerified ? Status.VERIFIED
                : callbackSuccess ? Status.UNVERIFIED : Status.FAILED;
        return update(id, status, evidence, world, gameTick);
    }

    public synchronized Transaction get(String id) {
        return id == null ? null : transactions.get(id);
    }

    public synchronized List<Transaction> open() {
        return transactions.values().stream().filter(value -> !value.terminal()).toList();
    }

    /** Close entries which outlived their bounded execution window. */
    public synchronized List<Transaction> expire(long gameTick, long timeoutTicks,
                                                 SemanticWorldModel world) {
        long timeout = Math.max(20L, timeoutTicks);
        List<Transaction> expired = new ArrayList<>();
        for (Transaction transaction : List.copyOf(transactions.values())) {
            if (!transaction.terminal()
                    && gameTick - transaction.startedTick() > timeout) {
                expired.add(update(transaction.id(), Status.UNVERIFIED,
                        "transaction timeout", world, gameTick));
            }
        }
        return expired;
    }

    public synchronized State exportState() {
        return new State(List.copyOf(transactions.values()), revision);
    }

    public synchronized void importState(State state) {
        transactions.clear();
        if (state != null && state.transactions() != null) {
            state.transactions().stream().filter(value -> value != null)
                    .sorted(Comparator.comparingLong(Transaction::updatedTick))
                    .forEach(value -> {
                        // Body ownership cannot cross a restart.  Preserve the
                        // audit record but force any open action to revalidate
                        // from fresh server evidence instead of resuming it.
                        Transaction restored = value.terminal() ? value
                                : new Transaction(value.id(), value.tool(), value.predictedDelta(),
                                value.startedTick(), value.updatedTick(), value.preRevision(),
                                value.preEventSequence(), value.preStateHash(),
                                "interrupted by restart", value.postRevision(),
                                value.postEventSequence(), value.postStateHash(),
                                Status.UNVERIFIED);
                        transactions.put(restored.id(), restored);
                    });
            revision = Math.max(0L, state.revision()) + 1L;
        } else {
            revision = 0L;
        }
        trim();
    }

    public synchronized String summarizeForPrompt(long gameTick) {
        StringBuilder out = new StringBuilder("Action transactions:\n");
        List<Transaction> recent = transactions.values().stream()
                .sorted(Comparator.comparingLong(Transaction::updatedTick).reversed())
                .limit(6).toList();
        if (recent.isEmpty()) return "Action transactions: none\n";
        for (Transaction value : recent) {
            out.append("- ").append(value.id()).append(' ')
                    .append(value.tool()).append(" status=")
                    .append(value.status().name().toLowerCase(Locale.ROOT))
                    .append(" pre=").append(value.preStateHash())
                    .append(" post=").append(value.postStateHash());
            if (!value.observedDelta().isBlank()) out.append(" evidence=")
                    .append(value.observedDelta());
            out.append('\n');
        }
        out.append("Only VERIFIED actions may be assumed complete; retry or replan unverified actions.\n");
        return out.toString();
    }

    private Transaction update(String id, Status status, String evidence,
                               SemanticWorldModel world, long gameTick) {
        Transaction old = transactions.get(id);
        if (old == null) return null;
        // A transaction is an append-only audit fact once it reaches a
        // terminal state.  Late callbacks, timeout sweeps, and duplicate
        // network deliveries are common around an asynchronous server tick;
        // allowing any of them to overwrite VERIFIED with FAILED (or vice
        // versa) would make the planner believe a completed action failed and
        // could trigger duplicate crafting/placement.  Keep the first
        // terminal outcome and make all later updates idempotent.
        if (old.terminal()) return old;
        long postRevision = world == null ? old.postRevision() : world.revision();
        long postSequence = world == null ? old.postEventSequence() : world.latestSequence();
        String postHash = world == null ? old.postStateHash() : world.stateHash(gameTick);
        String delta = evidence == null || evidence.isBlank()
                ? world == null ? old.observedDelta()
                : world.deltaSummarySince(old.preEventSequence(), 8)
                : evidence;
        Transaction next = new Transaction(old.id(), old.tool(), old.predictedDelta(),
                old.startedTick(), gameTick, old.preRevision(), old.preEventSequence(),
                old.preStateHash(), delta, postRevision, postSequence, postHash, status);
        transactions.put(id, next);
        revision++;
        return next;
    }

    private void trim() {
        while (transactions.size() > MAX_TRANSACTIONS) {
            String key = transactions.values().stream()
                    .filter(Transaction::terminal)
                    .min(Comparator.comparingLong(Transaction::updatedTick))
                    .map(Transaction::id).orElse(transactions.keySet().iterator().next());
            transactions.remove(key);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : compact(value.trim(), 512);
    }

    private static String compact(String value, int limit) {
        String result = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return result.length() <= limit ? result : result.substring(0, limit) + "...";
    }

    private static boolean structuredBoolean(String json, String field) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return false;
            JsonObject object = parsed.getAsJsonObject();
            JsonElement value = object.get(field);
            return value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isBoolean()
                    && value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
