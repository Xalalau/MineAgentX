package com.mineagent.engine.i18n;

import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.entity.CompanionLifecycleHandler;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Records command dispatch without constructing a world, invoking HTTP or admitting a physical action. */
final class CommandPlayerFixture implements AutoCloseable {
    final RecordingPlayer player;
    final CommandModelsLanguageTest.Capture output = new CommandModelsLanguageTest.Capture();
    final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    final CommandSourceStack stack;
    final List<UUID> added = new ArrayList<>();
    final Object previousConfig;
    final Object previousConfigDir;

    CommandPlayerFixture() throws Exception {
        previousConfig = field("config").get(null);
        previousConfigDir = field("configDirPath").get(null);
        field("configDirPath").set(null, null);
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        player = allocate(RecordingPlayer.class);
        player.messages = new ArrayList<>();
        player.fixtureServer = allocate(DedicatedServer.class);
        stack = new CommandSourceStack(output, Vec3.ZERO, Vec2.ZERO, null, 0,
                "fixture", Component.literal("fixture"), null, player);
        MineAgentEngine.registerCommands(dispatcher);
    }

    MineAgentEngine.CompanionState companion(String name) throws Exception {
        CompanionEntity companion = new CompanionEntity(player, player, name);
        var lifecycle = new CompanionLifecycleHandler(companion, null, List.of());
        var state = new MineAgentEngine.CompanionState(companion, null, null, lifecycle);
        map("COMPANIONS").put(companion.companionId(), state);
        map("OWNER_TO_COMPANION").put(player.getUUID(), companion.companionId());
        map("OWNER_TO_ALL_COMPANIONS").put(player.getUUID(), new ArrayList<>(List.of(companion.companionId())));
        added.add(companion.companionId());
        return state;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> map(String name) throws Exception { return (Map<Object, Object>) field(name).get(null); }
    static java.lang.reflect.Field field(String name) throws Exception {
        var field = MineAgentEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }
    @Override public void close() throws Exception {
        for (UUID id : added) map("COMPANIONS").remove(id);
        map("OWNER_TO_COMPANION").remove(player.getUUID());
        map("OWNER_TO_ALL_COMPANIONS").remove(player.getUUID());
        field("config").set(null, previousConfig);
        field("configDirPath").set(null, previousConfigDir);
    }
    static final class RecordingPlayer extends ServerPlayer {
        List<String> messages;
        MinecraftServer fixtureServer;
        private RecordingPlayer() { super(null, null, null, null); }
        @Override public UUID getUUID() { return new UUID(0, 0x434d444c); }
        @Override public Component getName() { return Component.literal("Owner {0} $1 \\ ç"); }
        @Override public MinecraftServer getServer() { return fixtureServer; }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }
}
