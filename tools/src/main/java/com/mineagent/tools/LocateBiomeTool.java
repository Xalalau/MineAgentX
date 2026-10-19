package com.mineagent.tools;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Find the nearest biome of a given type. Uses the server's biome
 * locator to find the closest matching biome.
 *
 * <p>This is an <b>async</b> tool — it dispatches a LocateBiomeTaskRecord
 * and returns a task_id immediately.
 */
public class LocateBiomeTool implements Tool {

    @Override
    public String name() { return "locate_biome"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return ToolMessages.text("locate_biome.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("biome_type", ToolMessages.text("locate_biome.parameter.biome_type"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String biomeType = ToolArgs.getString(args, "biome_type");
        if (biomeType == null || biomeType.isBlank()) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_biome_type")));
            return;
        }
        biomeType = biomeType.trim();

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var registryAccess = level.registryAccess();

        // Resolve the biome type
        var biomeResourceLoc = net.minecraft.resources.ResourceLocation.tryParse(biomeType);
        if (biomeResourceLoc == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_biome_type_0", biomeType)));
            return;
        }

        var biomeRegistry = registryAccess.registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var biomeKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.BIOME, biomeResourceLoc);

        if (!biomeRegistry.containsKey(biomeKey)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.biome_type_0_not_found_in_this_world", biomeType)));
            return;
        }

        var record = new LocateBiomeTaskRecord(toolCallId, biomeType);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for biome location. */
    public static class LocateBiomeTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String biomeType;

        public LocateBiomeTaskRecord(String toolCallId, String biomeType) {
            super(toolCallId);
            this.biomeType = biomeType;
        }
    }
}
