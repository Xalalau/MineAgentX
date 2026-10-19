package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.MessageCatalog;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.util.McCompat;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.inventory.CollectItemsTool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Set;
import java.util.UUID;

/** Navigates physically and corroborates target pickup events with matching stack gain. */
public class CollectItemsTask extends CompanionTask<CollectItemsTool.CollectItemsTaskRecord>
        implements IntentAwareTask {

    private enum Phase { SCAN, NAVIGATE, PICKUP, DONE }

    private static final double PICKUP_RANGE = 1.5;
    private static final int MAX_WAIT_TICKS = 40;

    private static final MessageCatalog MESSAGES =
            new MessageCatalog(Map.of(
                    Language.EN_US, Map.ofEntries(
                            Map.entry("failure", "Collection incomplete: observed {0}/{1} items; remaining {2}. {3}"),
                            Map.entry("success", "Collection complete: observed {0}/{1} items; remaining {2}."),
                            Map.entry("cancelled", "Collection cancelled: observed {0}/{1} items; remaining {2}."),
                            Map.entry("timeout", "Collection timed out: observed {0}/{1} items; remaining {2}."),
                            Map.entry("unverified", "No further verified pickup."),
                            Map.entry("navigation_failure", "Navigation to item failed: {0}"),
                            Map.entry("inventory_refused", "Item could not enter inventory."),
                            Map.entry("no_items", "No eligible items found within radius {0}."),
                            Map.entry("no_reachable", "No reachable items found."),
                            Map.entry("unsafe_partial", "Partial pickup refused: target stack {0} exceeds remaining request {1}; the original entity was not modified. Automatic pickup is not capped by this task."),
                            Map.entry("collecting", "Collecting {0}"),
                            Map.entry("nearby", "nearby items"),
                            Map.entry("intent_goal", "Collect nearby dropped items"),
                            Map.entry("intent_outcome", "Complete only after target pickup post-events are corroborated by matching item/component inventory gain; never split entities to enforce a cap. Automatic pickup may be unobserved and exceed the request."),
                            Map.entry("protect_terrain", "Do not alter terrain merely to collect a dropped item"),
                            Map.entry("pickup_limit", "Counts cover only task-observed pickups; automatic pickup outside this task may be unobserved and exceed the request.")
                    ),
                    Language.PT_BR, Map.ofEntries(
                            Map.entry("failure", "Coleta incompleta: observados {0}/{1} itens; faltam {2}. {3}"),
                            Map.entry("success", "Coleta concluída: observados {0}/{1} itens; faltam {2}."),
                            Map.entry("cancelled", "Coleta cancelada: observados {0}/{1} itens; faltam {2}."),
                            Map.entry("timeout", "Tempo de coleta esgotado: observados {0}/{1} itens; faltam {2}."),
                            Map.entry("unverified", "Nenhuma outra coleta verificada."),
                            Map.entry("navigation_failure", "Falha na navegação até o item: {0}"),
                            Map.entry("inventory_refused", "O item não pôde entrar no inventário."),
                            Map.entry("no_items", "Nenhum item elegível encontrado no raio {0}."),
                            Map.entry("no_reachable", "Nenhum item acessível encontrado."),
                            Map.entry("unsafe_partial", "Coleta parcial recusada: a pilha-alvo de {0} excede os {1} itens restantes; a entidade original não foi modificada. A coleta automática não é limitada por esta tarefa."),
                            Map.entry("collecting", "Coletando {0}"),
                            Map.entry("nearby", "itens próximos"),
                            Map.entry("intent_goal", "Coletar itens próximos no chão"),
                            Map.entry("intent_outcome", "Concluir somente após confirmar eventos de coleta do alvo por ganho no inventário com item e componentes correspondentes; nunca dividir entidades para impor um limite. A coleta automática pode não ser observada e exceder o pedido."),
                            Map.entry("protect_terrain", "Não alterar o terreno apenas para coletar um item no chão"),
                            Map.entry("pickup_limit", "As quantidades abrangem apenas coletas observadas pela tarefa; a coleta automática fora dela pode não ser observada e exceder o pedido.")
                    )
            ));

    private static String t(String key, Object... arguments) {
        return MESSAGES.text(RuntimeLanguage.language(), key, arguments);
    }

    private String reason() {
        return failReason == null ? t("unverified") : t(failReason, failArguments);
    }

    private PlayerNav nav;
    private Phase phase;
    private int collectedCount;
    private boolean observingCollisions;
    private ItemEntity currentItem;
    private final Set<UUID> unreachableItems = new HashSet<>();
    private int waitTicks;
    private String failReason;
    private Object[] failArguments = new Object[0];

    public CollectItemsTask(AgentPlayer player, CollectItemsTool.CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        observingCollisions = true;
        phase = Phase.SCAN;
        collectedCount = 0;
        currentItem = null;
        waitTicks = 0;
        failReason = null;
        unreachableItems.clear();

        setupNavigation();
        scanForItems();
    }

    @Override
    protected void onResume() {
        observingCollisions = true;
        // Verified synchronous pickup receipts survive an interruption.
        // Entity references and paths do not.
        currentItem = null;
        waitTicks = 0;
        failReason = null;
        setupNavigation();
        scanForItems();
    }

    private void setupNavigation() {
        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches, intentContract().terrainPolicy());
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.PICKUP;
            }

            @Override
            public void onNavigationFailed(String reason) {
                if (currentItem != null) unreachableItems.add(currentItem.getUUID());
                failReason = "navigation_failure";
                failArguments = new Object[] {String.valueOf(reason)};
                currentItem = null;
                phase = Phase.SCAN;
            }
        });
    }

    @Override
    protected TaskState onTick() {
        if (phase == Phase.DONE) {
            return collectedCount >= record.maxCount ? TaskState.SUCCESS : TaskState.FAILED;
        }
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case SCAN -> scanForItems();
            case NAVIGATE -> tickNavigate();
            case PICKUP -> tickPickup();
            case DONE -> { }
        }

        if (collectedCount >= record.maxCount) return TaskState.SUCCESS;
        if (phase == Phase.DONE) return TaskState.FAILED;
        return TaskState.RUNNING;
    }

    private void tickNavigate() {
        if (currentItem == null || !currentItem.isAlive()) {
            currentItem = null;
            phase = Phase.SCAN;
            return;
        }

        if (currentItem.getItem().getCount() > record.maxCount - collectedCount) {
            touchCurrentItemWithinLimit();
            return;
        }
        nav.tick();
        if (distanceToItem() <= PICKUP_RANGE) {
            nav.cancel();
            touchCurrentItemWithinLimit();
            if (currentItem != null) phase = Phase.PICKUP;
        }
    }

    private void tickPickup() {
        if (currentItem != null && currentItem.isAlive()
                && distanceToItem() <= PICKUP_RANGE) {
            // Invoke the normal entry point after physical navigation. Vanilla
            // collision pickup can also occur independently of this task.
            touchCurrentItemWithinLimit();
        }

        if (currentItem == null || !currentItem.isAlive()) {
            waitTicks = 0;
            currentItem = null;
            if (collectedCount >= record.maxCount) cancelNav();
            else phase = Phase.SCAN;
            return;
        }

        if (++waitTicks > MAX_WAIT_TICKS) {
            unreachableItems.add(currentItem.getUUID());
            waitTicks = 0;
            currentItem = null;
            failReason = "inventory_refused";
            failArguments = new Object[0];
            phase = Phase.SCAN;
        }
    }

    private void scanForItems() {
        var sp = TaskContext.serverPlayer(player);
        ServerLevel level = sp.serverLevel();
        var pos = sp.blockPosition();
        AABB searchBox = new AABB(
                pos.getX() - record.radius, pos.getY() - record.radius, pos.getZ() - record.radius,
                pos.getX() + record.radius + 1, pos.getY() + record.radius + 1,
                pos.getZ() + record.radius + 1);

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox,
                item -> item.isAlive()
                        && !unreachableItems.contains(item.getUUID())
                        && (record.itemId == null
                            || McCompat.isItem(item.getItem(), record.itemId)));
        int remaining = record.maxCount - collectedCount;
        ItemEntity oversized = items.stream()
                .filter(item -> item.getItem().getCount() > remaining).findFirst().orElse(null);
        // Refuse before navigating as well as at touch: normal automatic pickup
        // can run during movement. It still cannot be globally capped here.
        items = items.stream().filter(item -> item.getItem().getCount() <= remaining).toList();
        if (items.isEmpty()) {
            cancelNav();
            if (oversized != null) {
                failReason = "unsafe_partial";
                failArguments = new Object[] {oversized.getItem().getCount(), remaining};
            } else if (failReason == null) {
                failReason = "no_items";
                failArguments = new Object[] {record.radius};
            }
            phase = Phase.DONE;
            return;
        }

        currentItem = items.stream()
                .min(java.util.Comparator.comparingDouble(
                        item -> sp.distanceToSqr(item)))
                .orElse(null);
        if (currentItem == null) {
            failReason = "no_reachable";
            failArguments = new Object[0];
            phase = Phase.DONE;
            return;
        }

        failReason = null;
        navigateToItem();
    }

    private void navigateToItem() {
        if (currentItem == null || !currentItem.isAlive()) {
            phase = Phase.SCAN;
            return;
        }
        BlockPos target = currentItem.blockPosition();
        nav.navigateTo(target.getX(), target.getY(), target.getZ());
        phase = Phase.NAVIGATE;
    }

    private double distanceToItem() {
        return currentItem == null ? Double.MAX_VALUE
                : TaskContext.serverPlayer(player).position().distanceTo(currentItem.position());
    }

    /** Refuse unsafe partial operations and observe a normal, unmodified entity touch. */
    private void touchCurrentItemWithinLimit() {
        if (currentItem == null || !currentItem.isAlive()) return;
        int remaining = record.maxCount - collectedCount;
        if (remaining <= 0) return;

        ItemStack entityStack = currentItem.getItem();
        if (entityStack.getCount() > remaining) {
            // Vanilla has no bounded playerTouch. Do not split or replace an
            // entity merely to satisfy a cap: ownership, age and hooks belong
            // to the original entity. Refuse the unsafe partial operation.
            if (nav != null) nav.cancel();
            unreachableItems.add(currentItem.getUUID());
            failReason = "unsafe_partial";
            failArguments = new Object[] {entityStack.getCount(), remaining};
            currentItem = null;
            phase = Phase.SCAN;
            return;
        }

        var sp = TaskContext.serverPlayer(player);
        // Match the vanilla player's collision pickup envelope. playerTouch
        // itself has no reach check. Do not reach through a vertical gap merely
        // because a spherical distance test passed.
        if (sp.getHealth() <= 0.0F || sp.isSpectator()) return;
        AABB pickupBox = sp.isPassenger() && !sp.getVehicle().isRemoved()
                ? sp.getBoundingBox().minmax(sp.getVehicle().getBoundingBox()).inflate(1.0, 0.0, 1.0)
                : sp.getBoundingBox().inflate(1.0, 0.5, 1.0);
        if (!pickupBox.intersects(currentItem.getBoundingBox())) return;

        if (CollectionCollisionFrame.observes(this)) {
            currentItem.playerTouch(sp);
            TaskContext.syncInventory(sp);
            return;
        }

        var observation = new CollectionPickupObservation(currentItem, sp.getInventory());
        Consumer<ItemEntityPickupEvent.Post> listener = event -> {
            if (event.getPlayer() == sp) observation.record(event);
        };
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, false,
                ItemEntityPickupEvent.Post.class, listener);
        try {
            // Keep the original entity, vanilla ownership/pickup delay, and
            // NeoForge Pre/Post event order. No inventory writes or cloning.
            currentItem.playerTouch(sp);
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            collectedCount += observation.delivered();
            TaskContext.syncInventory(sp);
        }
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() {
        observingCollisions = false;
        cancelNav();
    }

    boolean observesCollisions() { return observingCollisions; }

    boolean coversCollision(ItemEntity item) {
        return record.itemId == null || McCompat.isItem(item.getItem(), record.itemId);
    }

    void recordCollisionPickup(int delivered) {
        collectedCount += delivered;
    }

    @Override
    public TaskSnapshot snapshot() {
        BlockPos target = currentItem == null ? null : currentItem.blockPosition();
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        long entityBits = currentItem == null ? 0L
                : currentItem.getUUID().getLeastSignificantBits();
        long version = ((long) collectedCount << 32)
                ^ (phase == null ? 0L : phase.ordinal()) ^ entityBits;
        return TaskSnapshot.progress(stage,
                t("collecting", record.itemId == null ? t("nearby") : record.itemId),
                collectedCount, record.maxCount,
                target == null ? null : target.getX(),
                target == null ? null : target.getY(),
                target == null ? null : target.getZ(),
                phase == Phase.DONE && collectedCount < record.maxCount ? reason() : null,
                t("pickup_limit") + (currentItem == null ? "" : " item_entity=" + currentItem.getUUID()),
                version);
    }

    @Override
    public IntentContract intentContract() {
        return new IntentContract(t("intent_goal"),
                t("intent_outcome"),
                null, null, null,
                new IntentContract.TerrainPolicy(false, false, false, false,
                        0, 0, 3, IntentContract.CleanupMode.CONTEXTUAL),
                java.util.List.of(new IntentContract.Constraint("protect_terrain",
                        IntentContract.ConstraintKind.HARD,
                        t("protect_terrain"),
                        "navigation")));
    }

    @Override
    protected String successMessage() {
        if (collectedCount < record.maxCount) return failureMessage();
        return t("success", collectedCount, record.maxCount, Math.max(0, record.maxCount - collectedCount));
    }

    @Override
    protected String cancelledMessage() {
        return t("cancelled", collectedCount, record.maxCount, Math.max(0, record.maxCount - collectedCount));
    }

    @Override
    protected String timeoutMessage() {
        return t("timeout", collectedCount, record.maxCount, Math.max(0, record.maxCount - collectedCount));
    }

    @Override
    protected String failureMessage() {
        return t("failure", collectedCount, record.maxCount,
                Math.max(0, record.maxCount - collectedCount), reason());
    }
}
