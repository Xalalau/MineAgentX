package com.mineagent.safety;

import com.mineagent.api.safety.CombatPolicy;
import com.mineagent.engine.safety.CombatSafetyEvents;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real arrow/NeoForge NBT and hook boundaries with a headless level/player fixture. Not gameplay. */
class CombatProvenanceDamageTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Minimal real NeoForge fluid registry for Entity construction, not loader startup.
        if (!BuiltInRegistries.REGISTRY.containsKey(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.FLUID_TYPES.location())) {
            var registry = net.neoforged.neoforge.registries.NeoForgeRegistries.FLUID_TYPES;
            net.minecraft.core.Registry.register(registry, "minecraft:empty",
                    new net.neoforged.neoforge.fluids.FluidType(net.neoforged.neoforge.fluids.FluidType.Properties.create()));
            try {
                var ctor = net.neoforged.neoforge.registries.NewRegistryEvent.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                var event = ctor.newInstance();
                event.register(registry);
                var fill = event.getClass().getDeclaredMethod("fill");
                fill.setAccessible(true);
                fill.invoke(event);
            } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        }
        NeoForge.EVENT_BUS.start();
    }
    @BeforeEach void register() { NeoForge.EVENT_BUS.register(CombatSafetyEvents.class); }
    @AfterEach void reset() {
        NeoForge.EVENT_BUS.unregister(CombatSafetyEvents.class);
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
    }

    @Test void companionArrowStillProtectsHumanAfterOwnerDisappears() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var shooter = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        var level = HarmfulPotionFixtures.allocate(EmptyLevel.class);
        var arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(shooter);
        NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(arrow, level));
        var removed = Entity.class.getDeclaredField("removalReason");
        removed.setAccessible(true);
        removed.set(shooter, Entity.RemovalReason.DISCARDED);
        assertNull(arrow.getOwner(), "vanilla owner lookup must really lose the removed body");
        var victim = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        victim.gameMode = new ServerPlayerGameMode(victim);
        var source = new DamageSource(Holder.direct(new DamageType("arrow", 0.1F)), arrow, arrow);
        assertTrue(CommonHooks.onEntityIncomingDamage(victim, new DamageContainer(source, 5)),
                "incoming damage consumer must cancel companion arrow after owner despawn");
    }

    @Test void savedArrowRetainsOriginAndUsesCurrentIndependentPermissions() throws Exception {
        CombatPolicy.configure(new CombatPolicy(true, true));
        var shooter = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        var level = HarmfulPotionFixtures.allocate(EmptyLevel.class);
        var arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(shooter);
        NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(arrow, level));
        var restored = roundTrip(arrow, level);
        assertNull(restored.getOwner());
        NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(restored, level, true));
        var human = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        human.gameMode = new ServerPlayerGameMode(human);
        var companion = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        assertFalse(denied(restored, human));
        assertFalse(denied(restored, companion));
        CombatPolicy.configure(new CombatPolicy(false, true));
        assertTrue(denied(restored, human), "permission is read at impact, not captured at launch");
        assertFalse(denied(restored, companion));
        CombatPolicy.configure(new CombatPolicy(true, false));
        assertFalse(denied(restored, human));
        assertTrue(denied(restored, companion));
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        assertTrue(denied(restored, human));
        assertTrue(denied(restored, companion));
        assertFalse(denied(restored, HarmfulPotionFixtures.allocate(net.minecraft.world.entity.animal.Pig.class)),
                "mob damage is not denied by protected-body policy");
    }

    @Test void humanAndUnownedArrowsRemainOrdinaryAfterSaveLoad() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var level = HarmfulPotionFixtures.allocate(EmptyLevel.class);
        var human = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        human.gameMode = new ServerPlayerGameMode(human);
        for (boolean owned : new boolean[]{true, false}) {
            var arrow = new Arrow(EntityType.ARROW, level);
            if (owned) arrow.setOwner(human);
            NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(arrow, level));
            var restored = roundTrip(arrow, level);
            NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(restored, level, true));
            assertFalse(denied(restored, human));
            assertFalse(denied(restored, HarmfulPotionFixtures.player(ItemStack.EMPTY)));
        }
    }

    @Test void environmentalDamageWithoutAuthorIsNotGloballyDenied() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var victim = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        var source = new DamageSource(Holder.direct(new DamageType("fall", 0.0F)));
        assertFalse(CommonHooks.onEntityIncomingDamage(victim, new DamageContainer(source, 5)));
    }

    @Test void ordinaryHumanMeleeIsNotChangedByCompanionPolicy() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var human = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        human.gameMode = new ServerPlayerGameMode(human);
        var source = new DamageSource(Holder.direct(new DamageType("player", 0.1F)), human);
        assertFalse(CommonHooks.onEntityIncomingDamage(HarmfulPotionFixtures.player(ItemStack.EMPTY), new DamageContainer(source, 5)));
        assertFalse(CommonHooks.onEntityIncomingDamage(human, new DamageContainer(source, 5)));
    }

    @Test void liveCompanionDamageStillUsesExistingGuard() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var shooter = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        var victim = HarmfulPotionFixtures.player(ItemStack.EMPTY);
        var source = new DamageSource(Holder.direct(new DamageType("player", 0.1F)), shooter);
        assertTrue(CommonHooks.onEntityIncomingDamage(victim, new DamageContainer(source, 5)));
    }

    @Test void nonServerJoinCannotAuthenticateCompanionOrigin() throws Exception {
        CombatPolicy.configure(CombatPolicy.DEFAULTS);
        var level = HarmfulPotionFixtures.allocate(EmptyLevel.class);
        var arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(HarmfulPotionFixtures.player(ItemStack.EMPTY));
        NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(arrow, null));
        var restored = roundTrip(arrow, level);
        assertFalse(denied(restored, HarmfulPotionFixtures.player(ItemStack.EMPTY)));
    }

    private static boolean denied(Arrow arrow, net.minecraft.world.entity.LivingEntity victim) {
        var source = new DamageSource(Holder.direct(new DamageType("arrow", 0.1F)), arrow, arrow.getOwner() == null ? arrow : arrow.getOwner());
        return CommonHooks.onEntityIncomingDamage(victim, new DamageContainer(source, 5));
    }

    private static Arrow roundTrip(Arrow arrow, EmptyLevel level) throws Exception {
        var nbt = arrow.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        var bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(nbt, new java.io.DataOutputStream(bytes));
        var persisted = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray())), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        var restored = new Arrow(EntityType.ARROW, level);
        restored.load(persisted);
        return restored;
    }

    static final class EmptyLevel extends ServerLevel {
        private EmptyLevel() { super(null, null, null, null, null, null, null, false, 0L, java.util.List.of(), false, null); }
        @Override public Entity getEntity(UUID uuid) { return null; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
    }
}
