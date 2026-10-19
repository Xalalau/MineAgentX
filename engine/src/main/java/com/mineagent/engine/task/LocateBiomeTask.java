package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.i18n.ExecutorMessages;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.tools.LocateBiomeTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

/** Performs one bounded biome-source search without loading terrain chunks. */
public final class LocateBiomeTask
        extends CompanionTask<LocateBiomeTool.LocateBiomeTaskRecord> {

    private static final int SEARCH_RADIUS_BLOCKS = 6_400;
    private static final int SAMPLE_INTERVAL = 32;
    private boolean searched;
    private BlockPos foundPos;
    private String failReason;

    public LocateBiomeTask(AgentPlayer player,
                           LocateBiomeTool.LocateBiomeTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        searched = false;
        foundPos = null;
        failReason = null;
    }

    @Override
    protected TaskState onTick() {
        var sp = TaskContext.serverPlayer(player);
        if (record.deadline() > 0L && sp.level().getGameTime() >= record.deadline()) return TaskState.FAILED;
        if (searched) return foundPos != null ? TaskState.SUCCESS : TaskState.FAILED;
        searched = true;

        ResourceLocation id = ResourceLocation.tryParse(record.biomeType);
        if (id == null) {
            failReason = ExecutorMessages.text("biome.invalid", String.valueOf(record.biomeType));
            return TaskState.FAILED;
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
        var level = sp.serverLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        if (!registry.containsKey(key)) {
            failReason = ExecutorMessages.text("biome.unregistered", String.valueOf(record.biomeType));
            return TaskState.FAILED;
        }

        BlockPos origin = sp.blockPosition();
        var sampler = level.getChunkSource().randomState().sampler();
        // The previous implementation repeated this synchronous search every
        // tick and eventually passed 409,600 blocks while describing it as
        // 800 chunks. One 6,400-block query matches vanilla's useful bound;
        // a deterministic local RandomSource avoids perturbing world RNG.
        RandomSource random = RandomSource.create(
                origin.asLong() ^ sp.getUUID().getLeastSignificantBits());
        var match = level.getChunkSource().getGenerator().getBiomeSource()
                .findBiomeHorizontal(origin.getX(), origin.getY(), origin.getZ(),
                        SEARCH_RADIUS_BLOCKS, SAMPLE_INTERVAL,
                        holder -> holder.is(key), random, true, sampler);
        if (match == null) {
            failReason = ExecutorMessages.text("biome.missing", String.valueOf(record.biomeType), SEARCH_RADIUS_BLOCKS);
            return TaskState.FAILED;
        }
        foundPos = match.getFirst();
        return TaskState.SUCCESS;
    }

    @Override
    protected void onInterrupt() {
        // The biome-source lookup completes inside one bounded call.
    }

    @Override
    public TaskSnapshot snapshot() {
        return TaskSnapshot.progress(searched ? "complete" : "searching",
                ExecutorMessages.text("biome.snapshot", String.valueOf(record.biomeType)),
                searched ? 1 : 0, 1,
                foundPos == null ? null : foundPos.getX(),
                foundPos == null ? null : foundPos.getY(),
                foundPos == null ? null : foundPos.getZ(),
                searched && foundPos == null ? failReason : null,
                foundPos == null ? null : "biome_location=" + foundPos,
                searched ? 1L : 0L);
    }

    @Override
    protected String cancelledMessage() { return ExecutorMessages.text("task.cancelled"); }

    @Override
    protected String successMessage() {
        if (foundPos == null) return ExecutorMessages.text("biome.found");
        BlockPos from = TaskContext.serverPlayer(player).blockPosition();
        double distance = Math.hypot(from.getX() - foundPos.getX(),
                from.getZ() - foundPos.getZ());
        return ExecutorMessages.text("biome.success", foundPos.getX(), foundPos.getY(),
                foundPos.getZ(), String.format(java.util.Locale.ROOT, "%.1f", distance));
    }

    @Override
    protected String timeoutMessage() {
        return ExecutorMessages.text("biome.timeout", String.valueOf(record.biomeType));
    }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason
                : ExecutorMessages.text("biome.failure", String.valueOf(record.biomeType));
    }
}
