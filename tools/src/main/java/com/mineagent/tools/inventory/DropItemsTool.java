package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import static com.mineagent.tools.inventory.InventoryMessages.t;
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
        return t("description.drop");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", t("Item ID to drop (e.g. 'minecraft:cobblestone')"))
                .integer("count", t("Number of items to drop (1-64)"), 1, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId == null) {
            reply.accept(ToolArgs.errorJson(t("Missing required parameter 'item_id'.")));
            return;
        }
        if (!ToolArgs.has(args, "count")) {
            reply.accept(ToolArgs.errorJson(t("Missing required parameter 'count'.")));
            return;
        }
        var parsedItemId = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (parsedItemId == null
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(parsedItemId)) {
            reply.accept(ToolArgs.errorJson(t("Unknown item: ") + itemId));
            return;
        }
        itemId = parsedItemId.toString();
        Integer count = ToolArgs.getIntOrNull(args, "count");
        if (count == null || count < 1 || count > MAX_DROP_COUNT) {
            reply.accept(ToolArgs.errorJson(t("'count' must be an integer from 1 to ") + MAX_DROP_COUNT + "."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var inv = sp.getInventory();
        int carriedBefore = countItem(inv, itemId);

        // Find and count matching items
        int remaining = count;
        int dropped = 0;
        boolean deliveryRefused = false;
        boolean[] allDeliveriesVerified = {true};

        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!id.equals(itemId)) continue;

            int toDrop = Math.min(remaining, stack.getCount());
            var source = TransferItemsTool.resolveSlot(sp, "player", i);
            if (source == null || !stack.onDroppedByPlayer(sp)) continue;
            int accepted = InventorySlotTransactions.drop(source, toDrop, sp, attempt -> {
                var expected = attempt.copy();
                // Two-argument Player#drop is NeoForge's normal toss-event path.
                // ServerPlayer#drop(three arguments) alone bypasses ItemTossEvent
                // and returns a non-null entity even if addFreshEntity is vetoed.
                var entity = sp.drop(attempt, true);
                boolean spawned = entity != null && !entity.isRemoved()
                        && sp.serverLevel().getEntity(entity.getUUID()) == entity;
                if (spawned && !net.minecraft.world.item.ItemStack.matches(expected, entity.getItem())) {
                    allDeliveriesVerified[0] = false;
                }
                return spawned;
            });
            if (accepted == 0) {
                deliveryRefused = true;
                break;
            }
            remaining -= accepted;
            dropped += accepted;
        }

        com.mineagent.engine.task.TaskContext.syncInventory(sp);
        JsonObject result = InventorySlotTransactions.dropResult(itemId, count, dropped,
                carriedBefore, countItem(inv, itemId), allDeliveriesVerified[0], deliveryRefused);
        reply.accept(result.toString());
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
