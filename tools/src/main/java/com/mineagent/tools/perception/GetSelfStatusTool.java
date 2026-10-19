package com.mineagent.tools.perception;

import com.mineagent.tools.i18n.ToolMessages;
import com.mineagent.tools.i18n.ToolMessages;
import com.mineagent.tools.i18n.ToolOutput;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.WorldAssetObserver;

import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Get the companion's current status — health, food, position, inventory,
 * equipment, and active effects.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class GetSelfStatusTool implements Tool {

    @Override
    public String name() { return "get_self_status"; }

    @Override
    public String description() {
        return ToolMessages.text("get_self_status.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var sp = ((CompanionEntity) player).serverPlayer();
        var pos = sp.blockPosition();
        var inv = sp.getInventory();
        var loop = TaskContext.agentLoop(player);
        if (loop != null) {
            loop.worldAssetIndex().observeInventory(WorldAssetObserver.position(sp),
                    WorldAssetObserver.inventory(sp), sp.level().getGameTime());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(ToolMessages.text("status.header"));
        sb.append(ToolOutput.formatted("status.position", pos.getX(), pos.getY(), pos.getZ()));
        sb.append(ToolOutput.formatted("status.health", sp.getHealth(), sp.getMaxHealth()));
        sb.append(ToolOutput.formatted("status.food",
                sp.getFoodData().getFoodLevel(), sp.getFoodData().getSaturationLevel()));
        sb.append(ToolOutput.formatted("status.air", sp.getAirSupply(), sp.experienceLevel));

        // Active potion effects
        var effects = sp.getActiveEffects();
        if (!effects.isEmpty()) {
            sb.append(ToolMessages.text("status.effects"));
            for (var effect : effects) {
                String effectName = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                        .getKey(effect.getEffect().value()).toString()
                        .replace("minecraft:", "");
                sb.append(effectName).append("(")
                  .append(effect.getDuration() / 20).append("s) ");
            }
            sb.append("\n");
        }

        // Equipment
        sb.append(ToolMessages.text("status.equipment"));
        var armorSlots = sp.getInventory().armor;
        String[] slotNames = {ToolMessages.text("status.boots"), ToolMessages.text("status.leggings"), ToolMessages.text("status.chestplate"), ToolMessages.text("status.helmet")};
        for (int i = 0; i < 4; i++) {
            var stack = armorSlots.get(i);
            if (!stack.isEmpty()) {
                String itemName = ToolOutput.itemLabel(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getHoverName().getString(), stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
                sb.append(slotNames[i]).append(": ").append(itemName)
                        .append(" [").append(itemId).append(']');
                // Check enchantments
                if (stack.isEnchanted()) {
                    sb.append(ToolMessages.text("status.enchanted"));
                }
                sb.append("\n");
            } else {
                sb.append(slotNames[i]).append(ToolMessages.text("status.empty_slot"));
            }
        }

        // Main hand and off hand
        var mainHand = sp.getMainHandItem();
        sb.append(ToolMessages.text("status.main_hand"));
        if (!mainHand.isEmpty()) {
            sb.append(ToolOutput.itemLabel(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString(),
                        mainHand.getHoverName().getString(), mainHand.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)));
            sb.append(" [").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(mainHand.getItem())).append(']');
            if (mainHand.isEnchanted()) sb.append(ToolMessages.text("status.enchanted"));
        } else {
            sb.append(ToolMessages.text("status.empty"));
        }
        sb.append("\n");

        var offHand = sp.getOffhandItem();
        sb.append(ToolMessages.text("status.off_hand"));
        if (!offHand.isEmpty()) {
            sb.append(ToolOutput.itemLabel(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(offHand.getItem()).toString(),
                        offHand.getHoverName().getString(), offHand.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)));
            sb.append(" [").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(offHand.getItem())).append(']');
        } else {
            sb.append(ToolMessages.text("status.empty"));
        }
        sb.append("\n");

        // Inventory — only the 36 main slots (9 hotbar + 27 inventory).
        // getContainerSize() is 41 (36 + 4 armor + 1 offhand); iterating
        // beyond 36 would double-report armor/offhand as fake "Inv 27-31".
        sb.append(ToolMessages.text("status.inventory"));
        int hotbarCount = 0;
        int invCount = 0;
        for (int i = 0; i < 36; i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String itemName = ToolOutput.itemLabel(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getHoverName().getString(), stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
                String slotType = (i < 9) ? ToolMessages.text("status.hotbar") : ToolMessages.text("status.inv");
                int slotNum = (i < 9) ? i : i - 9;
                sb.append(String.format(Locale.ROOT, "[%s %d] %s [%s] x%d\n",
                        slotType, slotNum, itemName, itemId, stack.getCount()));
                if (i < 9) hotbarCount++; else invCount++;
            }
        }
        sb.append(ToolOutput.formatted("status.slots",
                hotbarCount, invCount));

        reply.accept(sb.toString());
    }
}
