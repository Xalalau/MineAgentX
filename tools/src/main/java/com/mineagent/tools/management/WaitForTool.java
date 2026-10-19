package com.mineagent.tools.management;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Waits for a verifiable world condition instead of ending the LLM turn. */
public final class WaitForTool implements Tool {
    private static final Set<String> KINDS = Set.of(
            "duration", "inventory", "semantic", "menu_slot", "dimension", "block", "entity");

    @Override public String name() { return "wait_for"; }
    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return ToolMessages.text("wait_for.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("kind", ToolMessages.text("wait_for.parameter.kind"))
                .optionalInteger("duration_ticks", ToolMessages.text("wait_for.parameter.duration_ticks"), 1, 12000)
                .optionalString("item_id", ToolMessages.text("wait_for.parameter.item_id"))
                .optionalInteger("count", ToolMessages.text("wait_for.parameter.count"), 0, 4096)
                .optionalString("subject", ToolMessages.text("wait_for.parameter.subject"))
                .optionalString("predicate", ToolMessages.text("wait_for.parameter.predicate"))
                .optionalString("value", ToolMessages.text("wait_for.parameter.value"))
                .optionalString("comparison", ToolMessages.text("wait_for.parameter.comparison"))
                .optionalString("endpoint", ToolMessages.text("wait_for.parameter.endpoint"))
                .optionalInteger("slot", ToolMessages.text("wait_for.parameter.slot"), 0, 255)
                .optionalString("dimension", ToolMessages.text("wait_for.parameter.dimension"))
                .optionalInteger("x", ToolMessages.text("wait_for.parameter.x"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("y", ToolMessages.text("wait_for.parameter.y"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("z", ToolMessages.text("wait_for.parameter.z"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalString("block_id", ToolMessages.text("wait_for.parameter.block_id"))
                .optionalString("entity_type", ToolMessages.text("wait_for.parameter.entity_type"))
                .nullableNumber("radius", ToolMessages.text("wait_for.parameter.radius"))
                .optionalBoolean("present", ToolMessages.text("wait_for.parameter.present"))
                .optionalInteger("stable_ticks", ToolMessages.text("wait_for.parameter.stable_ticks"), 1, 100)
                .optionalInteger("max_wait_ticks", ToolMessages.text("wait_for.parameter.max_wait_ticks"), 20, 12000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String kind = ToolArgs.getString(args, "kind");
        if (!KINDS.contains(kind)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_wait_kind_choose_one_of_0", KINDS)));
            return;
        }
        int duration = integer(args, "duration_ticks", 0);
        int count = integer(args, "count", -1);
        int slot = integer(args, "slot", -1);
        int stable = integer(args, "stable_ticks", 1);
        int timeout = integer(args, "max_wait_ticks", 600);
        if ((ToolArgs.has(args, "stable_ticks")
                && ToolArgs.getIntOrNull(args, "stable_ticks") == null)
                || (ToolArgs.has(args, "max_wait_ticks")
                && ToolArgs.getIntOrNull(args, "max_wait_ticks") == null)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.stable_ticks_and_max_wait_ticks_must_be_integers_when_supplied")));
            return;
        }
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId != null) {
            ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_registered_item_0", itemId)));
                return;
            }
            itemId = id.toString();
        }
        if (duration < 0 || duration > 12000 || stable < 1 || stable > 100
                || timeout < 20 || timeout > 12000) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_duration_stability_timeout_bounds")));
            return;
        }
        if ("duration".equals(kind) && duration < 1) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.duration_ticks_is_required_for_kind_duration")));
            return;
        }
        if ("inventory".equals(kind) && (itemId == null || count < 0)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.inventory_waits_require_item_id_and_count")));
            return;
        }
        if ("semantic".equals(kind)
                && (blank(args, "subject") || blank(args, "predicate") || !ToolArgs.has(args, "value"))) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.semantic_waits_require_subject_predicate_and_value")));
            return;
        }
        if ("menu_slot".equals(kind) && (slot < 0 || count < 0)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.menu_slot_waits_require_slot_and_count")));
            return;
        }
        String dimension = ToolArgs.getString(args, "dimension");
        if ("dimension".equals(kind)) {
            ResourceLocation id = dimension == null ? null
                    : ResourceLocation.tryParse(dimension.trim());
            if (id == null) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.dimension_waits_require_a_valid_namespaced_dimension_id")));
                return;
            }
            // Dimensions are dynamic registry keys, so syntax is validated
            // here while existence is left to the bounded runtime condition.
            dimension = id.toString();
        }
        if ("block".equals(kind)
                && (integerMissing(args, "x") || integerMissing(args, "y")
                || integerMissing(args, "z") || blank(args, "block_id"))) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.block_waits_require_x_y_z_and_block_id")));
            return;
        }
        String blockId = ToolArgs.getString(args, "block_id");
        if (blockId != null) {
            ResourceLocation id = ResourceLocation.tryParse(blockId.trim());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_registered_block_0", blockId)));
                return;
            }
            blockId = id.toString();
        }
        if ("entity".equals(kind)
                && (integerMissing(args, "x") || integerMissing(args, "y")
                || integerMissing(args, "z"))) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.entity_waits_require_x_y_and_z")));
            return;
        }
        String entityType = ToolArgs.getString(args, "entity_type");
        if (entityType != null) {
            ResourceLocation id = ResourceLocation.tryParse(entityType.trim());
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                reply.accept(ToolArgs.errorJson(ToolMessages.text("error.unknown_registered_entity_type_0", entityType)));
                return;
            }
            entityType = id.toString();
        }
        String endpoint = ToolArgs.getString(args, "endpoint", "container");
        if (!List.of("container", "player").contains(endpoint)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.endpoint_must_be_container_or_player")));
            return;
        }
        String comparison = ToolArgs.getString(args, "comparison", "equals");
        if (!List.of("equals", "at_least", "at_most", "present").contains(comparison)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.invalid_semantic_comparison")));
            return;
        }
        String semanticValue = ToolArgs.getString(args, "value");
        if ("semantic".equals(kind)
                && ("at_least".equals(comparison) || "at_most".equals(comparison))
                && !finiteNumber(semanticValue)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.numeric_semantic_comparisons_require_a_finite_numeric_value")));
            return;
        }
        Double radius = ToolArgs.has(args, "radius")
                ? ToolArgs.getDoubleOrNull(args, "radius") : 8.0;
        if (radius == null || !Double.isFinite(radius) || radius <= 0.0 || radius > 128.0) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.radius_must_be_finite_and_between_0_and_128")));
            return;
        }
        Boolean requestedPresence = ToolArgs.has(args, "present")
                ? ToolArgs.getBoolOrNull(args, "present") : Boolean.TRUE;
        if (requestedPresence == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.present_must_be_a_boolean_when_supplied")));
            return;
        }
        boolean present = requestedPresence;
        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        WaitForTaskRecord record = new WaitForTaskRecord(toolCallId, kind, duration,
                itemId, count, ToolArgs.getString(args, "subject"),
                ToolArgs.getString(args, "predicate"), semanticValue,
                comparison, endpoint, slot, dimension,
                integer(args, "x", 0), integer(args, "y", 0), integer(args, "z", 0),
                blockId, entityType,
                radius, present, stable);
        record.extendDeadlineTo(sp.level().getGameTime() + timeout);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    private static boolean blank(JsonObject args, String key) {
        String value = ToolArgs.getString(args, key);
        return value == null || value.isBlank();
    }
    private static boolean integerMissing(JsonObject args, String key) {
        return !ToolArgs.has(args, key) || ToolArgs.getIntOrNull(args, key) == null;
    }
    private static int integer(JsonObject args, String key, int fallback) {
        Integer value = ToolArgs.has(args, key) ? ToolArgs.getIntOrNull(args, key) : null;
        return value == null ? fallback : value;
    }
    private static boolean finiteNumber(String value) {
        try { return Double.isFinite(Double.parseDouble(value)); }
        catch (NumberFormatException | NullPointerException invalid) { return false; }
    }

    public static final class WaitForTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String kind;
        public final int durationTicks;
        public final String itemId;
        public final int count;
        public final String subject;
        public final String predicate;
        public final String value;
        public final String comparison;
        public final String endpoint;
        public final int slot;
        public final String dimension;
        public final int x, y, z;
        public final String blockId;
        public final String entityType;
        public final double radius;
        public final boolean present;
        public final int stableTicks;

        public WaitForTaskRecord(String toolCallId, String kind, int durationTicks,
                                 String itemId, int count, String subject, String predicate,
                                 String value, String comparison, String endpoint, int slot,
                                 String dimension, int x, int y, int z, String blockId,
                                 String entityType, double radius, boolean present,
                                 int stableTicks) {
            super(toolCallId);
            this.kind = kind; this.durationTicks = durationTicks; this.itemId = itemId;
            this.count = count; this.subject = subject; this.predicate = predicate;
            this.value = value; this.comparison = comparison; this.endpoint = endpoint;
            this.slot = slot; this.dimension = dimension; this.x = x; this.y = y; this.z = z;
            this.blockId = blockId; this.entityType = entityType; this.radius = radius;
            this.present = present; this.stableTicks = stableTicks;
        }
    }
}
