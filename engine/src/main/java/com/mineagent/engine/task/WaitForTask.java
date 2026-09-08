package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.management.WaitForTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Polls a bounded condition on the server thread and reports a real terminal result. */
public final class WaitForTask extends CompanionTask<WaitForTool.WaitForTaskRecord>
        implements IntentAwareTask {
    private long startedTick;
    private boolean started;
    private int stable;
    private boolean done;
    private String failReason;

    public WaitForTask(AgentPlayer player, WaitForTool.WaitForTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        // Tick zero is a valid world time. Using startedTick<=0 as an
        // initialization sentinel allowed every resume at tick 0 (and the
        // first resume after it) to reset the elapsed timer indefinitely.
        if (!started) {
            startedTick = now();
            started = true;
        }
        stable = 0;
        done = false;
        failReason = null;
    }

    @Override protected void onResume() {
        // Waiting has no volatile path or held item. Preserve elapsed time and
        // require the condition to be observed stable again after preemption.
        stable = 0;
        done = false;
        failReason = null;
    }

    @Override protected TaskState onTick() {
        if (record == null) {
            // Task records normally originate from validated tool arguments,
            // but scheduler extensions may construct them directly. Fail
            // deterministically instead of letting a malformed record escape
            // as a NullPointerException from the deadline check below.
            failReason = "Wait task record is null";
            return TaskState.FAILED;
        }
        if (record.kind == null || record.kind.isBlank()) {
            failReason = "Wait task kind is missing";
            return TaskState.FAILED;
        }
        long tick = now();
        if (record.deadline() > 0L && tick >= record.deadline()) {
            failReason = "Condition timed out after waiting "
                    + Math.max(0L, tick - startedTick) + " ticks";
            return TaskState.FAILED;
        }
        boolean satisfied = condition(tick);
        stable = satisfied ? stable + 1 : 0;
        if (stable >= Math.max(1, record.stableTicks)) {
            done = true;
            return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    private boolean condition(long tick) {
        if (record.kind == null) return false;
        var sp = TaskContext.serverPlayer(player);
        return switch (record.kind) {
            case "duration" -> tick - startedTick >= record.durationTicks;
            case "inventory" -> compare(inventoryCount(sp, record.itemId), record.count,
                    record.comparison);
            case "semantic" -> semanticCondition(tick);
            case "menu_slot" -> menuSlotCondition(sp);
            case "dimension" -> sp.level().dimension().location().toString()
                    .equals(record.dimension);
            case "block" -> blockCondition(sp);
            case "entity" -> entityCondition(sp);
            default -> false;
        };
    }

    private boolean semanticCondition(long tick) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) return false;
        var fact = loop.semanticWorldModel().find(record.subject, record.predicate, tick)
                .orElse(null);
        if (fact == null || fact.confidenceAt(tick) < 0.6) return false;
        if ("present".equals(record.comparison)) return true;
        if ("equals".equals(record.comparison)) return "*".equals(record.value)
                || (record.value != null && fact.value().equalsIgnoreCase(record.value));
        if (record.value == null) return false;
        try {
            double actual = Double.parseDouble(fact.value());
            double expected = Double.parseDouble(record.value);
            return "at_least".equals(record.comparison) ? actual >= expected : actual <= expected;
        } catch (NumberFormatException invalid) { return false; }
    }

    private boolean menuSlotCondition(net.minecraft.server.level.ServerPlayer sp) {
        var menu = sp.containerMenu;
        ItemStack stack;
        if ("player".equals(record.endpoint)) {
            // The player endpoint is the always-present player inventory. It
            // must work while no GUI is open; requiring a non-inventory menu
            // here made valid backpack waits fail forever.
            if (record.slot < 0 || record.slot >= sp.getInventory().getContainerSize()) return false;
            stack = sp.getInventory().getItem(record.slot);
        } else {
            // A container endpoint is meaningful only while that container
            // menu is actually open. Never read a stale or player-inventory
            // slot as if it belonged to a world container.
            if (menu == null || menu == sp.inventoryMenu) return false;
            if (record.slot < 0 || record.slot >= menu.slots.size()) return false;
            stack = menu.getSlot(record.slot).getItem();
        }
        int matchingCount;
        if (record.itemId == null) {
            matchingCount = stack.isEmpty() ? 0 : stack.getCount();
        } else {
            boolean matches = !stack.isEmpty() && BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString().equals(record.itemId);
            // Count the requested item, not arbitrary contents of the slot.
            // This makes equals/at_most 0 a real absence condition while
            // preserving exact-item checks for positive quantities.
            matchingCount = matches ? stack.getCount() : 0;
        }
        return compare(matchingCount, record.count, record.comparison);
    }

    private boolean blockCondition(net.minecraft.server.level.ServerPlayer sp) {
        BlockPos pos = new BlockPos(record.x, record.y, record.z);
        if (!sp.level().hasChunkAt(pos)) return false;
        String id = BuiltInRegistries.BLOCK.getKey(sp.level().getBlockState(pos).getBlock()).toString();
        return id.equals(record.blockId);
    }

    private boolean entityCondition(net.minecraft.server.level.ServerPlayer sp) {
        AABB box = new AABB(record.x - record.radius, record.y - record.radius,
                record.z - record.radius, record.x + record.radius,
                record.y + record.radius, record.z + record.radius);
        boolean found = sp.serverLevel().getEntitiesOfClass(Entity.class, box, entity ->
                entity != sp && entity.isAlive()
                        && (record.entityType == null || BuiltInRegistries.ENTITY_TYPE
                        .getKey(entity.getType()).toString().equals(record.entityType))).size() > 0;
        if (record.present || found) return record.present == found;
        // Absence is only evidence when the complete horizontal observation
        // area is loaded. Treating an unloaded chunk as "no entity" can make
        // a portal arrival, boss despawn, or machine-output wait succeed from
        // ignorance and corrupt the enclosing plan checkpoint.
        int minChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.minX));
        int maxChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.maxX));
        int minChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.minZ));
        int maxChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.maxZ));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!sp.serverLevel().hasChunk(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    private static int inventoryCount(net.minecraft.server.level.ServerPlayer sp, String id) {
        int total = 0;
        for (int slot = 0; slot < sp.getInventory().getContainerSize(); slot++) {
            ItemStack stack = sp.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean compare(int actual, int expected, String comparison) {
        return switch (comparison) {
            case "at_least" -> actual >= expected;
            case "at_most" -> actual <= expected;
            case "present" -> actual > 0;
            default -> actual == expected;
        };
    }

    private long now() { return TaskContext.serverPlayer(player).level().getGameTime(); }

    @Override protected void onInterrupt() { }

    @Override public TaskSnapshot snapshot() {
        long elapsed = !started ? 0L : Math.max(0L, now() - startedTick);
        String kind = record == null || record.kind == null ? "invalid" : record.kind;
        boolean positioned = "block".equals(kind) || "entity".equals(kind);
        int requiredStable = record == null ? 1 : Math.max(1, record.stableTicks);
        return TaskSnapshot.progress(kind, "Waiting for " + kind,
                stable, requiredStable,
                positioned && record != null ? record.x : null,
                positioned && record != null ? record.y : null,
                positioned && record != null ? record.z : null,
                done ? null : failReason,
                "elapsed_ticks=" + elapsed + " stable=" + stable + "/" + requiredStable,
                (elapsed << 8) ^ stable);
    }

    @Override public IntentContract intentContract() {
        String kind = record == null || record.kind == null ? "condition" : record.kind;
        return new IntentContract("Wait for " + kind,
                "The requested server condition remains true for the required stability window",
                null, null, null, IntentContract.TerrainPolicy.CONSERVATIVE,
                java.util.List.of());
    }

    @Override protected String successMessage() {
        if (record == null) return "Wait condition verified";
        return "Condition '" + record.kind + "' verified for "
                + Math.max(1, record.stableTicks) + " ticks";
    }
    @Override protected String timeoutMessage() {
        return failReason == null ? "Wait condition timed out" : failReason;
    }
    @Override protected String failureMessage() {
        return failReason == null ? "Wait condition failed" : failReason;
    }
}
