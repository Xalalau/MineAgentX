package com.mineagent.engine.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Owner-scoped work survives conversation, not explicit revocation. */
public final class ExecutionOwner {
    private final Object gate = new Object();
    private final Map<String, Lease> owned = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Consumer<Runnable> server;
    private volatile long revision;

    public long revision() { return revision; }

    public ExecutionOwner(LongSupplier clock, Consumer<Runnable> server) {
        this.clock = clock;
        this.server = server;
    }

    public Lease open(String id, BooleanSupplier current, long deadline, Runnable cancellation) {
        return open(id, current, current, deadline, cancellation);
    }

    /** Admission is reasoning-scoped; after admit, only work authority may revoke the lease. */
    public Lease open(String id, BooleanSupplier admission, BooleanSupplier authority,
                      long deadline, Runnable cancellation) {
        synchronized (gate) {
            Lease lease = new Lease(id, cancellation, admission, authority);
            lease.call = new ExecutionProtocol.Call(lease::active, clock, deadline, gate);
            owned.put(id, lease);
            return lease;
        }
    }

    public void invalidate(Runnable generationChange) {
        synchronized (gate) { generationChange.run(); }
    }

    public void revokeAll(String result, Runnable generationChange) {
        java.util.List<Lease> previous;
        synchronized (gate) {
            revision++;
            generationChange.run();
            previous = java.util.List.copyOf(owned.values());
            owned.clear();
            for (Lease lease : previous) {
                lease.revoked = true;
                lease.call.expire(result);
            }
        }
        for (Lease lease : previous) lease.requestCancellation();
    }

    public boolean revoke(String id, String result) {
        Lease lease;
        synchronized (gate) {
            lease = owned.get(id);
            if (lease == null) return false;
            lease.revoked = true;
            lease.call.expire(result);
        }
        lease.requestCancellation();
        return true;
    }

    public void release(String id) {
        synchronized (gate) { owned.remove(id); }
    }

    public final class Lease {
        private final String id;
        private final Runnable cancellation;
        private final BooleanSupplier current;
        private final BooleanSupplier admission;
        private volatile boolean revoked;
        private ExecutionProtocol.Call call;
        private Lease(String id, Runnable cancellation, BooleanSupplier admission, BooleanSupplier current) {
            this.id = id; this.cancellation = cancellation; this.admission = admission; this.current = current;
        }
        public ExecutionProtocol.Call call() { return call; }
        public boolean active() {
            ExecutionProtocol.State state = call == null ? ExecutionProtocol.State.PENDING : call.terminal().state();
            return !revoked && state != ExecutionProtocol.State.EXPIRED && current.getAsBoolean()
                    && (state != ExecutionProtocol.State.PENDING || admission.getAsBoolean());
        }
        public void release() { synchronized (gate) { owned.remove(id, this); } }
        public void requestCancellation() {
            if (cancellation == null) return;
            try {
                server.accept(() -> {
                    synchronized (gate) {
                        Lease replacement = owned.get(id);
                        if (replacement != null && replacement != this) return;
                    }
                    cancellation.run();
                });
            } catch (RuntimeException rejected) {
                // The token stays revoked. Never substitute off-thread inventory/navigation cleanup.
                System.err.println("[MineAgent] Owned cancellation could not be scheduled: " + rejected.getMessage());
            }
        }
    }
}
