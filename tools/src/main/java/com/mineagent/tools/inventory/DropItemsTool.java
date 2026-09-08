package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Drop items from inventory. The companion will drop the specified
 * items onto the ground at its current position.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class DropItemsTool implements Tool {

    private static final int MAX_DROP_COUNT = 64;

    @Override
    public String name() { return "drop_items"; }

    @Override
    public String description() {
        return """
            Drop items from inventory onto the ground. Specify the item ID
            and count. The items are dropped at the companion's current position.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Item ID to drop (e.g. 'minecraft:cobblestone')")
                .integer("count", "Number of items to drop (1-64)", 1, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId == null) {
            reply.accept("{\"error\":\"Missing required parameter 'item_id'.\"}");
            return;
        }
        if (!ToolArgs.has(args, "count")) {
            reply.accept("{\"error\":\"Missing required parameter 'count'.\"}");
            return;
        }
        var parsedItemId = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (parsedItemId == null
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(parsedItemId)) {
            reply.accept(ToolArgs.errorJson("Unknown item: " + itemId));
            return;
        }
        itemId = parsedItemId.toString();
        Integer count = ToolArgs.getIntOrNull(args, "count");
        if (count == null || count < 1 || count > MAX_DROP_COUNT) {
            reply.accept(ToolArgs.errorJson("'count' must be an integer from 1 to " + MAX_DROP_COUNT + "."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var inv = sp.getInventory();
        int carriedBefore = countItem(inv, itemId);

        // Find and count matching items
        int remaining = count;
        int dropped = 0;

        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!id.equals(itemId)) continue;

            int toDrop = Math.min(remaining, stack.getCount());
            var dropStack = stack.split(toDrop);
            var droppedEntity = sp.drop(dropStack, false, true);
            if (droppedEntity == null) {
                // Player#drop is normally guaranteed on the logical server,
                // but restoring the split stack keeps item conservation if a
                // platform hook vetoes entity creation.
                stack.grow(toDrop);
                break;
            }
            remaining -= toDrop;
            dropped += toDrop;

            if (stack.isEmpty()) {
                inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }

        if (dropped == 0) {
            reply.accept(ToolArgs.errorJson("Item '" + itemId + "' not found in inventory."));
        } else {
            // Publish both the normal inventory menu and any currently open
            // container menu. Updating only containerMenu leaves the owner's
            // hotbar stale when a platform keeps a separate inventory menu,
            // which then makes the next planning snapshot reason from items
            // that were already dropped.
            com.mineagent.engine.task.TaskContext.syncInventory(sp);
            JsonObject result = new JsonObject();
            int carriedAfter = countItem(inv, itemId);
            result.addProperty("success", true);
            result.addProperty("item", itemId);
            result.addProperty("dropped", dropped);
            result.addProperty("requested", count);
            result.addProperty("carried_before", carriedBefore);
            result.addProperty("carried_after", carriedAfter);
            // Re-count after the ItemEntity was actually created. A callback
            // alone is not enough because platform hooks can veto Player#drop.
            result.addProperty("postcondition_verified",
                    carriedAfter == carriedBefore - dropped);
            reply.accept(result.toString());
        }
    }

    private static int countItem(net.minecraft.world.entity.player.Inventory inventory,
                                 String itemId) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
