package com.mineagent.safety;

import com.mojang.authlib.GameProfile;
import com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;

/** Constructor-free test boundaries, not a running server or simulated game ticks. */
final class PlacementReachFixture {
    static <T> T allocate(Class<T> type) throws Exception {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }
    static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    static TestPlayer player() throws Exception {
        TestPlayer p = allocate(TestPlayer.class);
        p.inventory = new Inventory(p);
        p.attributes = new AttributeMap(net.minecraft.world.entity.ai.attributes.AttributeSupplier.builder()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).build());
        field(p, LivingEntity.class, "attributes", p.attributes);
        field(p, Entity.class, "position", new Vec3(0.5, 0.0, 0.5));
        field(p, Entity.class, "eyeHeight", 0.5f);
        field(p, Entity.class, "blockPosition", BlockPos.ZERO);
        p.world = allocate(TestLevel.class);
        p.world.blocks = new HashMap<>();
        p.world.border = new WorldBorder();
        p.gameMode = allocate(TestMode.class);
        ((TestMode) p.gameMode).reach = 4.5;
        return p;
    }
    static final class TestPlayer extends ServerPlayer {
        Inventory inventory;
        AttributeMap attributes;
        TestLevel world;
        boolean creative;
        int worldReads;
        double lastTolerance = Double.NaN;
        int reachChecks;
        boolean sneaking;
        int aimed;
        boolean stopAtAim;
        private TestPlayer() { super(null, null, (GameProfile) null, (ClientInformation) null); }
        @Override public Inventory getInventory() { return inventory; }
        @Override public ServerLevel serverLevel() { worldReads++; return world; }
        @Override public Level level() { return world; }
        @Override public boolean isCreative() { return creative; }
        @Override public boolean isDescending() { return false; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isInWater() { return false; }
        @Override public Vec3 getDeltaMovement() { return new Vec3(0, -1, 0); }
        @Override public boolean isShiftKeyDown() { return sneaking; }
        @Override public void setShiftKeyDown(boolean value) { sneaking = value; }
        @Override public void lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor anchor, Vec3 point) {
            aimed++;
            if (stopAtAim) throw new ReachedVanillaUse();
        }
        @Override public ItemStack getMainHandItem() { return inventory.getSelected(); }
        @Override public ItemStack getOffhandItem() { return inventory.offhand.get(0); }
        @Override public ItemStack getItemInHand(net.minecraft.world.InteractionHand hand) {
            return hand == net.minecraft.world.InteractionHand.MAIN_HAND ? inventory.getSelected() : inventory.offhand.get(0);
        }
        @Override public boolean blockActionRestricted(Level level, BlockPos pos, net.minecraft.world.level.GameType type) { return false; }
        @Override public boolean canInteractWithBlock(BlockPos pos, double tolerance) {
            lastTolerance = tolerance;
            reachChecks++;
            return super.canInteractWithBlock(pos, tolerance);
        }
    }
    static final class TestLevel extends ServerLevel {
        Map<BlockPos, BlockState> blocks;
        WorldBorder border;
        private TestLevel() { super(null, null, null, null, null, null, null, false, 0, null, false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public boolean mayInteract(Player p, BlockPos pos) { return true; }
    }
    static final class TestMode extends FakePlayerGameMode {
        double reach;
        int useCalls;
        net.minecraft.world.phys.BlockHitResult hit;
        private TestMode() { super(null); }
        @Override public double getReachDistance() { return reach; }
        @Override public net.minecraft.world.InteractionResult useItem(ServerPlayer player,
                Level level, ItemStack stack, net.minecraft.world.InteractionHand hand) {
            useCalls++;
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        @Override public net.minecraft.world.InteractionResult useItemOn(ServerPlayer player, Level level,
                ItemStack stack, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
            useCalls++;
            this.hit = hit;
            throw new ReachedVanillaUse();
        }
    }
    static final class ReachedVanillaUse extends RuntimeException { }
}
