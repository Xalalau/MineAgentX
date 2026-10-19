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
 * Find the nearest structure of a given type. Uses the server's
 * structure locator to find the closest matching structure.
 *
 * <p>This is an <b>async</b> tool — it dispatches a LocateStructureTaskRecord
 * and returns a task_id immediately.
 */
public class LocateStructureTool implements Tool {

    @Override
    public String name() { return "locate_structure"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return ToolMessages.text("locate_structure.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("structure_type", ToolMessages.text("locate_structure.parameter.structure_type"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String structureType = ToolArgs.getString(args, "structure_type");
        if (structureType == null || structureType.isBlank()) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_structure_type")));
            return;
        }
        structureType = structureType.trim();

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var registryAccess = level.registryAccess();

        // Resolve the structure type
        boolean tagRequest = structureType.startsWith("#");
        var structureResourceLoc = net.minecraft.resources.ResourceLocation.tryParse(
                tagRequest ? structureType.substring(1) : structureType);
        if (structureResourceLoc == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_structure_type_0", structureType)));
            return;
        }

        var structureRegistry = registryAccess.registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        boolean exists = tagRequest
                ? structureRegistry.getTag(net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.STRUCTURE,
                        structureResourceLoc)).filter(tag -> tag.size() > 0).isPresent()
                : structureRegistry.getHolder(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.STRUCTURE,
                        structureResourceLoc)).isPresent();
        if (!exists) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.structure_type_0_not_found_in_this_world", structureType)));
            return;
        }

        var record = new LocateStructureTaskRecord(toolCallId, structureType);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for structure location. */
    public static class LocateStructureTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String structureType;

        public LocateStructureTaskRecord(String toolCallId, String structureType) {
            super(toolCallId);
            this.structureType = structureType;
        }
    }
}
