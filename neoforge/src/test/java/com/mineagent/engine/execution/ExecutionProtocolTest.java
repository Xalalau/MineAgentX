package com.mineagent.engine.execution;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class ExecutionProtocolTest {
    @Test void rejectedCancellationQueueStillRevokesEveryOwnedExecutionWithoutWorldFallback() {
        var worldCalls = new AtomicInteger();
        var scheduled = new AtomicInteger();
        var owner = new ExecutionOwner(() -> 1, task -> {
            scheduled.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("server unavailable");
        });
        var body = owner.open("body", () -> true, 10, worldCalls::incrementAndGet);
        var skill = owner.open("skill", () -> true, 10, worldCalls::incrementAndGet);
        assertDoesNotThrow(() -> owner.revokeAll("cancelled", () -> { }));
        assertFalse(body.active());
        assertFalse(skill.active());
        assertEquals(2, scheduled.get(), "one rejected enqueue must not skip other owned cancellations");
        assertEquals(0, worldCalls.get(), "never run world cleanup on the caller as a fallback");
    }

    @Test void acceptedBodyTaskIsRevokedOnServerNotOnReasoningThread() throws Exception {
        java.util.concurrent.BlockingQueue<Runnable> server = new java.util.concurrent.LinkedBlockingQueue<>();
        var owner = new ExecutionOwner(() -> 1, server::add);
        var task = new AtomicReference<>("old");
        var cancelledOn = new AtomicReference<Thread>();
        var lease = owner.open("old", () -> true, 10, () -> {
            cancelledOn.set(Thread.currentThread());
            task.compareAndSet("old", null);
        });
        assertTrue(lease.call().admit());
        assertTrue(lease.call().complete("async_pending", () -> { }));
        Thread reasoning = Thread.ofPlatform().start(() -> owner.revokeAll("cancelled", () -> { }));
        reasoning.join(2000);
        assertFalse(lease.active(), "async admission does not relinquish cancellation ownership");
        assertNull(cancelledOn.get(), "world cleanup must only run after server queue drain");
        assertEquals("old", task.get());
        server.remove().run();
        assertSame(Thread.currentThread(), cancelledOn.get());
        assertNull(task.get());
    }

    @Test void queuedCancellationCannotStopAnUnrelatedReplacementTask() {
        ArrayDeque<Runnable> server = new ArrayDeque<>();
        var owner = new ExecutionOwner(() -> 1, server::add);
        var task = new AtomicReference<>("old");
        AtomicInteger wrongCancellations = new AtomicInteger();
        owner.open("old", () -> true, 10, () -> {
            if ("old".equals(task.get())) task.set(null);
            else wrongCancellations.incrementAndGet();
        });
        owner.revokeAll("cancelled", () -> { });
        task.set("new");
        // This models PriorityAuction's exact-ID check at server execution, not at enqueue time.
        if (!server.isEmpty()) server.remove().run();
        assertEquals("new", task.get());
        assertEquals(1, wrongCancellations.get(), "the exact-ID guard runs on the server after replacement");
    }

    @Test void revocationBetweenAtomicBatchesKeepsAlreadyCommittedPhysicalItems() {
        var permitted = new java.util.concurrent.atomic.AtomicBoolean(true);
        AtomicInteger delivered = new AtomicInteger();
        int committed = 0;
        try (var ignored = com.mineagent.api.execution.ExecutionToken.bind(permitted::get)) {
            for (int batch = 0; batch < 5; batch++) {
                if (!com.mineagent.api.execution.ExecutionToken.active()) break;
                delivered.addAndGet(4);
                committed++;
                permitted.set(false);
            }
        }
        assertEquals(1, committed, "revocation stops only subsequent atomic batches");
        assertEquals(4, delivered.get(), "already delivered output is not fabricated away");
        assertTrue(com.mineagent.api.execution.ExecutionToken.active(), "scope does not leak between server calls");
    }

    @Test void callbackRechecksDeadlineBeforeAnyLearnedOutcome() {
        AtomicLong now = new AtomicLong(1);
        AtomicInteger learned = new AtomicInteger();
        var call = new ExecutionProtocol.Call(() -> true, now::get, 10);
        assertTrue(call.admit());
        now.set(10);
        assertFalse(call.complete("delivered", learned::incrementAndGet));
        assertEquals(0, learned.get());
    }

    @Test void callbackRechecksGenerationBeforeAnyLearnedOutcome() {
        AtomicLong generation = new AtomicLong(1);
        AtomicInteger learned = new AtomicInteger();
        var call = new ExecutionProtocol.Call(() -> generation.get() == 1, () -> 1, 10);
        assertTrue(call.admit());
        generation.incrementAndGet();
        assertFalse(call.complete("delivered", learned::incrementAndGet));
        assertEquals(0, learned.get());
    }

    @Test void terminalOwnershipAndPayloadPrecedeLedgerAndIdentityEffects() throws Exception {
        var call = new ExecutionProtocol.Call(() -> true, () -> 1, 10);
        assertTrue(call.admit());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread callback = Thread.ofPlatform().start(() -> call.complete("committed receipt", () -> {
            entered.countDown();
            try { assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }));
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        try {
            assertEquals(new ExecutionProtocol.Terminal(ExecutionProtocol.State.COMPLETED,
                    "committed receipt"), call.terminal(), "terminal ownership includes its payload before effects");
            assertFalse(call.expire("timeout"), "waiter cannot steal publication during terminal effects");
        } finally {
            release.countDown(); callback.join(2000);
        }
        assertFalse(callback.isAlive());
    }

    @Test void deadlineIsRecheckedWhenQueuedServerWorkIsAdmitted() {
        AtomicLong now = new AtomicLong(1);
        AtomicInteger bodies = new AtomicInteger();
        ArrayDeque<Runnable> server = new ArrayDeque<>();
        server.add(() -> {
            if (ExecutionProtocol.admits(() -> true, now::get, 10)) bodies.incrementAndGet();
        });
        now.set(10);
        server.remove().run();
        assertEquals(0, bodies.get(), "an expired queued execution must not enter world code");
    }
}
