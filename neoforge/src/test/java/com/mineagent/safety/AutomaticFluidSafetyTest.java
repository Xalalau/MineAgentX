package com.mineagent.safety;

import com.mineagent.api.entity.InputDriver;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.survival.MLGChain;
import com.mineagent.engine.survival.SurvivalBuiltin;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticFluidSafetyTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static final class Body extends CompanionEntity {
        int clears;
        Body(PlacementReachFixture.TestPlayer p) { super(p, null, "fixture"); }
        @Override public InputDriver inputDriver() {
            return (InputDriver) java.lang.reflect.Proxy.newProxyInstance(InputDriver.class.getClassLoader(),
                    new Class<?>[] {InputDriver.class}, (proxy, method, args) -> {
                        if (method.getName().equals("clear")) clears++;
                        return null;
                    });
        }
    }
    private static void phase(MLGChain chain, String value) throws Exception {
        var field = MLGChain.class.getDeclaredField("phase");
        field.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object phase = Enum.valueOf((Class) field.getType(), value);
        field.set(chain, phase);
    }
    private static void invokeBoundary(MLGChain chain, String method, PlacementReachFixture.TestPlayer p) throws Exception {
        var boundary = MLGChain.class.getDeclaredMethod(method, net.minecraft.server.level.ServerPlayer.class);
        boundary.setAccessible(true);
        try { boundary.invoke(chain, p); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (!(failure.getCause() instanceof PlacementReachFixture.ReachedVanillaUse)) throw failure;
        }
    }
    @Test void placementBoundaryRefusesUnisolatedWaterOverRealWireState() throws Exception {
        var p = PlacementReachFixture.player();
        p.stopAtAim = true;
        p.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
        var landing = new BlockPos(0, -3, 0);
        var water = landing.above();
        var wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        p.world.blocks.put(landing, Blocks.STONE.defaultBlockState());
        p.world.blocks.put(water, wire);
        var body = new Body(p);
        try {
            invokeBoundary(new MLGChain(body), "placeWhenReachable", p);
            assertEquals(0, p.aimed, "unauthorized fluid placement must not reach bucket aiming");
            assertSame(wire, p.world.getBlockState(water));
            assertTrue(p.getMainHandItem().is(Items.WATER_BUCKET));
        } finally { SurvivalBuiltin.remove(body); }
    }
    @Test void recoveryBoundaryRefusesSameFluidWithoutSourceProvenance() throws Exception {
        var p = PlacementReachFixture.player();
        p.stopAtAim = true;
        p.inventory.setItem(0, new ItemStack(Items.BUCKET));
        var water = new BlockPos(0, -1, 0);
        p.world.blocks.put(water, Blocks.WATER.defaultBlockState());
        var body = new Body(p);
        // Vanilla bootstrap has no loaded datapack tags. Bind only this holder
        // for the fixture and restore it, rather than mistake a missing tag for denial.
        var holder = net.minecraft.core.registries.BuiltInRegistries.FLUID
                .getHolderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.FLUID,
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("water")));
        var tags = holder.tags().toList();
        var bind = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
        bind.setAccessible(true);
        bind.invoke(holder, java.util.List.of(net.minecraft.tags.FluidTags.WATER));
        try {
            assertTrue(p.world.getFluidState(water).is(net.minecraft.tags.FluidTags.WATER));
            var chain = new MLGChain(body);
            PlacementReachFixture.field(chain, MLGChain.class, "waterPlaced", true);
            PlacementReachFixture.field(chain, MLGChain.class, "waterPos", water);
            invokeBoundary(chain, "recoverWater", p);
            assertEquals(0, p.aimed, "same fluid and waterPlaced flag do not prove ownership");
            assertTrue(p.world.getBlockState(water).is(Blocks.WATER));
            assertTrue(p.getMainHandItem().is(Items.BUCKET));
        } finally {
            bind.invoke(holder, tags);
            SurvivalBuiltin.remove(body);
        }
    }
    @Test void activeReflexWithoutAuthorityCannotWinTheBodyAuction() throws Exception {
        var body = new Body(PlacementReachFixture.player());
        try {
            var chain = new MLGChain(body);
            for (String state : new String[] {"WAITING_FOR_RANGE", "WAITING_FOR_LAND", "RECOVERING_WATER"}) {
                phase(chain, state);
                assertEquals(Float.NEGATIVE_INFINITY, chain.getPriority(body), state);
            }
        } finally { SurvivalBuiltin.remove(body); }
    }
    @Test void dangerousFallWithoutAuthorityCannotStartTheReflex() throws Exception {
        var p = PlacementReachFixture.player();
        p.world.blocks.put(new BlockPos(0, -5, 0), Blocks.STONE.defaultBlockState());
        p.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
        var body = new Body(p);
        try {
            assertEquals(Float.NEGATIVE_INFINITY, new MLGChain(body).getPriority(body));
        } finally { SurvivalBuiltin.remove(body); }
    }
    @Test void deliberateBucketUseStillReachesTheOrdinaryItemHandler() throws Exception {
        var p = PlacementReachFixture.player();
        var mode = (PlacementReachFixture.TestMode) p.gameMode;
        for (var item : new net.minecraft.world.item.Item[] {Items.WATER_BUCKET, Items.BUCKET}) {
            p.inventory.setItem(0, new ItemStack(item));
            int before = mode.useCalls;
            assertTrue(com.mineagent.engine.act.Interaction.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND));
            assertEquals(before + 1, mode.useCalls);
        }
    }
    @Test void deniedTickDoesNotClearMovementOrRearrangeBuckets() throws Exception {
        var p = PlacementReachFixture.player();
        p.inventory.setItem(12, new ItemStack(Items.WATER_BUCKET));
        var body = new Body(p);
        try {
            var chain = new MLGChain(body);
            for (String state : new String[] {"IDLE", "WAITING_FOR_RANGE", "WAITING_FOR_LAND", "RECOVERING_WATER"}) {
                phase(chain, state);
                chain.tick(body);
                assertEquals(0, body.clears, "denied reflex must not preempt movement: " + state);
                assertTrue(p.inventory.getItem(12).is(Items.WATER_BUCKET));
                assertEquals(0, p.inventory.selected);
                assertEquals(0, p.aimed);
                assertEquals(0, ((PlacementReachFixture.TestMode) p.gameMode).useCalls);
            }
        } finally { SurvivalBuiltin.remove(body); }
    }
}
