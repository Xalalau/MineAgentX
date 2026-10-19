package com.mineagent.engine.i18n;

import com.mineagent.api.entity.CompanionGameMode;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.entity.CompanionLifecycleHandler;
import com.mineagent.engine.loop.AgentLoop;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.mineagent.engine.i18n.CommandModelsLanguageTest.fixture;
import static com.mineagent.api.i18n.Language.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandLifecycleLanguageTest {
    @Test void spawnAndDespawnNotifyWithoutTranslatingNames() throws Exception {
        for (var language : values()) fixture(language, (dispatcher, output, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                body.player.connection = CommandPlayerFixture.allocate(ServerGamePacketListenerImpl.class);
                var companion = new CompanionEntity(body.player, body.player, "Name {0} $1 \\ ç");
                var loop = loop();
                var lifecycle = new CompanionLifecycleHandler(companion, loop, List.of());
                lifecycle.onSpawn(companion);
                assertEquals(List.of(language == PT_BR ? "§a[MineAgent] Name {0} $1 \\ ç foi criado!" : "§a[MineAgent] Name {0} $1 \\ ç has spawned!"), body.player.messages);
                assertEquals(List.of("companion_spawned"), loop.events);
                body.player.messages.clear();
                lifecycle.onDespawn(companion);
                assertEquals(List.of(language == PT_BR ? "§7[MineAgent] Name {0} $1 \\ ç foi removido." : "§7[MineAgent] Name {0} $1 \\ ç has been removed."), body.player.messages);
                assertTrue(lifecycle.isDead());
                assertTrue(lifecycle.isPaused());
                assertEquals("shutdown", loop.events.getLast());
            }
        });
    }

    @Test void deathNarrativeAndNotificationKeepIdempotence() throws Exception {
        for (var language : values()) fixture(language, (dispatcher, output, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                body.player.connection = CommandPlayerFixture.allocate(ServerGamePacketListenerImpl.class);
                var companion = new CompanionEntity(body.player, body.player, "fixture");
                var loop = loop();
                var lifecycle = new CompanionLifecycleHandler(companion, loop, List.of());
                try {
                    lifecycle.onDeath(companion);
                    lifecycle.onDeath(companion);
                    assertEquals(List.of(language == PT_BR ? "§c[MineAgent] fixture morreu! Use /mineagent respawn para revivê-lo." : "§c[MineAgent] fixture has died! Use /mineagent respawn to bring them back."), body.player.messages);
                    assertEquals(List.of("pause", language == PT_BR ? "Eu morri! Minha vida chegou a zero. Preciso ser revivido." : "I died! My health reached zero. I need to be respawned."), loop.events);
                } finally { com.mineagent.engine.task.TaskContext.removeCaches(companion); }
            }
        });
    }

    @Test void hardcoreRespawnRefusalDoesNotResumeLoop() throws Exception {
        for (var language : values()) fixture(language, (dispatcher, output, stack) -> {
            try (var body = new CommandPlayerFixture()) {
                body.player.connection = CommandPlayerFixture.allocate(ServerGamePacketListenerImpl.class);
                var companion = new CompanionEntity(body.player, body.player, "fixture", CompanionGameMode.HARDCORE);
                var loop = loop();
                var lifecycle = new CompanionLifecycleHandler(companion, loop, List.of());
                lifecycle.onRespawn(companion);
                assertEquals(List.of(language == PT_BR ? "§c[MineAgent] Um companheiro que morre no modo Hardcore não pode reviver nem mudar de modo. Crie um novo companheiro." : "§c[MineAgent] A companion that dies in Hardcore mode cannot respawn or change mode. Create a new companion."), body.player.messages);
                assertTrue(loop.events.isEmpty());
            }
        });
    }

    private static RecordingLoop loop() throws Exception {
        var loop = CommandPlayerFixture.allocate(RecordingLoop.class);
        loop.events = new ArrayList<>();
        return loop;
    }
    static final class RecordingLoop extends AgentLoop {
        List<String> events;
        private RecordingLoop() { super(null, "", "", "", "", 0, 1, "auto"); }
        @Override public synchronized void wake(String reason) { events.add(reason); }
        @Override public void onBodyLog(String narrative) { events.add(narrative); }
        @Override public synchronized void pause() { events.add("pause"); }
        @Override public void shutdown() { events.add("shutdown"); }
    }
}
