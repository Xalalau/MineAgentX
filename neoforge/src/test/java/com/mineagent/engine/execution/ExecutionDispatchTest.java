package com.mineagent.engine.execution;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

final class ExecutionDispatchTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nativeAndSkillAdmissionUseTheSameDeadlineGate(boolean skill) {
        var now = new AtomicLong(1);
        var call = new ExecutionProtocol.Call(() -> true, now::get, 10);
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        var server = new ArrayDeque<Runnable>();
        Runnable dispatch = () -> call.dispatch(() -> { }, reply -> bodies.incrementAndGet(),
                raw -> fail("expired action cannot teach an outcome"), raw -> fail("no body receipt"),
                failure -> "thrown", server::add, () -> { }, "expired");
        if (!skill) server.add(dispatch);
        now.set(10);
        if (skill) dispatch.run(); else server.remove().run();
        assertEquals(0, bodies.get());
        assertEquals(new ExecutionProtocol.Terminal(ExecutionProtocol.State.EXPIRED, "expired"), call.terminal());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nativeAndSkillRevocationAfterCheckpointStopsToolEntry(boolean skill) {
        var server = new ArrayDeque<Runnable>();
        var owner = new ExecutionOwner(() -> 1, server::add);
        var lease = owner.open("action", () -> true, 10, null);
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        Runnable dispatch = () -> lease.call().dispatch(
                () -> owner.revokeAll("owner changed", () -> { }), reply -> bodies.incrementAndGet(),
                raw -> fail("revoked action cannot teach an outcome"), raw -> fail("no body receipt"),
                failure -> "thrown", server::add, () -> { }, "revoked");
        if (skill) dispatch.run(); else { server.add(dispatch); server.remove().run(); }
        assertEquals(0, bodies.get());
        assertEquals("owner changed", lease.call().terminal().payload());
    }

    @Test void workerCallbackQueuedPastDeadlineIsObservedButNeverLearned() throws Exception {
        var now = new AtomicLong(1);
        var server = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var call = new ExecutionProtocol.Call(() -> true, now::get, 10);
        var learned = new ArrayList<String>();
        var observed = new ArrayList<String>();
        var callback = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
        call.dispatch(() -> { }, callback::set, learned::add, observed::add,
                failure -> "thrown", server::add, () -> { }, "expired");
        Thread worker = Thread.ofPlatform().start(() -> callback.get().accept("real partial receipt"));
        worker.join(2000);
        assertTrue(learned.isEmpty());
        assertTrue(observed.isEmpty(), "worker callback must not touch server observations");
        now.set(10);
        server.remove().run();
        assertTrue(learned.isEmpty());
        assertEquals(java.util.List.of("real partial receipt"), observed);
        assertEquals("expired", call.terminal().payload());
        callback.get().accept("duplicate cannot overwrite");
        assertEquals(1, observed.size());
    }

    @Test void skillLeaseRegisteredAfterOwnerChangedCannotContinueOldWork() {
        var owner = new ExecutionOwner(() -> 1, Runnable::run);
        long captured = owner.revision();
        owner.revokeAll("new owner input", () -> { });
        var stale = owner.open("skill", () -> owner.revision() == captured, 10, null);
        assertFalse(stale.active());
        assertFalse(stale.call().admit());
    }

    @Test void skillImmediateContractRevokesDeferredCallbacksInsteadOfLearningThemLater() {
        var call = new ExecutionProtocol.Call(() -> true, () -> 1, 10);
        var callback = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
        var learned = new ArrayList<String>();
        var receipts = new ArrayList<String>();
        call.dispatch(() -> { }, callback::set, learned::add, receipts::add,
                failure -> "thrown", Runnable::run, () -> { }, "cancelled");
        assertEquals(new ExecutionProtocol.Terminal(ExecutionProtocol.State.EXPIRED, "cancelled"),
                call.requireImmediate("cancelled"));
        callback.get().accept("actual late physical receipt");
        assertTrue(learned.isEmpty());
        assertEquals(java.util.List.of("actual late physical receipt"), receipts);
        assertEquals("cancelled", call.terminal().payload());
    }

    @Test void realCallbackQueuedFromWorkerWinsOverSubsequentToolThrow() {
        var server = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var call = new ExecutionProtocol.Call(() -> true, () -> 1, 10);
        var learned = new ArrayList<String>();
        call.dispatch(() -> { }, reply -> {
            Thread worker = Thread.ofPlatform().start(() -> reply.accept("physical receipt"));
            try { worker.join(2000); } catch (InterruptedException e) { throw new AssertionError(e); }
            throw new IllegalStateException("after callback");
        }, learned::add, raw -> fail("not a late receipt"), failure -> "thrown", server::add, () -> { }, "cancelled");
        assertEquals(ExecutionProtocol.State.RUNNING, call.terminal().state(),
                "a tool throw must not overtake its already delivered worker callback");
        server.remove().run();
        assertEquals("physical receipt", call.terminal().payload());
        assertEquals(java.util.List.of("physical receipt"), learned);
    }
}
