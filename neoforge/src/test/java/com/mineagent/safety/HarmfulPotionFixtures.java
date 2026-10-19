package com.mineagent.safety;

import com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import sun.misc.Unsafe;

/** Headless boundary fixtures, not a running server or gameplay simulation. */
final class HarmfulPotionFixtures {
    private HarmfulPotionFixtures() {}

    static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    static PlayerBody player(ItemStack held) throws Exception {
        PlayerBody player = allocate(PlayerBody.class);
        player.held = held;
        player.id = UUID.randomUUID();
        player.gameMode = allocate(RecordingGameMode.class);
        return player;
    }

    static final class PlayerBody extends ServerPlayer {
        ItemStack held;
        UUID id;
        @Override public net.minecraft.world.item.ItemCooldowns getCooldowns() { return new net.minecraft.world.item.ItemCooldowns(); }
        PlayerBody() { super(null, null, new GameProfile(UUID.randomUUID(), "fixture"), ClientInformation.createDefault()); }
        @Override public ItemStack getItemInHand(InteractionHand hand) { return held; }
        @Override public ServerLevel serverLevel() { return null; }
        @Override public UUID getUUID() { return id; }
        @Override public BlockPos blockPosition() { return BlockPos.ZERO; }
        @Override public void swing(InteractionHand hand) {}
    }

    static final class RecordingGameMode extends FakePlayerGameMode {
        int uses;
        RecordingGameMode() { super(null); }
        @Override public InteractionResult useItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand) {
            uses++;
            return InteractionResult.CONSUME;
        }
    }
}
