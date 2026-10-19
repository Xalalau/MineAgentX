package com.mineagent.engine.construction;

import com.google.gson.*;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.construction.ConstructionDesign.Point;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.tools.block.BuildTool;
import com.mineagent.tools.management.TaskStatusTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Server-thread adapter; all mutations go through BuildTool and existing player actions. */
public final class MinecraftConstructionBridge implements ConstructionExecution.Port {
    private static final String PROCESS_SESSION = UUID.randomUUID().toString();
    public static final int OBSERVATION_RANGE = 96;
    private final AgentPlayer player;
    private final Set<Point> assetPositions = new HashSet<>();
    private final List<JsonObject> knownAssets = new ArrayList<>();
    public MinecraftConstructionBridge(AgentPlayer player) {
        this.player = Objects.requireNonNull(player);
        var loop = TaskContext.agentLoop(player);
        if (loop != null) {
            var current = position();
            for (var asset : loop.worldAssetIndex().snapshot()) {
                var p = asset.position();
                if (p == null || !p.dimension().equals(dimension())) continue;
                boolean existing = asset.stored() || asset.scope() == WorldAssetIndex.Scope.WORLD_OBJECT
                        && (asset.capabilities().contains("world:placed") || asset.capabilities().contains("store_items")
                        || asset.kind().equals("bed") || asset.kind().startsWith("station_") || asset.kind().equals("portal"));
                if (!existing) continue;
                Point point = new Point(p.x(), p.y(), p.z()); assetPositions.add(point);
                if (knownAssets.size() >= 16 || distance(current, point) > OBSERVATION_RANGE) continue;
                var value = new JsonObject(); value.addProperty("resource_id", asset.resourceId());
                value.addProperty("kind", asset.kind()); value.addProperty("count", asset.count());
                value.addProperty("observed_tick", asset.observedTick()); value.addProperty("confidence", asset.confidence());
                value.addProperty("dimension", p.dimension());
                var coordinate = new JsonObject(); coordinate.addProperty("x", p.x()); coordinate.addProperty("y", p.y()); coordinate.addProperty("z", p.z());
                value.add("position", coordinate); value.addProperty("stored", asset.stored()); knownAssets.add(value);
            }
        }
    }
    public static String storageKey(UUID owner, String companionName) {
        Objects.requireNonNull(owner); Objects.requireNonNull(companionName);
        return owner + "_" + UUID.nameUUIDFromBytes(companionName.getBytes(StandardCharsets.UTF_8));
    }
    /** No persisted companion UUID exists in CompanionStore. Refuse ambiguous name-key changes, never guess a migration. */
    public static boolean canRename(Path world, UUID owner, String oldName, String newName) {
        if (world == null || owner == null || oldName == null || newName == null) return false;
        if (oldName.equals(newName)) return true;
        Path base = world.resolve("mineagent_construction");
        // Existing empty namespaces also fail closed; they may contain interrupted or unreadable data.
        return java.nio.file.Files.notExists(base.resolve(storageKey(owner, oldName)), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && java.nio.file.Files.notExists(base.resolve(storageKey(owner, newName)), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }
    public static boolean canRename(AgentPlayer player, String newName) {
        return player != null && canRename(MineAgentEngine.getWorldDataDir(), player.ownerUuid(), player.companionName(), newName);
    }
    /** Uses the same world data root as existing companion memory; the exact owner/name key survives recreation, not rename. */
    public static ConstructionSession open(AgentPlayer player) throws IOException {
        Path world = MineAgentEngine.getWorldDataDir();
        if (world == null) throw new IOException("world_unavailable");
        Path root = world.resolve("mineagent_construction").resolve(storageKey(player.ownerUuid(), player.companionName()));
        return new ConstructionSession(new ConstructionRepository(root), new MinecraftConstructionBridge(player));
    }
    @Override public String dimension() { return player.dimensionKey(); }
    @Override public Point position() {
        var pos = TaskContext.serverPlayer(player).blockPosition(); return new Point(pos.getX(), pos.getY(), pos.getZ());
    }
    @Override public long tick() { return TaskContext.serverPlayer(player).level().getGameTime(); }
    @Override public String sessionId() { return PROCESS_SESSION + ":" + player.companionId(); }
    @Override public SiteAssessment.Observation observe(Point point) {
        var sp = TaskContext.serverPlayer(player); var level = sp.serverLevel();
        BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
        if (distance(position(), point) > OBSERVATION_RANGE || point.y() < level.getMinBuildHeight()
                || point.y() >= level.getMaxBuildHeight() || !level.getWorldBorder().isWithinBounds(pos)
                || !level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        String block = state.isAir() ? "minecraft:air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean protectedBlock = state.getDestroySpeed(level, pos) < 0 || !level.mayInteract(sp, pos) || !sp.mayBuild();
        boolean asset = state.hasBlockEntity() || assetPositions.contains(point) || state.is(BlockTags.BEDS);
        int breakTicks = 0;
        if (!state.isAir()) {
            // Read-only estimate with the currently held tool; unlike expectedBreakTicks this never selects inventory tools.
            float progress = state.getDestroyProgress(sp, level, pos);
            breakTicks = !Float.isFinite(progress) || progress <= 0 ? -1 : (int) Math.ceil(1d / progress);
            if (breakTicks > 12000) breakTicks = -1;
        }
        return new SiteAssessment.Observation(block, state.canBeReplaced(), state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES),
                !state.getFluidState().isEmpty(), asset, protectedBlock, breakTicks);
    }
    @Override public Map<String, Integer> inventory() {
        var inventory = TaskContext.serverPlayer(player).getInventory(); Map<String, Integer> result = new TreeMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot >= 36 && slot <= 39) continue;
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item)
                result.merge(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString(), stack.getCount(), Integer::sum);
        }
        return result;
    }
    @Override public List<JsonObject> knownAssets() { return knownAssets.stream().map(JsonObject::deepCopy).toList(); }
    @Override public String taskState(String taskId) {
        var info = TaskStatusTool.getTaskInfo(player.companionId(), taskId); return info == null ? null : info.state.name();
    }
    @Override public void dispatch(String taskId, ConstructionExecution.Batch batch) {
        var args = new JsonObject(); args.addProperty("mode", batch.mode()); args.addProperty("block_type", batch.block());
        var positions = new JsonArray();
        for (Point p : batch.positions()) { var coordinate = new JsonArray(); coordinate.add(p.x()); coordinate.add(p.y()); coordinate.add(p.z()); positions.add(coordinate); }
        args.add("positions", positions); var acknowledgment = new AtomicReference<String>();
        new BuildTool().onServerCall(taskId, args, player, acknowledgment::set);
        if (acknowledgment.get() == null) throw new IllegalStateException("build_not_acknowledged");
        var response = JsonParser.parseString(acknowledgment.get()).getAsJsonObject();
        if (!response.has("task_id") || !taskId.equals(response.get("task_id").getAsString())
                || !response.has("async") || !response.get("async").getAsBoolean()) throw new IllegalStateException("build_rejected");
    }
    private static long distance(Point a, Point b) {
        return Math.max(Math.max(Math.abs((long) a.x() - b.x()), Math.abs((long) a.y() - b.y())), Math.abs((long) a.z() - b.z()));
    }
}
