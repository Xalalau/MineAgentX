package com.mineagent.engine.execution;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Monotonic admission and completion arbitration for server tools. */
public final class ExecutionProtocol {
    private ExecutionProtocol() { }

    public static boolean admits(BooleanSupplier current, LongSupplier clock, long deadline) {
        return current.getAsBoolean() && clock.getAsLong() - deadline < 0;
    }

    public enum State { PENDING, RUNNING, COMPLETED, EXPIRED }
    public record Terminal(State state, String payload) { }

    /** Extracted native callback arbitration; no Minecraft dependencies. */
    public static final class Call {
        private final BooleanSupplier current;
        private final LongSupplier clock;
        private final long deadline;
        private final Object gate;
        private final AtomicReference<Terminal> publication =
                new AtomicReference<>(new Terminal(State.PENDING, null));
        private final CountDownLatch done = new CountDownLatch(1);

        public Call(BooleanSupplier current, LongSupplier clock, long deadline) {
            this(current, clock, deadline, new Object());
        }

        Call(BooleanSupplier current, LongSupplier clock, long deadline, Object gate) {
            this.gate = gate;
            this.current = current;
            this.clock = clock;
            this.deadline = deadline;
        }

        public boolean admit() {
            synchronized (gate) {
                if (!admits(current, clock, deadline) || publication.get().state() != State.PENDING) return false;
                publication.set(new Terminal(State.RUNNING, null));
                return true;
            }
        }

        public boolean complete(String result, Runnable effects) {
            synchronized (gate) {
                if (publication.get().state() != State.RUNNING
                        || !admits(current, clock, deadline)) return false;
                publication.set(new Terminal(State.COMPLETED, java.util.Objects.requireNonNull(result)));
                try { effects.run(); }
                finally { done.countDown(); }
                return true;
            }
        }

        public boolean expire(String result) {
            if (isTerminal()) return false;
            synchronized (gate) {
                if (isTerminal()) return false;
                publication.set(new Terminal(State.EXPIRED, java.util.Objects.requireNonNull(result)));
                done.countDown();
                return true;
            }
        }

        public boolean isTerminal() {
            State state = publication.get().state();
            return state == State.COMPLETED || state == State.EXPIRED;
        }

        public boolean active() {
            return publication.get().state() == State.RUNNING && admits(current, clock, deadline);
        }

        public long remainingNanos() { return deadline - clock.getAsLong(); }

        public boolean await() throws InterruptedException {
            long remaining = remainingNanos();
            return isTerminal() || remaining > 0 && done.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        /** Execute only on the server; callbacks from worker threads are marshalled back there. */
        public void dispatch(Runnable before, java.util.function.Consumer<java.util.function.Consumer<String>> body,
                             java.util.function.Consumer<String> outcome, java.util.function.Consumer<String> late,
                             java.util.function.Function<Throwable, String> failure,
                             java.util.function.Consumer<Runnable> server, Runnable cancel, String unavailable) {
            if (!admit()) { expire(unavailable); return; }
            Thread serverThread = Thread.currentThread();
            var delivered = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<String> callback = result -> {
                if (!delivered.compareAndSet(false, true)) return;
                String safe = result == null ? failure.apply(new IllegalStateException("null tool callback")) : result;
                Runnable publish = () -> {
                    if (complete(safe, () -> outcome.accept(safe))) return;
                    expire(unavailable);
                    if (terminal().state() == State.EXPIRED) {
                        try { late.accept(safe); }
                        finally { cancel.run(); }
                    }
                };
                if (Thread.currentThread() == serverThread) publish.run();
                else {
                    try { server.accept(publish); }
                    catch (Throwable rejected) { expire(failure.apply(rejected)); cancel.run(); }
                }
            };
            try (var ignored = com.mineagent.api.execution.ExecutionToken.bind(this::active)) {
                if (!active()) { expire(unavailable); return; }
                before.run();
                // Owner input can arrive during checkpoint collection or just before tool entry.
                if (!active()) { expire(unavailable); return; }
                body.accept(callback);
            } catch (Throwable thrown) {
                // A tool can deliver its callback from a worker and then throw on the server.
                // Preserve that already selected callback rather than racing it with a synthetic failure.
                if (!delivered.get()) {
                    String error = failure.apply(thrown);
                    if (!complete(error, () -> outcome.accept(error))) expire(unavailable);
                }
            } finally {
                if (terminal().state() == State.EXPIRED) cancel.run();
            }
        }

        public Terminal requireImmediate(String unavailable) {
            expire(unavailable);
            return terminal();
        }

        public Terminal terminal() { return publication.get(); }
    }
}
