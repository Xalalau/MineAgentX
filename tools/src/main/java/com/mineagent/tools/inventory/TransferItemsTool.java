package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import static com.mineagent.tools.inventory.InventoryMessages.t;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.WorldAssetObserver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Moves real item stacks between player and open-container slots. */
public class TransferItemsTool implements Tool {

    @Override
    public String name() { return "transfer_items"; }

    @Override
    public String description() {
        return t("description.transfer");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("from_slot", t("Source slot index"), 0, 255)
                .integer("to_slot", t("Destination slot index"), 0, 255)
                .optionalInteger("count", t("Items to move; default is the full source stack"), 1, 64)
                .optionalString("source", t("Source: player or container (default player)"))
                .optionalString("destination", t("Destination: player or container (default player)"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        Integer fromIndex = ToolArgs.getIntOrNull(args, "from_slot");
        Integer toIndex = ToolArgs.getIntOrNull(args, "to_slot");
        if (fromIndex == null) {
            reply.accept(ToolArgs.errorJson(t("from_slot must be an integer")));
            return;
        }
        if (toIndex == null) {
            reply.accept(ToolArgs.errorJson(t("to_slot must be an integer")));
            return;
        }

        Integer requestedCount = ToolArgs.getIntOrNull(args, "count");
        if (ToolArgs.has(args, "count")
                && (requestedCount == null || requestedCount < 1 || requestedCount > 64)) {
            reply.accept(ToolArgs.errorJson(t("count must be an integer from 1 to 64")));
            return;
        }

        String sourceType = normalizedEndpoint(args, "source", "player");
        String destinationType = normalizedEndpoint(args, "destination", "player");
        if (!validEndpoint(sourceType) || !validEndpoint(destinationType)) {
            reply.accept(ToolArgs.errorJson(t("source and destination must be 'player' or 'container'")));
            return;
        }

        ServerPlayer sp = ((CompanionEntity) player).serverPlayer();
        AbstractContainerMenu openMenu = sp.containerMenu;
        if (("container".equals(sourceType) || "container".equals(destinationType))
                && (openMenu == null || openMenu == sp.inventoryMenu)) {
            reply.accept(ToolArgs.errorJson(t("No container GUI is currently open")));
            return;
        }

        Slot source = resolveSlot(sp, sourceType, fromIndex);
        Slot destination = resolveSlot(sp, destinationType, toIndex);
        if (source == null) {
            reply.accept(ToolArgs.errorJson(t("Source slot ") + fromIndex + t(" is out of range")));
            return;
        }
        if (destination == null) {
            reply.accept(ToolArgs.errorJson(t("Destination slot ") + toIndex + t(" is out of range")));
            return;
        }
        ItemStack sourceBeforeStack = source.getItem().copy();
        String movedItemId = BuiltInRegistries.ITEM
                .getKey(sourceBeforeStack.getItem()).toString();
        int requested = requestedCount == null ? sourceBeforeStack.getCount()
                : requestedCount;
        var outcome = InventorySlotTransactions.move(source, destination, requested, sp);
        int moved = outcome.moved();
        if (moved == 0) {
            JsonObject refused = outcome.result(requested);
            refused.addProperty("error", t("No items moved; slot rules or capacity refused the operation."));
            reply.accept(refused.toString());
            return;
        }
        String action = outcome.swap() ? "swap" : "transfer";

        TaskContext.syncInventory(sp);
        if (openMenu != null) openMenu.broadcastChanges();
        var loop = TaskContext.agentLoop(player);
        if (loop != null && openMenu != null && openMenu != sp.inventoryMenu) {
            // Update the exact post-transfer menu contents. This prevents the
            // persistent planner from believing that retrieved equipment is
            // still in the container on the next decision.
            WorldAssetObserver.observeOpenMenu(loop.worldAssetIndex(), sp, openMenu);
        }

        JsonObject result = outcome.result(requested);
        result.addProperty("action", action);
        result.addProperty("item", movedItemId);
        result.addProperty("source", sourceType);
        result.addProperty("destination", destinationType);
        result.addProperty("from_slot", fromIndex);
        result.addProperty("to_slot", toIndex);
        result.addProperty("source_count_after", source.getItem().getCount());
        result.addProperty("destination_count_after", destination.getItem().getCount());
        reply.accept(result.toString());
    }

    private static String normalizedEndpoint(JsonObject args, String key, String fallback) {
        String value = ToolArgs.getString(args, key, fallback);
        return value == null ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validEndpoint(String value) {
        return "player".equals(value) || "container".equals(value);
    }

    static Slot resolveSlot(ServerPlayer sp, String endpoint, int index) {
        if (index < 0) return null;
        if ("container".equals(endpoint)) {
            AbstractContainerMenu menu = sp.containerMenu;
            return menu != null && index < menu.slots.size() ? menu.getSlot(index) : null;
        }

        Inventory inventory = sp.getInventory();
        if (index >= inventory.getContainerSize()) return null;
        AbstractContainerMenu menu = sp.containerMenu == null
                ? sp.inventoryMenu : sp.containerMenu;
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == index) return slot;
        }
        // The inventory menu always maps all 41 player slots, but use it as a
        // defensive fallback for modded menus that omit an offhand/armor slot.
        for (Slot slot : sp.inventoryMenu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == index) return slot;
        }
        return null;
    }

}
