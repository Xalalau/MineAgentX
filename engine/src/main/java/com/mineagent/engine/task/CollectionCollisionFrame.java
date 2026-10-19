package com.mineagent.engine.task;

import com.mineagent.api.task.CompanionTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Synchronous receipts and conservative request fencing for exactly retained work. */
public final class CollectionCollisionFrame implements AutoCloseable {
    private static final ThreadLocal<CollectionCollisionFrame> CURRENT = new ThreadLocal<>();
    private final CollectionCollisionFrame previous;
    private final CollectItemsTask task;
    private final ServerPlayer player;
    private record Attempt(CollectionPickupObservation observation, int reserved) {}
    private final Map<ItemEntity, Attempt> attempts = new IdentityHashMap<>();
    private final Consumer<ItemEntityPickupEvent.Pre> pre = this::before;
    private final Consumer<ItemEntityPickupEvent.Post> post = this::after;
    private boolean closed;

    private CollectionCollisionFrame(CollectItemsTask task) {
        this.previous = CURRENT.get();
        this.task = task;
        this.player = TaskContext.serverPlayer(task.player());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ItemEntityPickupEvent.Pre.class, pre);
        try {
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ItemEntityPickupEvent.Post.class, post);
        } catch (RuntimeException | Error failure) {
            NeoForge.EVENT_BUS.unregister(pre);
            throw failure;
        }
        CURRENT.set(this);
    }

    /** Open only for the scheduler's unpaused task, and always close around physical tick. */
    public static CollectionCollisionFrame open(CompanionTask<?> task) {
        if (!(task instanceof CollectItemsTask collection) || !collection.observesCollisions()
                || observes(collection)) return null;
        return new CollectionCollisionFrame(collection);
    }

    /** Span auction and physics, including a task that may resume or finish in that auction. */
    public static CollectionCollisionFrame openTick(CompanionTask<?> task) {
        if (!(task instanceof CollectItemsTask collection) || observes(collection)) return null;
        return new CollectionCollisionFrame(collection);
    }

    static boolean observes(CollectItemsTask task) {
        CollectionCollisionFrame frame = CURRENT.get();
        return frame != null && !frame.closed && frame.task == task;
    }

    private boolean accepts(net.minecraft.world.entity.player.Player player, ItemEntity entity) {
        return !closed && CURRENT.get() == this && player == this.player
                && (task.observesCollisions() || task.fencesTerminalCollisions()) && task.coversCollision(entity);
    }

    private void before(ItemEntityPickupEvent.Pre event) {
        if (!accepts(event.getPlayer(), event.getItemEntity()) || event.canPickup().isFalse()) return;
        ItemEntity entity = event.getItemEntity();
        if (!event.canPickup().isTrue() && (entity.hasPickUpDelay()
                || entity.getTarget() != null && !entity.getTarget().equals(player.getUUID()))) return;
        int reserved = 0;
        if (task.capsCollisions()) {
            reserved = entity.getItem().getCount();
            if (!task.reserveCollisionPickup(reserved)) {
                // Never grant pickup, split stacks or change another player's policy.
                event.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE);
                return;
            }
        }
        attempts.put(entity, new Attempt(
                new CollectionPickupObservation(event.getItemEntity(), player.getInventory()), reserved));
    }

    private void after(ItemEntityPickupEvent.Post event) {
        if (closed || CURRENT.get() != this || event.getPlayer() != player
                || !task.observesCollisions()) return;
        Attempt attempt = attempts.remove(event.getItemEntity());
        if (attempt == null) return;
        attempt.observation().record(event);
        // Corroborate while still inside Post, before vanilla resets a consumed stack.
        task.settleCollisionPickup(attempt.reserved(), attempt.observation().delivered());
    }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Collision frames must close in stack order");
        closed = true;
        try {
            NeoForge.EVENT_BUS.unregister(pre);
        } finally {
            try { NeoForge.EVENT_BUS.unregister(post); }
            finally {
                attempts.clear();
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }
    }
}
