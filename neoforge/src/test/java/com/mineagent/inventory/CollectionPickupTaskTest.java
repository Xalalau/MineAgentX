package com.mineagent.inventory;

import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.task.CollectItemsTask;
import com.mineagent.tools.inventory.CollectItemsTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectionPickupTaskTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    private static CollectItemsTask taskWithDelivered(int count) throws Exception {
        var task = new CollectItemsTask(null,
                new CollectItemsTool.CollectItemsTaskRecord("literal-id", "minecraft:stone", 8, 5));
        var field = CollectItemsTask.class.getDeclaredField("collectedCount");
        field.setAccessible(true);
        field.setInt(task, count);
        return task;
    }

    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // Load the real NeoForge fluid registrations needed by Entity's constructor,
        // not a substitute Level, inventory, or entity implementation.
        var key = net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.FLUID_TYPES;
        if (!net.minecraft.core.registries.BuiltInRegistries.REGISTRY.containsKey(key.location())) {
            var constructor = net.neoforged.neoforge.registries.NewRegistryEvent.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            var event = constructor.newInstance();
            event.register(net.neoforged.neoforge.registries.NeoForgeRegistries.FLUID_TYPES);
            var fill = event.getClass().getDeclaredMethod("fill");
            fill.setAccessible(true);
            fill.invoke(event);
        }
        if (!net.neoforged.neoforge.registries.NeoForgeRegistries.FLUID_TYPES.containsKey(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("empty"))) {
            var field = net.neoforged.neoforge.common.NeoForgeMod.class.getDeclaredField("VANILLA_FLUID_TYPES");
            field.setAccessible(true);
            var deferred = field.get(null);
            var constructor = net.neoforged.neoforge.registries.RegisterEvent.class.getDeclaredConstructor(
                    net.minecraft.resources.ResourceKey.class, net.minecraft.core.Registry.class);
            constructor.setAccessible(true);
            var event = constructor.newInstance(key, net.neoforged.neoforge.registries.NeoForgeRegistries.FLUID_TYPES);
            var add = net.neoforged.neoforge.registries.DeferredRegister.class.getDeclaredMethod(
                    "addEntries", net.neoforged.neoforge.registries.RegisterEvent.class);
            add.setAccessible(true);
            add.invoke(deferred, event);
        }
    }

    @Test void oversizedTargetIsRefusedWithoutChangingItsStackOrMetadata() throws Exception {
        var target = new net.minecraft.world.entity.item.ItemEntity(
                net.minecraft.world.entity.EntityType.ITEM, null);
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("literal {0} \\ $ name"));
        target.setItem(stack);
        var owner = java.util.UUID.randomUUID();
        target.setTarget(owner);
        var thrower = new net.minecraft.world.entity.item.ItemEntity(
                net.minecraft.world.entity.EntityType.ITEM, null);
        target.setThrower(thrower);
        target.setPickUpDelay(37);
        target.setExtendedLifetime();
        target.lifespan = 12345;
        var before = target.getItem().copy();
        int beforeAge = target.getAge();
        var task = taskWithDelivered(0);
        var field = CollectItemsTask.class.getDeclaredField("currentItem");
        field.setAccessible(true);
        field.set(task, target);
        var touch = CollectItemsTask.class.getDeclaredMethod("touchCurrentItemWithinLimit");
        touch.setAccessible(true);
        assertDoesNotThrow(() -> touch.invoke(task));
        assertTrue(net.minecraft.world.item.ItemStack.matches(before, target.getItem()),
                "A refused bounded pickup must leave the original stack intact");
        assertEquals(owner, target.getTarget());
        assertSame(thrower, target.getOwner());
        assertSame(stack, target.getItem());
        assertEquals(12345, target.lifespan);
        var delay = net.minecraft.world.entity.item.ItemEntity.class.getDeclaredField("pickupDelay");
        delay.setAccessible(true);
        assertEquals(37, delay.getInt(target));
        assertEquals(beforeAge, target.getAge());
        assertTrue(task.completionMessage(TaskState.FAILED, false).contains("Partial pickup refused: target stack 64"));
        RuntimeLanguage.configure("pt_br");
        String refused = task.completionMessage(TaskState.FAILED, false);
        assertTrue(refused.contains("Coleta parcial recusada: a pilha-alvo de 64"));
        assertTrue(refused.contains("observados 0/5 itens; faltam 5"));
    }

    @Test void oversizedMergedTargetIsRefusedBeforeAnotherNavigationStep() throws Exception {
        var target = new net.minecraft.world.entity.item.ItemEntity(
                net.minecraft.world.entity.EntityType.ITEM, null);
        target.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
        var task = taskWithDelivered(0);
        var field = CollectItemsTask.class.getDeclaredField("currentItem");
        field.setAccessible(true);
        field.set(task, target);
        var navigate = CollectItemsTask.class.getDeclaredMethod("tickNavigate");
        navigate.setAccessible(true);
        assertDoesNotThrow(() -> navigate.invoke(task), "Refusal must precede any navigation side effect");
        assertNull(field.get(task));
        assertEquals(64, target.getItem().getCount());
    }

    @Test void exhaustedPartialCollectionIsNotSuccess() throws Exception {
        var task = taskWithDelivered(2);
        var phase = CollectItemsTask.class.getDeclaredField("phase");
        phase.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object done = Enum.valueOf((Class) phase.getType(), "DONE");
        phase.set(task, done);
        assertEquals(TaskState.FAILED, assertDoesNotThrow(task::tick,
                "An exhausted task must decide from its verified count, not enter the world again"));
    }

    @Test void executorUsesScopedTargetReceiptsInsteadOfCrossTickInventoryDeltas() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                System.getProperty("mineagentx.projectRoot", ".."),
                "engine/src/main/java/com/mineagent/engine/task/CollectItemsTask.java"));
        assertFalse(source.contains("updateCollectedFromInventory"),
                "Unrelated grants between ticks must never count as collection");
        assertTrue(source.contains("new CollectionPickupObservation("));
        assertTrue(source.contains("ItemEntityPickupEvent.Post"));
        assertTrue(source.contains("event.getPlayer() == sp"));
        assertTrue(source.contains("unregister(listener)"));
        assertTrue(source.contains("finally"));
        assertFalse(source.contains("new ItemEntity("));
        assertTrue(source.contains(".filter(item -> item.getItem().getCount() <= remaining)"),
                "Do not deliberately navigate toward an oversized target that vanilla may auto-pick up");
        int refusalStart = source.indexOf("if (entityStack.getCount() > remaining)");
        String refusal = source.substring(refusalStart, source.indexOf("var sp =", refusalStart));
        assertTrue(refusal.contains("nav.cancel()"), "Refusal must stop the previous movement goal");
        assertTrue(source.contains("intentContract().terrainPolicy()"));
    }

    @Test void executorLocalizesActualMessagesSnapshotsAndIntentAtRuntime() throws Exception {
        var task = taskWithDelivered(2);
        RuntimeLanguage.configure("pt_br");
        assertEquals("Coleta incompleta: observados 2/5 itens; faltam 3. Nenhuma outra coleta verificada.",
                task.completionMessage(TaskState.FAILED, false));
        assertEquals("Coleta cancelada: observados 2/5 itens; faltam 3.",
                task.completionMessage(TaskState.CANCELLED, false));
        assertEquals("Tempo de coleta esgotado: observados 2/5 itens; faltam 3.",
                task.completionMessage(TaskState.FAILED, true));
        assertTrue(task.snapshot().summary().startsWith("Coletando minecraft:stone"));
        assertEquals("Coletar itens próximos no chão", task.intentContract().goal());
        assertFalse(task.intentContract().terrainPolicy().allowBreakingObstacles());
        assertEquals("protect_terrain", task.intentContract().constraints().getFirst().id());
        assertEquals("navigation", task.intentContract().constraints().getFirst().scope());
        RuntimeLanguage.configure("en_us");
        assertEquals("Collection incomplete: observed 2/5 items; remaining 3. No further verified pickup.",
                task.completionMessage(TaskState.FAILED, false));
        assertTrue(task.snapshot().summary().startsWith("Collecting minecraft:stone"));
    }

    @Test void fullObservedRequestCanCompleteInBothLanguages() throws Exception {
        var task = taskWithDelivered(5);
        var phase = CollectItemsTask.class.getDeclaredField("phase");
        phase.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object done = Enum.valueOf((Class) phase.getType(), "DONE");
        phase.set(task, done);
        assertEquals(TaskState.SUCCESS, task.tick());
        assertEquals("Collection complete: observed 5/5 items; remaining 0.",
                task.completionMessage(TaskState.SUCCESS, false));
        RuntimeLanguage.configure("pt_br");
        assertEquals("Coleta concluída: observados 5/5 itens; faltam 0.",
                task.completionMessage(TaskState.SUCCESS, false));
    }

    @Test void localizedWrappersPreserveLiteralDiagnosticsAndIdentifiers() throws Exception {
        String literal = "literal {0} \\ $ \"name\"\nline";
        var task = taskWithDelivered(0);
        var key = CollectItemsTask.class.getDeclaredField("failReason");
        key.setAccessible(true);
        key.set(task, "navigation_failure");
        var args = CollectItemsTask.class.getDeclaredField("failArguments");
        args.setAccessible(true);
        args.set(task, new Object[] {literal});
        assertTrue(task.completionMessage(TaskState.FAILED, false).endsWith("Navigation to item failed: " + literal));
        RuntimeLanguage.configure("pt_br");
        assertTrue(task.completionMessage(TaskState.FAILED, false).endsWith("Falha na navegação até o item: " + literal));
        var identifierTask = new CollectItemsTask(null,
                new CollectItemsTool.CollectItemsTaskRecord("literal-id", literal, 8, 5));
        assertEquals("Coletando " + literal, identifierTask.snapshot().summary());
        assertTrue(identifierTask.snapshot().evidence().contains("coleta automática"));
    }

    @Test void terminalMessageCannotLabelPartialDeliveryComplete() throws Exception {
        assertEquals("Collection incomplete: observed 2/5 items; remaining 3. No further verified pickup.",
                taskWithDelivered(2).completionMessage(TaskState.SUCCESS, false));
    }

    @Test void partialFailureReportsObservedRequestedAndRemaining() throws Exception {
        assertEquals("Collection incomplete: observed 2/5 items; remaining 3. No further verified pickup.",
                taskWithDelivered(2).completionMessage(TaskState.FAILED, false));
    }
}
