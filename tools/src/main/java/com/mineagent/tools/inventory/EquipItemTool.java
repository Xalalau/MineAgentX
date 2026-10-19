package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import static com.mineagent.tools.inventory.InventoryMessages.t;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Equips an existing stack by performing one real inventory swap. */
public class EquipItemTool implements Tool {

    @Override
    public String name() { return "equip_item"; }

    @Override
    public String description() {
        return t("description.equip");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", t("Registered item ID to equip"))
                .string("slot", t("head, chest, legs, feet, offhand, or hotbar_1-9"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String requestedItem = ToolArgs.getString(args, "item_id");
        ResourceLocation itemId = requestedItem == null
                ? null : ResourceLocation.tryParse(requestedItem.trim());
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            reply.accept(ToolArgs.errorJson(t("Unknown item: ") + requestedItem));
            return;
        }

        String slotName = ToolArgs.getString(args, "slot");
        if (slotName == null || slotName.isBlank()) {
            reply.accept(ToolArgs.errorJson(t("Missing required parameter 'slot'")));
            return;
        }
        slotName = slotName.trim().toLowerCase(Locale.ROOT);

        int targetSlot = inventorySlot(slotName);
        if (targetSlot < 0) {
            reply.accept(ToolArgs.errorJson(t("Unknown slot '") + slotName
                    + t("'; use head/chest/legs/feet/offhand/hotbar_1-9")));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var inventory = sp.getInventory();
        int sourceSlot = !inventory.getItem(targetSlot).isEmpty()
                && BuiltInRegistries.ITEM.getKey(inventory.getItem(targetSlot).getItem()).equals(itemId)
                ? targetSlot : -1;
        for (int i = 0; sourceSlot < 0 && i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
                sourceSlot = i;
                break;
            }
        }
        if (sourceSlot < 0) {
            reply.accept(ToolArgs.errorJson(t("Item '") + itemId + t("' not found in inventory")));
            return;
        }

        var sourceStack = inventory.getItem(sourceSlot).copy();
        boolean moveVerified = true;
        if (sourceSlot != targetSlot) {
            var source = TransferItemsTool.resolveSlot(sp, "player", sourceSlot);
            var target = TransferItemsTool.resolveSlot(sp, "player", targetSlot);
            if (source == null || target == null) {
                reply.accept(ToolArgs.errorJson(t("Equipment slots are unavailable.")));
                return;
            }
            var outcome = InventorySlotTransactions.move(source, target, sourceStack.getCount(), sp);
            moveVerified = outcome.verified() && outcome.moved() > 0;
            if (!moveVerified) {
                reply.accept(ToolArgs.errorJson(t("Equipment move was refused or could not be verified.")));
                return;
            }
        }
        if (targetSlot < 9) inventory.selected = targetSlot;
        TaskContext.syncInventory(sp);

        var equipped = inventory.getItem(targetSlot);
        boolean selected = targetSlot >= 9 || inventory.selected == targetSlot;
        // This is a server-side read after the swap, not an assertion based on
        // the requested arguments. AgentLoop uses it as the transaction's
        // synchronous postcondition.
        boolean verified = moveVerified && !equipped.isEmpty()
                && BuiltInRegistries.ITEM.getKey(equipped.getItem()).equals(itemId)
                && selected;

        JsonObject result = new JsonObject();
        result.addProperty("success", verified);
        result.addProperty("item", itemId.toString());
        result.addProperty("slot", slotName);
        result.addProperty("postcondition_verified", verified);
        reply.accept(result.toString());
    }

    private static int inventorySlot(String slot) {
        return switch (slot) {
            case "head" -> 39;
            case "chest" -> 38;
            case "legs" -> 37;
            case "feet" -> 36;
            case "offhand" -> 40;
            case "hotbar_1" -> 0;
            case "hotbar_2" -> 1;
            case "hotbar_3" -> 2;
            case "hotbar_4" -> 3;
            case "hotbar_5" -> 4;
            case "hotbar_6" -> 5;
            case "hotbar_7" -> 6;
            case "hotbar_8" -> 7;
            case "hotbar_9" -> 8;
            default -> -1;
        };
    }

}
