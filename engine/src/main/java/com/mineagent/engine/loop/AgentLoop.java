package com.mineagent.engine.loop;

import com.mineagent.api.agent.tool.*;
import com.mineagent.api.agent.skill.*;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.llm.*;
import com.mineagent.api.llm.model.ModelFamilies;
import com.mineagent.api.llm.provider.*;
import com.mineagent.api.platform.Services;
import com.mineagent.api.task.*;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.execution.ExecutionOwner;
import com.mineagent.engine.execution.ExecutionProtocol;
import com.mineagent.api.execution.ExecutionToken;
import com.mineagent.engine.persona.PersonaProfile;
import com.mineagent.engine.identity.*;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.i18n.ModelText;
import com.mineagent.engine.persona.EmotionState;
import com.mineagent.engine.memory.PlaceEventMemory;
import com.mineagent.engine.memory.ReflectionSystem;
import com.mineagent.engine.memory.ImportanceEvaluator;
import com.mineagent.engine.memory.ExperienceStore;
import com.mineagent.engine.memory.LayeredMemory;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.skill.SkillRuntime;
import com.mineagent.engine.cache.DecisionCache;
import com.mineagent.engine.cognition.RealtimeCognition;
import com.mineagent.engine.cognition.SituationSnapshot;
import com.mineagent.engine.cognition.TeamBlackboard;
import com.mineagent.engine.theory.TheoryOfMind;
import com.mineagent.engine.knowledge.MinecraftKnowledgeGraph;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.planning.HierarchicalRollingPlanner;
import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.planning.ResourceReservationLedger;
import com.mineagent.engine.exploration.MechanismExplorer;
import com.mineagent.engine.exploration.EnvironmentFingerprint;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.BeliefState;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.engine.world.WorldAssetObserver;
import com.mineagent.engine.world.SemanticWorldModel;
import com.mineagent.engine.world.ActionTransactionLedger;
import com.mineagent.engine.telemetry.CognitionMetrics;
import com.mineagent.engine.cognition.CognitiveState;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.time.Duration;

/**
 * The Agent Loop - the core orchestrator that drives the LLM conversation
 * and executes tool calls.
 *
 * <h3>Architecture: event-driven, dual-rail</h3>
 * <ol>
 *   <li>The LLM is NOT in the tick loop - it only runs when:
 *       <ul>
 *         <li>Owner speaks to companion</li>
 *         <li>Tool result arrives</li>
 *         <li>Async task finishes</li>
 *       </ul>
 *   </li>
 *   <li>Tool results go through two rails:
 *       <ul>
 *         <li><b>Query rail</b> (sync tools): reply immediately, batch with
 *             other tool results</li>
 *         <li><b>Action rail</b> (async tools): dispatch a task, get a
 *             task_id; the task_finished event wakes the loop later</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Conversation flow</h3>
 * <pre>
 *   System prompt + history → LLM → assistant message
 *     ├─ text only → done, wait for next event
 *     └─ tool_calls → execute all → collect results → feed back → LLM again
 * </pre>
 *
 * <h3>Safety mechanisms</h3>
 * <ul>
 *   <li>History truncation: keeps last N messages to avoid context overflow</li>
 *   <li>Tool-call recursion cap: prevents infinite tool-calling loops</li>
 *   <li>LLM retry with backoff: handles transient network errors</li>
 *   <li>Assistant text output: speaks to the owner in-game</li>
 * </ul>
 */
public class AgentLoop {

    /** Maximum conversation history messages (excluding system prompt).
     *  Lowered from 50 → 24 to reduce token consumption and latency.
     *  Old messages are summarized (see {@link #trimHistory}) so context
     *  is not lost, just compressed. */
    private static final int MAX_HISTORY = 24;

    /** Number of recent messages to keep verbatim before summarizing.
     *  Messages older than this window get folded into a compact
     *  "[SUMMARY]" system message to save tokens. */
    private static final int RECENT_KEEP = 12;

    /** Hard bound for the accumulated rolling summary. */
    private static final int MAX_HISTORY_SUMMARY_CHARS = 2_400;

    /** Maximum consecutive tool-call rounds within a single turn. */
    private static final int MAX_TOOL_ROUNDS = 10;

    /** Maximum LLM retry attempts. */
    private static final int MAX_RETRIES = 3;

    /** Retry base delay in milliseconds. */
    private static final long RETRY_BASE_MS = 1000;

    /** Maximum length (chars) of a tool result before it gets truncated.
     *  Long tool outputs (e.g. look_around dumping 50 blocks) blow up
     *  token usage and slow down the next LLM call. We cap results to
     *  keep the conversation compact. */
    private static final int MAX_TOOL_RESULT_CHARS = 800;

    /** Cache TTL check interval — skip the LLM call entirely if we have
     *  a fresh cached decision for a similar situation. This is the
     *  single biggest latency win for repetitive actions (e.g. mining
     *  a vein block-by-block). */
    private static final boolean DECISION_CACHE_ENABLED = true;

    /** Maximum consecutive cache hits.
     *  Force an LLM call after this many consecutive hits to prevent the AI
     *  from endlessly repeating the same cached decision.
     *  Set to 2: allow consecutive mining reuse, then force reasoning on the third lookup. */
    private static final int MAX_CONSECUTIVE_CACHE_HITS = 2;

    /** Maximum remediation attempts for a text-only response.
     *  Give the LLM N chances to call a tool when it promised an action but did not act.
     *  End the turn after that limit to avoid unbounded recursion. */
    private static final int MAX_REMEDIATION_ROUNDS = 1;

    /**
     * Stable high-frequency tool surface. Specialized GUI, storage, ranged,
     * location and memory tools are exposed for the remainder of a turn by
     * query_extra_tools, keeping the large schema prefix cacheable.
     */
    private static final Set<String> CORE_TOOL_NAMES = Set.of(
            "goto", "look_around", "scan_blocks", "get_self_status",
            "resolve_need", "plan_acquisition",
            "auto_mine", "build", "craft", "lookup_recipe", "interact_at",
            "collect_items", "eat_item", "use_item", "equip_item", "transfer_items",
            "melee_attack", "todowrite", "task_status", "task_stop",
            "wait_for", "query_extra_tools", "identity_memory");
    private static final Set<String> SKILL_ACTION_TOOLS = Set.of(
            "goto", "auto_mine", "build", "melee_attack", "ranged_attack",
            "equip_item", "eat_item", "drop_items", "collect_items",
            "transfer_items", "craft", "interact_at", "interact_entity",
            "use_item", "wait_for", "close_gui");
    private static final Set<String> SKILL_TRACE_TOOLS = Set.of(
            "goto", "auto_mine", "build", "melee_attack", "ranged_attack",
            "equip_item", "eat_item", "drop_items", "collect_items",
            "transfer_items", "craft", "interact_at", "interact_entity",
            "use_item", "wait_for", "close_gui", "look_around", "scan_blocks", "scan_nearby_entities",
            "get_self_status", "get_owner_status", "get_world_info",
            "resolve_need", "plan_acquisition", "lookup_recipe", "inspect_block",
            "inspect_block_storage", "inspect_gui", "recall_memory");
    // Only live observations, not recall, planning predictions or self-authored identity output.
    private static final Set<String> IDENTITY_OBSERVATION_TOOLS = Set.of(
            "look_around", "scan_blocks", "scan_nearby_entities", "get_self_status",
            "get_owner_status", "get_world_info", "lookup_recipe", "inspect_block",
            "inspect_block_storage", "inspect_gui", "locate_structure", "locate_biome");
    private static final Set<String> INVENTORY_EXTRA_TOOLS = Set.of(
            "drop_items", "inspect_gui", "close_gui", "inspect_block_storage");
    private static final Set<String> PERCEPTION_EXTRA_TOOLS = Set.of(
            "recall_memory", "inspect_block", "scan_nearby_entities",
            "get_owner_status", "get_world_info");
    private static final Set<String> COMBAT_EXTRA_TOOLS = Set.of(
            "scan_nearby_entities", "ranged_attack", "interact_entity");
    private static final Set<String> LOCATION_EXTRA_TOOLS = Set.of(
            "recall_memory", "locate_structure", "locate_biome");
    private static final Set<String> GUI_EXTRA_TOOLS = Set.of(
            "inspect_gui", "close_gui", "inspect_block", "inspect_block_storage");

    /** Time since the last player message, in milliseconds.
     *  Skip the cache if the player spoke recently (<30 seconds): conversation
     *  needs genuine LLM understanding, and replayed decisions may answer the wrong question. */
    private static final long CACHE_SKIP_AFTER_PLAYER_MSG_MS = 30_000;

    private final AgentPlayer companion;
    private final String providerId;
    private final String apiKey;
    private final String model;  // final — model switching is not allowed at runtime
    private final String baseUrl;
    private final double temperature;
    private final int maxTokens;
    private volatile String reasoningEffort; // off/low/medium/high/xhigh/max or null

    private final List<ChatMessage> history = new ArrayList<>();
    /** Pending deliberation events, coalesced by semantic identity. */
    private enum EventSource { MACHINE, PEER }
    private record PendingEvent(EventSource source, String text) {}
    private final LinkedHashMap<String, PendingEvent> inbox = new LinkedHashMap<>();
    private long peerEventSequence;
    private final Object inboxLock = new Object(); // unified inbox lock (L1 fix)
    private static final int MAX_PENDING_EVENTS = 32;

    private volatile boolean inProgress = false;
    private volatile boolean suspended = false;

    /**
     * Stale Request detection (borrowed from Mindcraft).
     *
     * <p>Tracks the timestamp of the last significant event (owner message,
     * body log, task completion). When an LLM call is in progress and a
     * new event arrives, this timestamp updates. After the LLM returns,
     * we compare the timestamp — if it changed during the call, the
     * response is "stale" (the situation it was generated for no longer
     * applies) and we discard it and restart the turn.
     *
     * <p>This prevents the classic "AI answers a question you asked 10
     * seconds ago, ignoring the new one you just asked" problem, and
     * reduces latency for new messages by 5-10x (no waiting for the
     * old response to complete).
     */
    private final AtomicLong eventGeneration = new AtomicLong();
    private final ExecutionOwner executionOwner = new ExecutionOwner(System::nanoTime,
            task -> Services.platform().scheduleOnServer(task));
    private volatile ExecutionOwner.Lease activeSkillLease;
    private final Set<String> exposedExtraTools = ConcurrentHashMap.newKeySet();
    private record PendingSkillAction(String description,
                                      ChatMessage.ToolCallRef call) {}
    private final ConcurrentMap<String, PendingSkillAction> pendingTaskActions =
            new ConcurrentHashMap<>();
    /** Correlates executor task IDs with tool names for semantic outcomes. */
    private final ConcurrentMap<String, String> dispatchedActionTools =
            new ConcurrentHashMap<>();
    /** Retain exact async skill authority until its scheduler terminal receipt, even after revocation. */
    private final ConcurrentMap<String, ToolExecution> skillTaskExecutions = new ConcurrentHashMap<>();
    /** Verified action prefix for learning one reusable multi-step episode. */
    private final Object verifiedTraceLock = new Object();
    private final List<ChatMessage.ToolCallRef> verifiedActionTrace = new ArrayList<>();
    private String verifiedTraceGoal = "";

    /**
     * Only populated while this loop thread is blocked inside a provider HTTP
     * call. A newer owner command can interrupt that obsolete request instead
     * of waiting up to the provider timeout before beginning the new turn.
     */
    private final AtomicReference<Thread> activeLLMCallThread = new AtomicReference<>();

    /**
     * Server-thread-published body state. The LLM executor must never query a
     * live Minecraft level from its background thread, but it still needs
     * authoritative progress to avoid interpreting RUNNING as "moving".
     */
    private record LiveBodyState(String taskId, String taskName, TaskState state,
                                 TaskSnapshot snapshot, String message, long gameTick) {
        static LiveBodyState idle() {
            return new LiveBodyState(null, "idle", null, null, null, 0L);
        }
    }

    private final AtomicReference<LiveBodyState> liveBodyState =
            new AtomicReference<>(LiveBodyState.idle());

    /**
     * Sparse Thinking (borrowed from Game-TARS).
     *
     * <p>Not every situation requires LLM reasoning. Routine actions
     * (continuing to mine a vein, walking along a known path) can be
     * handled by the decision cache or fast-path heuristics. Only "key
     * decision points" (new task, unexpected event, owner message,
     * inventory full, mob appears) trigger a real LLM call.
     *
     * <p>Track the last action type to detect repetitive patterns.
     * When the same action type repeats, the decision cache is preferred.
     */
    private volatile String lastActionType = "";
    private volatile int sameActionCount = 0;

    private final ExecutorService executor;
    /** Local-only counters; never contain prompts, keys, or world dumps. */
    private final CognitionMetrics metrics;
    private final CognitiveState cognitiveState;
    /** Tiered recall layer shared by working, episodic, semantic and team evidence. */
    private final LayeredMemory layeredMemory;

    // ── Cognitive subsystems ──
    private final PersonaProfile persona;
    private final EvolvingIdentity identity = new EvolvingIdentity();
    private final IdentityReflection identityReflection = new IdentityReflection(identity,
            () -> MineAgentEngine.getConfig().reflection().intervalTicks());
    private final IdentityMemoryAccess identityMemoryAccess = new IdentityMemoryAccess(identity, identityReflection, eventGeneration::get);
    private final EmotionState emotion;
    private final PlaceEventMemory placeMemory;
    private final SkillLibrary skillLib;
    private final DecisionCache decisionCache;
    private final TheoryOfMind theoryOfMind;
    private final MinecraftKnowledgeGraph knowledgeGraph;
    private final PlanGraph planner;
    private final ReflectionSystem reflection;
    private final ImportanceEvaluator importance;
    private final BeliefState beliefState;
    /**
     * Unified object permanence for carried items, known storage, facilities
     * and dropped stacks. This is the common substrate behind reuse decisions;
     * it replaces one-off "is there a crafting table?" memory patches.
     */
    private final WorldAssetIndex worldAssetIndex;
    private final ExperienceStore experienceStore;
    /** Shared temporal evidence consumed by skills, planning, and exploration. */
    private final SemanticWorldModel semanticWorldModel;
    /** Correlated server-evidence ledger for all physical actions. */
    private final ActionTransactionLedger actionLedger;
    private final ResourceReservationLedger reservationLedger;
    /** Strategic/tactical/execution rolling horizon over the verified PlanGraph. */
    private final HierarchicalRollingPlanner rollingPlanner;
    /** One-at-a-time, risk-bounded unfamiliar-mechanism experiments. */
    private final MechanismExplorer mechanismExplorer;
    /** Replays learned action sequences only as executor-verified closed loops. */
    private final SkillRuntime skillRuntime;
    /**
     * Server-thread tactical cognition. It continuously publishes immutable
     * evidence and handles reactions that are too urgent for an HTTP round
     * trip; the LLM is woken only when executor evidence requires replanning.
     */
    private final RealtimeCognition realtimeCognition;
    /** Spatial memory — records points of interest discovered while
     *  exploring (ores, structures, hazards, chests). Backs the
     *  "Location memory" section of the system prompt so the LLM can recall
     *  "I saw iron at (10, 64, -5)" without re-scanning. */
    private final com.mineagent.engine.memory.CognitiveMap cognitiveMap;
    /** Persist memories on game shutdown and restore them on restart to avoid amnesia.
     *  May be null until the world data directory is set; memories then remain in RAM only. */
    private final com.mineagent.engine.memory.MemoryPersistence persistence;
    /** Stateful Responses transport is isolated per companion, never shared by registry singletons. */
    private final AtomicReference<ProviderSessionState> providerTransportState;
    /** Number of durable history messages already represented by a Responses session. */
    private int providerHistoryCursor;
    /** Published on the server thread; prompt construction never reads Level. */
    private final AtomicReference<WorldAssetIndex.Position> liveAssetPosition =
            new AtomicReference<>();
    private final AtomicLong liveAssetGameTick = new AtomicLong();
    private volatile long lastAssetSnapshotTick = Long.MIN_VALUE;

    public AgentLoop(AgentPlayer companion, String providerId, String apiKey,
                      String model, String baseUrl, double temperature, int maxTokens,
                      String reasoningEffort) {
        this.companion = companion;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "MineAgent-Loop-" + companion.companionId().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        this.providerId = providerId;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens != 0 ? maxTokens : ModelFamilies.defaultMaxTokens(model);
        this.reasoningEffort = reasoningEffort;
        this.metrics = new CognitionMetrics();
        this.cognitiveState = new CognitiveState();
        this.layeredMemory = new LayeredMemory();
        // Registry providers are process-wide singletons, while Responses
        // cursors are conversation-owned.  Persist a stable per-companion
        // identity in the request so two companions using the same model can
        // never accidentally continue one another's response chain.
        String conversationIdentity = stableConversationIdentity(companion);
        this.providerTransportState = new AtomicReference<>(
                new ProviderSessionState(providerId == null ? "unknown" : providerId,
                        model, conversationIdentity, null, null, 0L));

        // Initialize cognitive subsystems
        this.persona = new PersonaProfile(.5f, .5f, .5f, .5f, .5f, new String[0]);
        this.emotion = new EmotionState();
        this.placeMemory = new PlaceEventMemory();
        this.skillLib = new SkillLibrary();
        this.decisionCache = new DecisionCache();
        this.theoryOfMind = new TheoryOfMind();
        this.knowledgeGraph = new MinecraftKnowledgeGraph();
        this.planner = new PlanGraph();
        this.reflection = new ReflectionSystem();
        this.importance = new ImportanceEvaluator();
        this.beliefState = new BeliefState();
        this.worldAssetIndex = new WorldAssetIndex();
        this.experienceStore = new ExperienceStore();
        this.semanticWorldModel = new SemanticWorldModel();
        this.actionLedger = new ActionTransactionLedger();
        this.reservationLedger = new ResourceReservationLedger();
        this.rollingPlanner = new HierarchicalRollingPlanner(
                planner, semanticWorldModel);
        this.mechanismExplorer = new MechanismExplorer(semanticWorldModel,
                (experiment, supported, evidence, gameTick) ->
                        beliefState.observeRuleOutcome(experiment.subject(),
                                experiment.probeTool(), experiment.hypothesis(),
                                supported, evidence, gameTick),
                skillLib, EnvironmentFingerprint.capture(),
                this::dispatchExplorationCompensation);
        this.skillRuntime = new SkillRuntime(skillLib, semanticWorldModel,
                new SkillRuntime.ActionDispatcher() {
                    @Override
                    public SkillRuntime.DispatchResult dispatch(String actionId,
                                                                 String tool,
                                                                 com.google.gson.JsonObject arguments) {
                        return dispatchSkillAction(actionId, tool, arguments);
                    }

                    @Override
                    public void cancel(String actionId) {
                        cancelSkillAction(actionId);
                    }
                }, this::onSkillRuntimeCompleted);
        this.realtimeCognition = new RealtimeCognition(companion);
        this.cognitiveMap = new com.mineagent.engine.memory.CognitiveMap();
        // The persistent loader appends validated dialogue after this system
        // entry. Keeping index zero reserved preserves provider message order.
        history.add(ChatMessage.system(buildSystemPrompt()));

        // Memory persistence: per-companion directory, restore previous memories.
        // Solves the "restart = amnesia" problem — the companion remembers
        // locations, lessons and learned importance weights across restarts.
        //
        // IMPORTANT: the directory key must be STABLE across restarts.
        // companionId is a random UUID regenerated on every spawn/restore,
        // so it cannot be used — the restored companion would never find
        // its previous memories. Use ownerUuid + companionName instead,
        // matching CompanionStore's upsert key.
        com.mineagent.engine.memory.MemoryPersistence p = null;
        java.nio.file.Path worldDir = MineAgentEngine.getWorldDataDir();
        if (worldDir != null) {
            String stableKey = companion.ownerUuid()
                    + "_" + sanitizeForPath(companion.companionName());
            java.nio.file.Path memDir = worldDir.resolve("mineagent_memory")
                    .resolve(stableKey);
            p = new com.mineagent.engine.memory.MemoryPersistence(
                    memDir, cognitiveMap, placeMemory, importance, reflection,
                    planner, beliefState, worldAssetIndex, experienceStore,
                    skillLib, semanticWorldModel, rollingPlanner,
                    mechanismExplorer, history, cognitiveState, layeredMemory,
                    actionLedger, reservationLedger).attachIdentity(identity, identityReflection);
            p.loadAll();
            // A restored Responses response id already represents the durable
            // dialogue prefix.  Sending that prefix again duplicates turns and
            // destroys prompt-cache affinity; continue with only new events.
            if (p.loadedProviderSession() != null) {
                ProviderSessionState restored = p.loadedProviderSession();
                if (restored.providerId().equals(providerId)
                        && restored.model().equals(model)) {
                    // v0.3.x provider_session.json files have no stable
                    // conversation id.  Keep their response cursor, but bind
                    // it to this companion before it reaches a shared
                    // provider instance.
                    ProviderSessionState bound = restored.conversationId() == null
                            || !conversationIdentity.equals(restored.conversationId())
                            ? restored.withConversationId(conversationIdentity) : restored;
                    providerTransportState.set(bound);
                    if (restored.previousResponseId() != null) {
                        synchronized (history) {
                            // The persisted response id names the last
                            // provider response, while any tool outputs or
                            // owner events after its assistant message still
                            // need to be sent on the next continuation.
                            providerHistoryCursor = history.size();
                            for (int i = history.size() - 1; i >= 1; i--) {
                                if ("assistant".equals(history.get(i).role())) {
                                    providerHistoryCursor = i + 1;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        this.persistence = p;

        // The first message is deliberately immutable. Memory-loaded state is
        // attached as a transient tail context per request so provider prompt
        // caches can reuse the static instructions and conversation prefix.
    }

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Wake the loop - called when:
     * <ul>
     *   <li>Owner sends a chat message</li>
     *   <li>A task_finished event arrives</li>
     *   <li>A reflex report needs to be delivered</li>
     * </ul>
     */
    public synchronized void wake(String reason) {
        if (suspended) {
            // Preserve meaningful events for the first post-respawn turn, but
            // never let a dead/paused companion start LLM or tool work.
            if (!reasonAlreadyRecorded(reason)) {
                enqueueInbox(reason);
            }
            return;
        }
        if (inProgress) {
            // Currently in a turn - add to inbox for batch processing
            if (!reasonAlreadyRecorded(reason)) {
                enqueueInbox(reason);
            }
            if (shouldInterruptActiveCall(reason)) {
                // Generation advances only for a newly admitted decision event.
                // Previously every repeated blocked heartbeat advanced it and
                // interrupted the same HTTP request hundreds of times.
                executionOwner.invalidate(eventGeneration::incrementAndGet);
                Thread activeCall = activeLLMCallThread.get();
                if (activeCall != null) activeCall.interrupt();
            }
            return;
        }
        // Spawn/resume wakes do not already have an owner/body message. Queue
        // the reason itself so providers such as Anthropic receive a real user
        // event instead of an invalid request containing only system messages.
        if (!reasonAlreadyRecorded(reason)) {
            enqueueInbox(reason);
        }
        executionOwner.invalidate(eventGeneration::incrementAndGet);
        startTurn(reason);
    }

    private static boolean reasonAlreadyRecorded(String reason) {
        return "owner_message".equals(reason) || "body_log".equals(reason)
                || "cognition_decision".equals(reason) || "team_event".equals(reason)
                || "rolling_replan".equals(reason) || "goal_verified".equals(reason);
    }

    private static boolean shouldInterruptActiveCall(String reason) {
        // These events invalidate the action assumptions of an in-flight
        // request. Interrupting the provider call is materially faster than
        // waiting only to discard its stale response afterward.
        return "owner_message".equals(reason) || "body_log".equals(reason)
                || "cognition_decision".equals(reason) || "team_event".equals(reason)
                || "rolling_replan".equals(reason) || "goal_verified".equals(reason);
    }

    /**
     * Add one decision event while replacing an older event with the same
     * semantic identity. The bound is a final safety net for modded producers
     * that emit unbounded diagnostics.
     */
    private boolean enqueueInbox(String event) {
        return enqueueInbox(event, EventSource.MACHINE);
    }

    private boolean enqueueInbox(String event, EventSource source) {
        if (event == null || event.isBlank()) return false;
        synchronized (inboxLock) {
            // Speech has no machine identity, even when it quotes a terminal marker.
            String key = source == EventSource.PEER ? "peer:" + ++peerEventSequence
                    : "machine:" + inboxKey(event.stripLeading());
            PendingEvent pending = new PendingEvent(source, event);
            PendingEvent previous = inbox.put(key, pending);
            while (inbox.size() > MAX_PENDING_EVENTS) {
                Iterator<String> oldest = inbox.keySet().iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
            return !pending.equals(previous);
        }
    }

    private static String inboxKey(String event) {
        if (event.startsWith("[TASK_FINISHED]")) {
            return "task_finished:" + eventField(event, "task_id");
        }
        if (event.startsWith("[SKILL_FINISHED]")) {
            return "skill_finished:" + eventField(event, "run_id");
        }
        if (event.startsWith("[ROLLING_REPLAN]")) {
            return "rolling:" + eventField(event, "reason") + ':'
                    + eventField(event, "active_step");
        }
        if (event.startsWith("[GOAL_VERIFIED]")) return "goal_verified";
        if (event.startsWith("[COGNITION_DECISION]")) return "cognition_decision";
        return event.replaceAll("\\s+", " ");
    }

    private static String eventField(String event, String field) {
        String marker = field + '=';
        int start = event.indexOf(marker);
        if (start < 0) return "unknown";
        start += marker.length();
        int end = event.indexOf(' ', start);
        return end < 0 ? event.substring(start) : event.substring(start, end);
    }

    /**
     * Add an owner message and wake the loop.
     */
    public void onOwnerMessage(String message) {
        if (message == null || message.isBlank()) return;
        executionOwner.invalidate(eventGeneration::incrementAndGet);
        identityReflection.close();
        identity.recordEvidence("player_claim", "owner", message, liveAssetGameTick.get());
        // Speech informs retrieval/cache freshness, not an automatically admitted objective.
        lastOwnerCommand = message.length() > 120 ? message.substring(0, 120) : message;
        latestOwnerSpeech = truncateForLog(message, 360);
        lastOwnerMessageTime = System.currentTimeMillis();
        cognitiveState.append("owner_speech", "owner", truncateForLog(message, 300),
                currentGameTickSafe());
        // Speech remains a sourced report; only evidence-backed reflection revises identity.
        synchronized (history) {
            history.add(ChatMessage.user(message));
        }
        wake("owner_message");
    }

    /** Add a body observation to the next reasoning batch. */
    public void onBodyLog(String narrative) {
        if (narrative == null || narrative.isBlank()) return;
        cognitiveState.append("body_observation", "body", truncateForLog(narrative, 300),
                currentGameTickSafe());
        layeredMemory.remember(LayeredMemory.Tier.WORKING, "body.observation",
                truncateForLog(narrative, 300), "executor", 0.9,
                currentGameTickSafe(), 240L, "current", "current");
        // Keep routine body telemetry out of personal reflection; retain meaningful executor events.
        if (isDeliberationEvent(narrative)) {
            identity.recordEvidence("observation", "body", narrative, liveAssetGameTick.get());
        }

        // Low-level body observations are evidence, not independent decision
        // points. Waking on every breath/follow/pickup message created request
        // storms and also caused the turn-final inbox check to start an extra
        // request even without wake(). LiveBodyState/RealtimeCognition already
        // carry routine evidence, so only deliberation events enter the inbox.
        if (isDeliberationEvent(narrative)
                && enqueueInbox(narrative)) {
            wake("body_log");
        }
    }

    /** Receive an explicit teammate event without treating it as owner speech. */
    public void onTeamEvent(String event) {
        if (event == null || event.isBlank()) return;
        if (enqueueInbox(event, EventSource.PEER)) {
            wake("team_event");
        }
    }

    /** Commit a newly admitted body task to the shared planning state. */
    public void onTaskAccepted(String taskId, String taskName,
                               IntentContract intent, TaskSnapshot snapshot,
                               long gameTick) {
        boolean skillAction = skillRuntime.ownsAction(taskId);
        liveBodyState.set(new LiveBodyState(taskId, taskName, TaskState.RUNNING,
                snapshot, snapshot == null ? text("Task accepted", "Tarefa aceita") : snapshot.summary(), gameTick));
        // Inner skill actions belong to one plan-level skill run. Binding every
        // inner task would falsely verify the parent plan after step one.
        if (!skillAction) {
            boolean hadPlan = planner.hasActivePlan();
            planner.bindTask(taskId, taskName, intent, gameTick);
            if (!hadPlan) {
                rollingPlanner.onPlanReplaced(intent == null
                        ? taskName : intent.goal(), gameTick);
            }
            rollingPlanner.onTaskAccepted(taskId, taskName, snapshot, gameTick);
        }
        beliefState.observeFact("body", "current_task",
                intent == null ? taskName : intent.goal(), 1.0,
                "scheduler", gameTick);
        if (snapshot != null && !skillAction) {
            planner.recordProgress(taskId, snapshot, gameTick);
        }
        semanticWorldModel.observe("task:" + taskId, "status", "running",
                null, 1.0, "scheduler", taskId, gameTick, 1_200L, false);
        cognitiveState.setProjection("active_task", taskId + ":" + taskName, gameTick);
        layeredMemory.remember(LayeredMemory.Tier.EPISODIC, "task:" + taskId,
                "started " + taskName, "scheduler", 0.9, gameTick, 12_000L,
                "current", "current");
        TeamBlackboard.updateTask(companion.ownerUuid(), companion.companionId(),
                taskId, intent == null ? taskName : intent.goal(), TaskState.RUNNING,
                taskTarget(snapshot), gameTick);
        publishWorldSnapshot(gameTick, true);
    }

    /** Publish live executor progress without waking the LLM every tick. */
    public void onTaskProgress(String taskId, TaskState state,
                               TaskSnapshot snapshot, String message,
                               long gameTick) {
        LiveBodyState previous = liveBodyState.get();
        String taskName = previous != null && Objects.equals(previous.taskId(), taskId)
                ? previous.taskName() : "body_task";
        // Paused survival preemption is materially different from an executor
        // that is still RUNNING. Publishing every progress event as RUNNING
        // made the model believe the old task still owned the body while a
        // breath/combat chain was actually in control.
        liveBodyState.set(new LiveBodyState(taskId, taskName,
                state == null ? TaskState.RUNNING : state,
                snapshot, message, gameTick));
        boolean skillAction = skillRuntime.ownsAction(taskId);
        if (skillAction) skillRuntime.onTaskProgress(taskId,
                state == null ? TaskState.RUNNING : state, gameTick);
        if (!skillAction) {
            planner.recordProgress(taskId, snapshot, gameTick);
            rollingPlanner.onTaskProgress(taskId, snapshot, gameTick);
        }
        if (snapshot != null && snapshot.hasTarget()) {
            beliefState.observeFact("body", "task_target",
                    String.valueOf(snapshot.targetX()) + ","
                            + snapshot.targetY() + "," + snapshot.targetZ(),
                    1.0, "task:" + taskId, gameTick);
        }
        TeamBlackboard.updateTask(companion.ownerUuid(), companion.companionId(),
                taskId, taskName, state == null ? TaskState.RUNNING : state,
                taskTarget(snapshot), gameTick);
        publishWorldSnapshot(gameTick, false);
    }

    /**
     * Apply a verifier-backed terminal transition and retain the structured
     * experience. This is the only path by which an asynchronous task can
     * verify a plan node.
     */
    public void onTaskFinished(String taskId, String taskName,
                               IntentContract intent, TaskState state,
                               TaskSnapshot snapshot, String message,
                               long gameTick) {
        // Reconcile physical receipts even when revoked skill authority rejects learning below.
        if ("execute_construction".equals(dispatchedActionTools.get(taskId))) {
            try {
                com.mineagent.engine.construction.MinecraftConstructionBridge.open(companion)
                        .taskFinished(taskId, state.name());
            } catch (java.io.IOException | RuntimeException failure) {
                System.err.println("[MineAgent] Construction reconciliation failed: "
                        + failure.getClass().getSimpleName());
            }
        }
        ToolExecution skillExecution = skillTaskExecutions.remove(taskId);
        boolean ownedSkillTask = skillExecution != null && skillExecution.lease().active()
                && skillRuntime.ownsAction(taskId);
        if (skillExecution != null && (!ownedSkillTask || state == TaskState.CANCELLED)) {
            // A real receipt remains useful, but cannot revive an obsolete skill or teach its outcome.
            skillExecution.lease().release();
            dispatchedActionTools.remove(taskId);
            actionLedger.finish(taskId, ownedSkillTask ? ActionTransactionLedger.Status.CANCELLED
                            : ActionTransactionLedger.Status.UNVERIFIED,
                    message, semanticWorldModel, gameTick);
            semanticWorldModel.observe("task:" + taskId, "status", ownedSkillTask ? "cancelled" : "unverified",
                    null, 1.0, "scheduler", taskId, gameTick, 2_400L, false);
            cognitiveState.append("unverified_task_receipt", taskId, state + ":" + message, gameTick);
            enqueueInbox(text("Unverified task receipt; observe current state before repeating work: ",
                    "Recibo de tarefa não verificado; observe o estado atual antes de repetir o trabalho: ")
                    + taskId + " state=" + state + " " + message);
            if (ownedSkillTask) {
                LiveBodyState previous = liveBodyState.get();
                if (previous != null && Objects.equals(previous.taskId(), taskId)) {
                    liveBodyState.compareAndSet(previous, new LiveBodyState(null, "idle", null, null,
                            "last_task=" + taskName + " state=" + state, gameTick));
                }
                mechanismExplorer.onTaskFinished(taskId, state, message, gameTick);
                skillRuntime.onTaskFinished(taskId, state, message, gameTick);
            }
            publishWorldSnapshot(gameTick, true);
            return;
        }
        executionOwner.release(taskId);
        // A terminal task no longer owns the body. Keep its outcome as compact
        // evidence, but publish a null task id so the next decision sees an
        // idle executor instead of treating SUCCESS/FAILED as body occupancy.
        liveBodyState.set(new LiveBodyState(null, "idle", null, null,
                "last_task=" + taskName + " state=" + state
                        + (message == null || message.isBlank()
                        ? "" : " message=" + truncateForLog(message, 180)),
                gameTick));
        boolean skillAction = skillRuntime.ownsAction(taskId);
        PlanGraph.State planBeforeOutcome = planner.exportState();
        PlanGraph.PlanNode nodeBeforeOutcome = planner.currentNode();
        String traceGoal = !planBeforeOutcome.goal().isBlank()
                ? planBeforeOutcome.goal()
                : nodeBeforeOutcome == null ? taskName : nodeBeforeOutcome.description();
        if (!skillAction) {
            planner.recordOutcome(taskId, state, snapshot, message, gameTick);
            rollingPlanner.onTaskFinished(taskId, state, gameTick);
        }
        String goal = intent == null ? taskName : intent.goal();
        identity.recordEvidence("observation", "task:" + taskId,
                taskName + ": " + state + "; " + goal + "; " + (message == null ? "" : message), gameTick);
        ExperienceStore.Experience experience = experienceStore.record(
                taskId, taskName, goal, state, snapshot, message, gameTick);
        PendingSkillAction pendingAction = pendingTaskActions.remove(taskId);
        if (!skillAction && pendingAction != null) {
            appendVerifiedTrace(traceGoal, List.of(pendingAction.call()),
                    state == TaskState.SUCCESS, !planner.hasActivePlan());
        } else if (!skillAction && state != TaskState.SUCCESS) {
            appendVerifiedTrace(traceGoal, List.of(), false, false);
        }
        String mappedAction = dispatchedActionTools.remove(taskId);
        String transactionId = pendingAction == null ? taskId : pendingAction.call().id();
        String outcomeAction = mappedAction != null ? mappedAction
                : pendingAction == null ? taskName : pendingAction.call().name();
        // An executor terminal result is verified world interaction evidence.
        // Persist it across restarts; routine observations remain volatile.
        semanticWorldModel.recordOutcome(outcomeAction,
                state == TaskState.SUCCESS, message, taskId, gameTick, true);
        actionLedger.finish(transactionId,
                state == TaskState.SUCCESS ? ActionTransactionLedger.Status.VERIFIED
                        : state == TaskState.CANCELLED ? ActionTransactionLedger.Status.CANCELLED
                        : ActionTransactionLedger.Status.FAILED,
                message, semanticWorldModel, gameTick);
        mechanismExplorer.onTaskFinished(taskId, state, message, gameTick);
        if (skillAction) {
            skillRuntime.onTaskFinished(taskId, state, message, gameTick);
        }
        beliefState.observeRuleOutcome(goal, taskName,
                intent == null ? "executor success" : intent.successCriterion(),
                state == TaskState.SUCCESS,
                experience.failureKind() + ": " + experience.evidence(), gameTick);
        beliefState.observeFact("body", "current_task", "idle", 1.0,
                "scheduler", gameTick);
        semanticWorldModel.observe("task:" + taskId, "status",
                state.name().toLowerCase(Locale.ROOT), null, 1.0,
                "scheduler", taskId, gameTick, 2_400L, false);
        cognitiveState.append("task_outcome", taskId,
                state + ":" + truncateForLog(message, 260), gameTick);
        cognitiveState.setProjection("active_task", "idle", gameTick);
        layeredMemory.remember(state == TaskState.SUCCESS
                        ? LayeredMemory.Tier.EPISODIC : LayeredMemory.Tier.SEMANTIC,
                "task:" + taskName + ".outcome",
                state + ":" + truncateForLog(message, 320), "executor",
                state == TaskState.SUCCESS ? 0.95 : 0.65, gameTick,
                state == TaskState.SUCCESS ? 48_000L : 12_000L,
                "current", "current");
        TeamBlackboard.updateTask(companion.ownerUuid(), companion.companionId(),
                taskId, goal, state, taskTarget(snapshot), gameTick);
        // Task completion often changes inventory through mining, pickup,
        // crafting or placement. Capture the authoritative postcondition so
        // the next plan cannot reason from a pre-task inventory snapshot.
        publishWorldSnapshot(gameTick, true);
    }

    /**
     * Publish live player assets from the server thread at a bounded cadence.
     * The LLM thread consumes only immutable index records and never touches a
     * Minecraft Level or Inventory directly.
     */
    public void onServerStateTick(long gameTick) {
        publishWorldSnapshot(gameTick, false);
        try {
            actionLedger.expire(gameTick, 1_200L, semanticWorldModel);
            reservationLedger.expire(gameTick);
            if (gameTick % 200L == 0L) layeredMemory.consolidate(gameTick);
            var player = TaskContext.serverPlayer(companion);
            var owner = companion instanceof com.mineagent.engine.entity.CompanionEntity entity
                    ? entity.serverPlayerOwner() : null;
            RealtimeCognition.TickResult result = realtimeCognition.tick(
                    player, owner, toTaskObservation(liveBodyState.get()), gameTick);
            semanticWorldModel.observeSituation(realtimeCognition.currentFrame());
            // Evaluate strategic acceptance only after the authoritative live
            // snapshot has refreshed inventory, position, actors and task
            // facts. This prevents a completed tactical suffix from being
            // mistaken for completion of the owner's actual objective.
            boolean goalVerified = planner.verifyGoal(semanticWorldModel, gameTick);
            if (result.ownerIntent() != null) {
                var signal = result.ownerIntent();
                theoryOfMind.observeIntent(signal.intent(), signal.confidence(),
                        signal.evidence(), signal.gameTick());
            }
            // Skill and experiment transitions run on the authoritative server
            // thread. Each transition is bounded to one step per tick.
            skillRuntime.tick(gameTick);
            mechanismExplorer.tick(gameTick);
            if (skillRuntime.active()) {
                SkillRuntime.Snapshot skill = skillRuntime.snapshot();
                rollingPlanner.onTaskProgress(skill.runId(),
                        TaskSnapshot.progress("skill", skill.lastEvidence(),
                                skill.stepIndex(), skill.stepCount(),
                                null, null, null, null, skill.lastEvidence(),
                                skill.stepIndex()), gameTick);
            }
            HierarchicalRollingPlanner.ReplanSignal replan =
                    skillRuntime.active() ? null : rollingPlanner.tick(gameTick);
            // Tactical cognition and the rolling planner can identify the same
            // blocked executor frame in one tick. Prefer the structured rolling
            // signal and admit only one paid deliberation event.
            String deliberation = replan != null
                    ? replan.event() : result.deliberationEvent();
            if (goalVerified) {
                deliberation = "[GOAL_VERIFIED] goal="
                        + truncateForLog(planner.exportState().goal(), 240)
                        + "; all required milestones and acceptance conditions are satisfied";
            }
            if (deliberation != null && enqueueInbox(deliberation)) {
                wake(goalVerified ? "goal_verified"
                        : replan != null ? "rolling_replan" : "cognition_decision");
            }
        } catch (Throwable failure) {
            // Entity teardown can race the final engine tick. Cognitive
            // observation must never take down the server or the body loop.
            System.err.println("[MineAgent] Realtime cognition failed for "
                    + companion.companionName() + ": " + failure.getMessage());
        }
    }

    private static SituationSnapshot.TaskObservation toTaskObservation(LiveBodyState body) {
        if (body == null || body.taskId() == null) {
            return SituationSnapshot.TaskObservation.idle();
        }
        TaskSnapshot snapshot = body.snapshot();
        return new SituationSnapshot.TaskObservation(body.taskId(), body.taskName(),
                body.state(), snapshot == null ? "running" : snapshot.stage(),
                snapshot == null ? null : snapshot.blockedReason(), body.gameTick());
    }

    private static String taskTarget(TaskSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasTarget()) return null;
        return snapshot.targetX() + "," + snapshot.targetY() + "," + snapshot.targetZ();
    }

    private static boolean isDeliberationEvent(String narrative) {
        String value = narrative.stripLeading();
        return value.startsWith("[TASK_FINISHED]")
                || value.startsWith("[SKILL_FINISHED]")
                || value.startsWith("[GOAL_VERIFIED]")
                || value.startsWith("[ROLLING_REPLAN]")
                || value.startsWith("[COGNITION_DECISION]")
                || value.startsWith("[TEAM_SUPPORT]");
    }

    private long currentGameTickSafe() {
        long published = liveAssetGameTick.get();
        if (published > 0L) return published;
        try {
            return TaskContext.serverPlayer(companion).level().getGameTime();
        } catch (Throwable ignored) {
            // A monotonic local fallback preserves ordering during spawn before
            // the first world snapshot; it is never mixed with epoch time.
            return Math.max(0L, eventGeneration.get());
        }
    }

    private void publishWorldSnapshot(long gameTick, boolean force) {
        if (!force && lastAssetSnapshotTick != Long.MIN_VALUE
                && gameTick - lastAssetSnapshotTick < 20L) return;
        try {
            var sp = TaskContext.serverPlayer(companion);
            WorldAssetIndex.Position position = WorldAssetObserver.position(sp);
            List<WorldAssetIndex.ItemObservation> inventory =
                    WorldAssetObserver.inventory(sp);
            worldAssetIndex.observeInventory(position, inventory, gameTick);
            semanticWorldModel.observeInventory(position, inventory, gameTick);
            semanticWorldModel.observeAssets(worldAssetIndex.snapshot(), gameTick);
            liveAssetPosition.set(position);
            liveAssetGameTick.set(Math.max(0L, gameTick));
            lastAssetSnapshotTick = gameTick;
        } catch (Throwable failure) {
            // Companion construction/teardown can race a final tick. Missing
            // one snapshot is preferable to crashing the shared server loop.
            System.err.println("[MineAgent] Asset snapshot failed for "
                    + companion.companionName() + ": " + failure.getMessage());
        }
    }

    /** Safe callback entry: argument evaluation cannot escape during teardown. */
    private void publishWorldSnapshotNow(boolean force) {
        long gameTick = liveAssetGameTick.get();
        try {
            gameTick = TaskContext.serverPlayer(companion).level().getGameTime();
        } catch (Throwable ignored) {
            // publishWorldSnapshot performs the guarded body lookup and emits
            // one diagnostic if teardown has already detached the player.
        }
        publishWorldSnapshot(gameTick, force);
    }

    /**
     * Parse a body log narrative for place events and record them
     * in the PlaceEventMemory. Detects patterns like:
     * "saw iron_ore at (10, 64, -5)" or "found cow near 10,64,-5"
     */
    private void recordPlaceEventFromLog(String narrative) {
        if (narrative == null) return;
        // Look for coordinate patterns: (x, y, z) or x,y,z
        java.util.regex.Matcher m = COORD_PATTERN.matcher(narrative);
        if (m.find()) {
            try {
                int x = Integer.parseInt(m.group(1));
                int y = Integer.parseInt(m.group(2));
                int z = Integer.parseInt(m.group(3));
                // Determine subject and type from keywords
                String subject = "unknown";
                String type = "event";
                String lower = narrative.toLowerCase();
                if (lower.contains("iron_ore")) { subject = "iron_ore"; type = "resource"; }
                else if (lower.contains("coal")) { subject = "coal_ore"; type = "resource"; }
                else if (lower.contains("diamond")) { subject = "diamond_ore"; type = "resource"; }
                else if (lower.contains("gold")) { subject = "gold_ore"; type = "resource"; }
                else if (lower.contains("cow")) { subject = "cow"; type = "entity"; }
                else if (lower.contains("sheep")) { subject = "sheep"; type = "entity"; }
                else if (lower.contains("village")) { subject = "village"; type = "structure"; }
                else if (lower.contains("creeper")) { subject = "creeper"; type = "danger"; }
                else if (lower.contains("zombie")) { subject = "zombie"; type = "danger"; }
                else if (lower.contains("tree")) { subject = "tree"; type = "resource"; }

                String dimension = companion.dimensionKey();
                placeMemory.remember(type, subject, x, y, z, dimension,
                        System.currentTimeMillis(), narrative);

                // Also record in the cognitive map (spatial POI store)
                // with a semantic category for prompt-level recall.
                String category;
                String label;
                switch (type) {
                    case "resource" -> {
                        category = "resource:" + subject;
                        label = subject + " (resource)";
                    }
                    case "structure" -> {
                        category = "structure:" + subject;
                        label = subject;
                    }
                    case "danger" -> {
                        category = "hazard:" + subject;
                        label = subject + " (hazard)";
                    }
                    case "entity" -> {
                        category = "entity:" + subject;
                        label = subject;
                    }
                    default -> {
                        category = "event:" + subject;
                        label = subject;
                    }
                }
                cognitiveMap.recordPoi(new net.minecraft.core.BlockPos(x, y, z),
                        category, label, dimension, System.currentTimeMillis());
            } catch (NumberFormatException ignored) {
                // Coordinate parse failed — skip
            }
        }
    }

    private static final java.util.regex.Pattern COORD_PATTERN =
            java.util.regex.Pattern.compile("\\(?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\)?");

    /** Replace characters that are illegal in file paths (Windows/Unix). */
    private static String sanitizeForPath(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_");
    }

    /** Stable identity used solely for provider transport/session isolation. */
    private static String stableConversationIdentity(AgentPlayer player) {
        String owner = player == null || player.ownerUuid() == null
                ? "owner" : player.ownerUuid().toString();
        String name = player == null ? "companion" : sanitizeForPath(player.companionName());
        return "mineagent:" + owner + ":" + name;
    }

    /**
     * Migrate memory storage to a new companion name (called on rename).
     * Keeps the companion's memories across renames.
     */
    public void migrateMemoryStorage(String newName) {
        java.nio.file.Path worldDir = MineAgentEngine.getWorldDataDir();
        if (persistence != null && worldDir != null) {
            String newKey = companion.ownerUuid() + "_" + sanitizeForPath(newName);
            persistence.migrateTo(worldDir.resolve("mineagent_memory").resolve(newKey));
        }
    }

    /**
     * Cancel the current turn and clear the inbox.
     */
    public synchronized void cancel() {
        identityReflection.close();
        synchronized (inboxLock) {
            inbox.clear();
        }
        // Invalidate the response currently being generated and any tool call
        // that is still queued for the server thread. An action that has already
        // entered vanilla code cannot be rolled back generically, but no stale
        // response is allowed to start new work after this point.
        executionOwner.revokeAll(executionUnavailable(), eventGeneration::incrementAndGet);
        Thread activeCall = activeLLMCallThread.get();
        if (activeCall != null) activeCall.interrupt();
    }

    /** Suspend new turns while preserving events that arrive during the pause. */
    public synchronized void pause() {
        suspended = true;
        cancel();
    }

    /** Resume from a lifecycle pause and process the accumulated state once. */
    public synchronized void resume(String reason) {
        if (!suspended) {
            wake(reason);
            return;
        }
        suspended = false;
        wake(reason);
    }

    /** Is a turn currently in progress? */
    public boolean isInProgress() { return inProgress; }

    // ── Getters for persistence ──

    public String getProviderId() { return providerId; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    public double getTemperature() { return temperature; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String effort) { this.reasoningEffort = effort; }

    // ── Cognitive subsystem accessors (for external hooks) ──

    public EmotionState emotion() { return emotion; }
    public PlaceEventMemory placeMemory() { return placeMemory; }
    public com.mineagent.engine.memory.CognitiveMap cognitiveMap() { return cognitiveMap; }
    public SkillLibrary skillLibrary() { return skillLib; }
    public TheoryOfMind theoryOfMind() { return theoryOfMind; }
    public PlanGraph planner() { return planner; }
    public PlanGraph planGraph() { return planner; }
    public BeliefState beliefState() { return beliefState; }
    public WorldAssetIndex worldAssetIndex() { return worldAssetIndex; }
    public ExperienceStore experienceStore() { return experienceStore; }
    public ReflectionSystem reflection() { return reflection; }
    public PersonaProfile persona() { return persona; }
    public EvolvingIdentity identity() { return identity; }
    public synchronized String identityMemory(com.google.gson.JsonObject args) {
        if (suspended) identityReflection.close();
        return identityMemoryAccess.execute(args, RuntimeLanguage.language().id());
    }
    public EvolvingIdentity.Entry reviseIdentity(String kind, String subject, String statement,
            String reason, List<String> evidenceIds, double confidence, long expectedRevision,
            String reconsideration) {
        if (suspended) throw new IllegalArgumentException("reflection_closed_or_stale");
        return identityReflection.revise(eventGeneration.get(), kind, subject, statement, reason,
                evidenceIds, confidence, expectedRevision, reconsideration);
    }
    public MinecraftKnowledgeGraph knowledgeGraph() { return knowledgeGraph; }
    public DecisionCache decisionCache() { return decisionCache; }
    public CognitiveState cognitiveState() { return cognitiveState; }

    /** Return a point-in-time snapshot for diagnostics and replay reports. */
    public CognitionMetrics.Snapshot metricsSnapshot() {
        return metrics.snapshot();
    }
    public RealtimeCognition realtimeCognition() { return realtimeCognition; }
    public SemanticWorldModel semanticWorldModel() { return semanticWorldModel; }
    public ActionTransactionLedger actionLedger() { return actionLedger; }
    public LayeredMemory layeredMemory() { return layeredMemory; }
    public ResourceReservationLedger reservationLedger() { return reservationLedger; }
    public HierarchicalRollingPlanner rollingPlanner() { return rollingPlanner; }
    public MechanismExplorer mechanismExplorer() { return mechanismExplorer; }
    public SkillRuntime skillRuntime() { return skillRuntime; }

    /** Admit a learned sequence as one plan-level action, not N unrelated tasks. */
    public SkillRuntime.StartResult startSkill(String skillName,
                                               com.google.gson.JsonObject overrides) {
        long ownerRevision = executionOwner.revision();
        if (suspended || !ExecutionToken.active()) {
            return new SkillRuntime.StartResult(false, null, executionUnavailable());
        }
        long gameTick = currentGameTickSafe();
        LiveBodyState body = liveBodyState.get();
        if (body != null && body.taskId() != null) {
            return new SkillRuntime.StartResult(false, null,
                    text("Body is busy with task ", "O corpo está ocupado com a tarefa ") + body.taskId());
        }
        SkillRuntime.StartResult result = skillRuntime.start(
                skillName, overrides, gameTick);
        if (result.accepted()) {
            ExecutionOwner.Lease lease = executionOwner.open(result.runId(),
                    () -> !suspended && executionOwner.revision() == ownerRevision,
                    Long.MAX_VALUE, () -> cancelOwnedSkillNow(result.runId()));
            activeSkillLease = lease;
            if (!lease.active() || !ExecutionToken.active()) {
                lease.requestCancellation();
                return new SkillRuntime.StartResult(false, result.runId(), executionUnavailable());
            }
            PlanGraph.PlanNode current = planner.nodeForAction();
            boolean hadPlan = planner.hasActivePlan();
            IntentContract contract = current == null
                    ? IntentContract.generic(text("Execute learned skill ", "Execute a habilidade aprendida ") + skillName,
                    text("Every skill step and declared effect is verified", "Cada etapa da habilidade e efeito declarado é verificado"), null, null, null)
                    : IntentContract.generic(current.description(),
                    current.successCriterion(), null, null, null);
            planner.bindTask(result.runId(), "execute_skill", contract, gameTick);
            if (!hadPlan) rollingPlanner.onPlanReplaced(contract.goal(), gameTick);
            rollingPlanner.onTaskAccepted(result.runId(), "execute_skill",
                    TaskSnapshot.running("skill", result.message()), gameTick);
            semanticWorldModel.observe("skill:" + result.runId(), "status",
                    "running", null, 1.0, "skill_runtime", result.runId(),
                    gameTick, 1_200L, false);
        }
        return result;
    }

    /** Skill steps use the same admission, terminal publication and revocation protocol as direct tools. */
    private SkillRuntime.DispatchResult dispatchSkillAction(
            String actionId, String toolName, com.google.gson.JsonObject arguments) {
        Optional<Tool> toolValue = ToolRegistry.get(toolName);
        if (toolValue.isEmpty()) {
            return new SkillRuntime.DispatchResult(false, false, false,
                    text("Stored tool is no longer registered: ", "A ferramenta armazenada não está mais registrada: ") + toolName);
        }
        Tool tool = toolValue.get();
        ExecutionOwner.Lease skillLease = activeSkillLease;
        boolean ownedSkillAction = skillRuntime.ownsAction(actionId);
        if (suspended || (ownedSkillAction && (skillLease == null || !skillLease.active()))) {
            return new SkillRuntime.DispatchResult(false, tool.dispatchesAsyncTask(), false, executionUnavailable(),
                    SkillRuntime.Disposition.CANCELLED);
        }
        LiveBodyState body = liveBodyState.get();
        if (tool.dispatchesAsyncTask() && body != null && body.taskId() != null) {
            return new SkillRuntime.DispatchResult(false, true, false,
                    text("Body is busy with task ", "O corpo está ocupado com a tarefa ") + body.taskId());
        }
        ToolExecution execution = prepareExecution(actionId, tool, eventGeneration.get(),
                () -> !ownedSkillAction || (skillLease != null && skillLease.active()));
        if (ownedSkillAction && tool.dispatchesAsyncTask()) skillTaskExecutions.put(actionId, execution);
        dispatchExecution(execution, tool, arguments);
        // SkillRuntime's dispatcher is deliberately immediate. A tool that defers its admission
        // callback is revoked here; its eventual real-world receipt is reconciled separately.
        execution.call().requireImmediate(executionUnavailable());
        if (execution.call().terminal().state() == ExecutionProtocol.State.EXPIRED) {
            execution.lease().requestCancellation();
            markExecutionUnverified(execution);
        }
        String raw = execution.call().terminal().payload();
        boolean completed = execution.call().terminal().state() == ExecutionProtocol.State.COMPLETED;
        boolean asynchronous = completed && "async_pending".equals(analyzeFailure(raw).errorType());
        // Definitive non-admission has no future scheduler receipt to release retained authority.
        if (completed && !asynchronous) skillTaskExecutions.remove(actionId, execution);
        boolean success = completed && execution.verified().get();
        return completed ? new SkillRuntime.DispatchResult(success, asynchronous, success, raw)
                : new SkillRuntime.DispatchResult(false, false, false, raw, SkillRuntime.Disposition.UNVERIFIED);
    }

    /** Revocation is immediate; exact task cleanup always executes later on the server. */
    private void cancelSkillAction(String actionId) {
        if (actionId == null || actionId.isBlank()) return;
        if (!executionOwner.revoke(actionId, executionUnavailable())) {
            Services.platform().scheduleOnServer(() -> cancelOwnedTaskNow(actionId));
        }
    }

    /**
     * Controlled exploration can attempt compensation only through the same validated
     * tool dispatcher used by learned skills.  JSON is parsed structurally and
     * asynchronous compensation is reported as unverified rather than being
     * falsely treated as an atomic rollback. A successful result proves that
     * the compensation tool ran, not that arbitrary mod state is transactional.
     */
    private MechanismExplorer.CompensationResult dispatchExplorationCompensation(
            String actionId, String toolName, String arguments) {
        com.google.gson.JsonObject parsed;
        try {
            var element = com.google.gson.JsonParser.parseString(arguments);
            if (!element.isJsonObject()) {
                return new MechanismExplorer.CompensationResult(false, false,
                        false, text("Compensation arguments are not a JSON object", "Os argumentos de compensação não são um objeto JSON"));
            }
            parsed = element.getAsJsonObject();
        } catch (RuntimeException malformed) {
            return new MechanismExplorer.CompensationResult(false, false,
                    false, text("Invalid compensation arguments: ", "Argumentos de compensação inválidos: ") + malformed.getMessage());
        }
        SkillRuntime.DispatchResult result = dispatchSkillAction(actionId,
                toolName, parsed);
        return new MechanismExplorer.CompensationResult(result.accepted(),
                result.asynchronous(), result.success(), result.evidence());
    }

    private void cancelOwnedSkillNow(String runId) {
        if (Objects.equals(skillRuntime.snapshot().runId(), runId) && skillRuntime.active()) {
            skillRuntime.cancel(text("Execution revoked", "Execução revogada"), currentGameTickSafe());
        }
    }

    private void onSkillRuntimeCompleted(SkillRuntime.Snapshot snapshot) {
        executionOwner.release(snapshot.runId());
        long gameTick = snapshot.updatedTick();
        boolean success = snapshot.status() == SkillRuntime.Status.SUCCEEDED;
        TaskState taskState = success ? TaskState.SUCCESS
                : snapshot.status() == SkillRuntime.Status.CANCELLED
                ? TaskState.CANCELLED : TaskState.FAILED;
        TaskSnapshot evidence = TaskSnapshot.progress("skill_"
                        + snapshot.status().name().toLowerCase(Locale.ROOT),
                snapshot.lastEvidence(), snapshot.stepIndex(), snapshot.stepCount(),
                null, null, null,
                success ? null : snapshot.lastEvidence(), snapshot.lastEvidence(),
                snapshot.stepIndex());
        planner.recordOutcome(snapshot.runId(), taskState, evidence,
                snapshot.lastEvidence(), gameTick);
        rollingPlanner.onTaskFinished(snapshot.runId(), taskState, gameTick);
        semanticWorldModel.observe("skill:" + snapshot.runId(), "status",
                snapshot.status().name().toLowerCase(Locale.ROOT), null, 1.0,
                "skill_runtime", snapshot.runId(), gameTick, 2_400L, false);
        if (snapshot.disposition().learnable()) {
            semanticWorldModel.recordOutcome("execute_skill", success,
                    snapshot.skillName() + ": " + snapshot.lastEvidence(),
                    snapshot.runId(), gameTick, true);
            if (snapshot.failureKind() == SkillRuntime.FailureKind.POSTCONDITION) {
                // The adapter API consumes a stable technical reason, never quoted/localized evidence.
                mechanismExplorer.onAdapterOutcome(snapshot.skillName(), success, "postcondition", gameTick);
            }
        }
        onBodyLog("[SKILL_FINISHED] run_id=" + snapshot.runId()
                + " skill=" + snapshot.skillName() + " state=" + snapshot.status()
                + " evidence=" + snapshot.lastEvidence());
    }

    /** Expose validated specialized tools until the current turn finishes. */
    public void exposeExtraTools(Collection<String> toolNames) {
        if (toolNames == null) return;
        for (String name : toolNames) {
            if (name != null && !name.isBlank() && ToolRegistry.get(name).isPresent()) {
                exposedExtraTools.add(name);
            }
        }
    }


    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) return false;
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    public static boolean isCoreTool(String name) {
        return name != null && CORE_TOOL_NAMES.contains(name);
    }

    // ── Turn execution ─────────────────────────────────────────────

    private void startTurn(String trigger) {
        inProgress = true;
        try {
            executor.submit(() -> {
                try {
                    // Rate-limited memory auto-save (max once per minute
                    // internally). Runs on the loop executor thread — NOT the
                    // server thread — so file IO never causes game lag.
                    if (persistence != null) {
                        persistence.autoSave();
                    }
                    executeTurn(trigger, 0);
                } catch (Exception e) {
                    // Log error but don't crash the loop
                    System.err.println("[MineAgent] Turn error: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    synchronized (this) {
                        identityReflection.close();
                        inProgress = false;
                        // Check if inbox accumulated during this turn. While
                        // suspended it must remain queued until resume();
                        // draining here would either lose death events or start
                        // a tool-producing turn for a dead companion.
                        if (suspended) return;
                        boolean hasPendingEvents;
                        synchronized (inboxLock) {
                            hasPendingEvents = !inbox.isEmpty();
                        }
                        if (hasPendingEvents) {
                            // Do not drain here. executeTurn() is the sole
                            // inbox consumer and converts the retained batch
                            // into a user-role [EVENTS] message. The previous
                            // code copied and cleared the queue before calling
                            // startTurn(), so body observations arriving during
                            // an LLM call were silently lost on the next turn.
                            startTurn("inbox_batch");
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Executor was shut down (or queue saturated) — submit() rejected
            // the task synchronously. The lambda above never runs, so its
            // finally block will NOT reset inProgress. We must reset it here
            // to avoid permanently locking the loop (inProgress stuck true →
            // all future wake() calls queue into inbox and never execute).
            synchronized (this) {
                inProgress = false;
            }
            System.err.println("[MineAgent] Turn rejected by executor (shutting down?): "
                    + e.getMessage());
        }
    }

    /**
     * Execute a single LLM turn.
     *
     * @param trigger     what woke the loop
     * @param roundNumber how many tool-call rounds have executed (for recursion cap)
     */
    private void executeTurn(String trigger, int roundNumber) {
        // 0. Safety cap on tool-call rounds
        if (roundNumber >= MAX_TOOL_ROUNDS) {
            System.err.println("[MineAgent] Reached max tool rounds ("
                    + MAX_TOOL_ROUNDS + ") - stopping turn to prevent infinite loop");
            synchronized (history) {
                history.add(ChatMessage.system(
                        text("The tool-round budget is exhausted. Report only verified progress and remaining uncertainty; do not execute more tools.",
                                "O limite de rodadas de ferramentas foi atingido. Relate apenas progresso verificado e incertezas restantes; não execute mais ferramentas.")));
            }
            // Final LLM call to wrap up
            callLLMAndHandleResponse(roundNumber);
            return;
        }

        // 1. Drain inbox into the conversation
        drainInbox();

        // 2. Tick emotion state (coarse-grained decay per turn)
        emotion.tick();

        // 3. Reset per-turn remediation state. Dynamic cognition is appended
        //    transiently at the end of the provider request; history[0] stays
        //    byte-for-byte stable for prompt-cache reuse.
        if (roundNumber == 0) {
            // Reset remediation counter at the start of each turn
            remediationRoundCounter = 0;
            LiveBodyState body = liveBodyState.get();
            synchronized (this) {
                identityReflection.close();
                if (!suspended) identityReflection.open(liveAssetGameTick.get(), eventGeneration.get(),
                        body != null && body.taskId() != null);
            }
            // Extra schemas stay stable for the entire owner-goal episode.
            // Clearing them at every task_finished turn forced repeated
            // query_extra_tools calls and invalidated the provider tool prefix.
        }

        // 4. Trim history to prevent context overflow
        trimHistory();

        // Call LLM and handle the response (with retry)
        callLLMAndHandleResponse(roundNumber);
    }

    /**
     * Call the LLM, add the response to history, and execute any tool calls.
     * If all tools are synchronous, recursively continues the turn.
     */
    private void callLLMAndHandleResponse(int roundNumber) {
        LLMProvider provider = resolveProvider();
        List<Map<String, Object>> toolDefs = buildToolDefinitions();

        // Every admitted decision uses current evidence, not cached dialogue.

        // 3b. Call the LLM (with retry on transient errors)
        VersionedResponse versioned = callLLMWithRetry(provider, toolDefs, roundNumber);
        if (versioned == null) {
            // All retries exhausted - give up gracefully
            return;
        }
        LLMResponse response = versioned.response();
        long acceptedGeneration = versioned.generation();
        if (eventGeneration.get() != acceptedGeneration) {
            return;
        }

        if (response.choice() == null || response.choice().message() == null) {
            System.err.println("[MineAgent] Provider returned a response without a choice/message");
            return;
        }
        ChatMessage assistantMsg = normalizeAssistantMessage(response.choice().message());
        // Skip empty assistant messages (no content AND no tool_calls).
        // Some providers occasionally return a no-op response; adding it to
        // history wastes tokens and can confuse subsequent rounds (the LLM
        // sees its own empty message and may echo the pattern). Bail out
        // early instead — there is nothing to speak, cache, or execute.
        boolean hasContent = assistantMsg.content() != null
                && !assistantMsg.content().isBlank();
        boolean hasToolCalls = assistantMsg.toolCalls() != null
                && !assistantMsg.toolCalls().isEmpty();
        if (!hasContent && !hasToolCalls) {
            System.err.println("[MineAgent] Empty assistant response "
                    + "(no content, no tool_calls) — skipping, not added to history");
            return;
        }

        // The final wrap-up request is deliberately not allowed to execute
        // more tools. Do not store the provider's rejected tool_calls: keeping
        // them without tool result messages makes the next provider request
        // protocol-invalid. Preserve only optional explanatory text.
        if (roundNumber >= MAX_TOOL_ROUNDS && hasToolCalls) {
            System.err.println("[MineAgent] LLM returned tool_calls after hitting "
                    + "MAX_TOOL_ROUNDS cap (" + MAX_TOOL_ROUNDS + ") - ignoring them");
            if (hasContent) {
                ChatMessage textOnly = ChatMessage.assistant(assistantMsg.content());
                synchronized (history) {
                    history.add(textOnly);
                    advanceProviderHistoryCursor(provider);
                }
                speakToOwner(assistantMsg.content());
            }
            return;
        }
        synchronized (history) {
            history.add(assistantMsg);
            advanceProviderHistoryCursor(provider);
        }

        // Speak text to the owner in-game
        if (assistantMsg.content() != null && !assistantMsg.content().isBlank()) {
            speakToOwner(assistantMsg.content());
        }

        // Ordinary text-only conversation is complete without special markup or another model call.
        if (assistantMsg.toolCalls() == null || assistantMsg.toolCalls().isEmpty()) return;

        // 5. Execute tool calls
        List<ChatMessage> toolResults = executeToolCalls(
                assistantMsg.toolCalls(), acceptedGeneration);
        synchronized (history) {
            history.addAll(toolResults);
        }

        // Tool callbacks are executor evidence even if a newer owner event
        // makes the language-model response stale. Commit plan transitions
        // before deciding whether this turn may recurse.
        commitSynchronousPlanEvidence(assistantMsg.toolCalls(), toolResults);

        // A cancel/new event that arrived while tools were running owns the
        // next decision. Keep the result messages to complete the provider
        // protocol group, but never recurse using the obsolete turn.
        if (eventGeneration.get() != acceptedGeneration) {
            return;
        }

        // 5b. Cognitive feedback is applied only to the still-current turn;
        // semantic and plan evidence above remain valid independently.
        analyzeToolResults(assistantMsg.toolCalls(), toolResults);

        // 6. Check if any async task was dispatched - if not, loop back
        boolean anyAsync = toolResults.stream()
                .anyMatch(m -> m.content() != null && m.content().contains("\"async\":true"));

        if (!anyAsync) {
            // All sync tools - loop back with incremented round
            executeTurn("tool_results_sync", roundNumber + 1);
        }
        // If async tasks were dispatched, they'll wake the loop when done
    }

    private void commitSynchronousPlanEvidence(
            List<ChatMessage.ToolCallRef> toolCalls,
            List<ChatMessage> toolResults) {
        for (int index = 0; index < toolCalls.size()
                && index < toolResults.size(); index++) {
            ChatMessage.ToolCallRef call = toolCalls.get(index);
            String content = toolResults.get(index).content();
            if (content == null || !SKILL_ACTION_TOOLS.contains(call.name())) continue;
            FailureAnalysis analysis = analyzeFailure(content);
            if ("async_pending".equals(analysis.errorType())
                    || (!analysis.isSuccess() && !analysis.isFailure())) continue;
            long gameTick = currentGameTickSafe();
            if (planner.recordToolOutcome(call.id(), call.name(),
                    analysis.isSuccess(), content, gameTick)) {
                rollingPlanner.onSynchronousOutcome(analysis.isSuccess(), gameTick);
            }
        }
    }

    /**
     * Analyze tool execution results to update cognitive subsystems:
     * <ul>
     *   <li>Emotion: trigger joy on success, frustration on failure</li>
     *   <li>Reflection: record failures for learning</li>
     *   <li>Skill library: register successful action sequences</li>
     * </ul>
     *
     * <p><b>Improvements:</b>
     * <ul>
     *   <li>More accurate failure detection: combine multiple signals instead of simple keywords</li>
     *   <li>Structured error analysis: extract the error type and context</li>
     *   <li>Improved skill registration: generate more stable skill IDs</li>
     * </ul>
     */
    private void analyzeToolResults(List<ChatMessage.ToolCallRef> toolCalls,
                                      List<ChatMessage> toolResults) {
        boolean anyFailure = false;
        boolean anySuccess = false;
        int failureCount = 0;
        int successCount = 0;
        PlanGraph.State tracePlan = planner.exportState();
        PlanGraph.PlanNode traceNode = planner.currentNode();
        String traceGoal = currentGoalContext(tracePlan, traceNode);

        // Capture PAD values before emotional changes to learn event importance.
        float pleasureBefore = emotion.pleasure();
        float arousalBefore = emotion.arousal();

        for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
            var tc = toolCalls.get(i);
            var result = toolResults.get(i);
            String content = result.content();
            if (content == null) continue;

            // Improved failure detection by combining multiple signals.
            FailureAnalysis analysis = analyzeFailure(content);
            boolean failed = analysis.isFailure();
            boolean succeeded = analysis.isSuccess();
            boolean asyncPending = "async_pending".equals(analysis.errorType());
            if (asyncPending
                    && SKILL_ACTION_TOOLS.contains(tc.name())) {
                pendingTaskActions.put(tc.id(),
                        new PendingSkillAction(currentTaskDescription(), tc));
            }

            // Check whether the LLM annotated an importance field in the result.
            String importanceLabel = extractImportanceLabel(content);
            if (importanceLabel != null) {
                importance.learnFromLLMAnnotation(tc.name(), content, importanceLabel);
            }

            if (failed) {
                anyFailure = true;
                failureCount++;
                // Learn importance from failures.
                importance.learnFromFailure(tc.name(), content);
                // Record failure in reflection system (micro layer)
                String taskDesc = planner.hasActivePlan() && planner.currentNode() != null
                        ? planner.currentNode().description() : "task";
                String failReason = analysis.errorMessage();
                reflection.recordFailure(taskDesc, tc.name(), failReason);
                // Record full failed-task memory (JARVIS-1 style) so the
                // LLM can recall this failure next time a similar task is
                // attempted, avoiding repeated mistakes.
                String attemptedPlan = tc.name() + "(" + tc.arguments() + ")";
                reflection.recordFailedTask(taskDesc, attemptedPlan, failReason);
            } else if (succeeded) {
                anySuccess = true;
                successCount++;
            }
        }

        // Emotion feedback based on the balance of successes and failures.
        if (failureCount > successCount) {
            emotion.onTaskFailed();
        } else if (successCount > 0) {
            emotion.onTaskComplete();
        }

        // Learn importance from emotional changes.
        float pleasureDelta = Math.abs(emotion.pleasure() - pleasureBefore);
        float arousalDelta = Math.abs(emotion.arousal() - arousalBefore);
        float emotionDelta = Math.max(pleasureDelta, arousalDelta);
        if (emotionDelta > 0.05f && !toolCalls.isEmpty()) {
            var firstCall = toolCalls.get(0);
            var firstResult = toolResults.get(0);
            if (firstResult.content() != null) {
                // Negative emotion (falling pleasure) passes a negative delta so ImportanceEvaluator raises the weight more.
                float signedDelta = emotion.pleasure() - pleasureBefore;
                importance.learnFromEmotion(firstCall.name(), firstResult.content(), signedDelta);
            }
        }

        // Improved skill registration with more stable skill IDs.
        if (anySuccess && !toolCalls.isEmpty()) {
            // Collect all successful tool calls.
            List<ChatMessage.ToolCallRef> successfulCalls = new ArrayList<>();
            for (int i = 0; i < toolCalls.size() && i < toolResults.size(); i++) {
                var tc = toolCalls.get(i);
                var result = toolResults.get(i);
                if (result.content() != null && analyzeFailure(result.content()).isSuccess()) {
                    successfulCalls.add(tc);
                }
            }

            boolean containsAction = successfulCalls.stream()
                    .anyMatch(call -> SKILL_ACTION_TOOLS.contains(call.name()));
            List<ChatMessage.ToolCallRef> replayCalls = successfulCalls.stream()
                    .filter(call -> SKILL_TRACE_TOOLS.contains(call.name())).toList();
            if (containsAction && !replayCalls.isEmpty()) {
                appendVerifiedTrace(traceGoal, replayCalls, !anyFailure,
                        !planner.hasActivePlan());
            }
        }
        if (anyFailure) {
            appendVerifiedTrace(traceGoal, List.of(), false, false);
        }
    }

    /** Build one skill from a verified episode instead of isolated responses. */
    private void appendVerifiedTrace(String goal,
                                     List<ChatMessage.ToolCallRef> calls,
                                     boolean success, boolean episodeComplete) {
        synchronized (verifiedTraceLock) {
            String normalizedGoal = goal == null || goal.isBlank()
                    ? "general_task" : goal.trim();
            if (!verifiedTraceGoal.equals(normalizedGoal)) {
                verifiedActionTrace.clear();
                verifiedTraceGoal = normalizedGoal;
            }
            if (!success) {
                // A failed episode is useful reflection evidence, but replaying
                // its partial prefix as a skill would encode an unsafe plan.
                verifiedActionTrace.clear();
                return;
            }
            for (ChatMessage.ToolCallRef call : calls) {
                if (call != null && SKILL_TRACE_TOOLS.contains(call.name())
                        && verifiedActionTrace.size() < 24) {
                    verifiedActionTrace.add(call);
                }
            }
            if (!episodeComplete || verifiedActionTrace.isEmpty()
                    || verifiedActionTrace.stream().noneMatch(
                    call -> SKILL_ACTION_TOOLS.contains(call.name()))) return;
            List<ChatMessage.ToolCallRef> verified = List.copyOf(verifiedActionTrace);
            List<String> toolNames = verified.stream()
                    .map(ChatMessage.ToolCallRef::name).toList();
            String skillId = generateSkillId(normalizedGoal,
                    toolNames.getFirst(), toolNames);
            skillLib.registerSequence(skillId, normalizedGoal, normalizedGoal,
                    toolTraceJson(verified), true);
            verifiedActionTrace.clear();
        }
    }

    private String currentTaskDescription() {
        return currentGoalContext(planner.exportState(), planner.currentNode());
    }

    /** Objectives come from deliberated plans, never the mere arrival of speech. */
    private String currentGoalContext(PlanGraph.State plan, PlanGraph.PlanNode node) {
        if (plan != null && plan.goal() != null && !plan.goal().isBlank()) return plan.goal();
        if (node != null && node.description() != null && !node.description().isBlank()) {
            return node.description();
        }
        return text("current task", "tarefa atual");
    }

    /** Preserve parameters so a learned sequence is adaptable, not a name list. */
    private static String toolTraceJson(List<ChatMessage.ToolCallRef> calls) {
        com.google.gson.JsonArray trace = new com.google.gson.JsonArray();
        for (ChatMessage.ToolCallRef call : calls) {
            com.google.gson.JsonObject action = new com.google.gson.JsonObject();
            action.addProperty("tool", call.name());
            try {
                action.add("args", com.google.gson.JsonParser.parseString(call.arguments()));
            } catch (com.google.gson.JsonParseException malformed) {
                // Provider arguments should be JSON, but retaining malformed
                // text is safer than dropping evidence or failing the turn.
                action.addProperty("args_raw", call.arguments());
            }
            trace.add(action);
        }
        return trace.toString();
    }

    /**
     * Failure analysis result.
     */
    private record FailureAnalysis(
            boolean isFailure,
            boolean isSuccess,
            String errorMessage,
            String errorType
    ) {}

    /**
     * Analyze tool results for success or failure by combining multiple signals.
     */
    private static FailureAnalysis analyzeFailure(String content) {
        if (content == null) {
            return new FailureAnalysis(true, false, text("null content", "conteúdo nulo"), "unknown");
        }

        // Revocation/deadline replies are execution uncertainty, not observations of a failed
        // physical action. Do not feed synthetic timeouts into plan verification or learned failure memory.
        try {
            var json = com.google.gson.JsonParser.parseString(content);
            if (json.isJsonObject()) {
                var code = json.getAsJsonObject().get("code");
                if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()
                        && ("tool_cancelled".equals(code.getAsString()) || "tool_timeout".equals(code.getAsString()))) {
                    return new FailureAnalysis(false, false, extractErrorMessage(content), "execution_unverified");
                }
            }
        } catch (RuntimeException notStructured) {
            // Legacy tools can still return ordinary prose.
        }
        String lower = content.toLowerCase();

        // An async dispatch acknowledgment means the task just started; its terminal state is unknown.
        // Count neither success nor failure: dispatch success must not be mistaken for execution success,
        // which would pollute learned skills with unverified sequences and distort emotional feedback.
        // The later task_finished event records the actual asynchronous outcome.
        boolean isAsyncDispatch = lower.contains("\"async\":true") ||
                                  lower.contains("\"async\":1");

        // Explicit success signals (high priority).
        boolean hasSuccessFlag = lower.contains("\"success\":true") ||
                                  lower.contains("\"success\":1") ||
                                  lower.contains("\"status\":\"success\"") ||
                                  lower.contains("\"result\":\"ok\"");

        // Failure signals.
        boolean hasErrorFlag = lower.contains("\"error\"") ||
                                lower.contains("\"failed\"") ||
                                lower.contains("\"status\":\"error\"") ||
                                lower.contains("\"status\":\"failed\"");

        // Timeout signals.
        boolean hasTimeout = lower.contains("timed out") ||
                             lower.contains("timeout") ||
                             lower.contains("deadline exceeded");

        // Missing-target signals.
        boolean hasNotFound = lower.contains("not found") ||
                               lower.contains("no such") ||
                               lower.contains("cannot find") ||
                               lower.contains("doesn't exist");

        // Decision order:
        // 0. Async dispatch acknowledgment without failure -> unknown (neither success nor failure).
        // 1. Explicit success without a failure signal -> success.
        // 2. Explicit failure signal -> failure.
        // 3. Timeout or missing target without success -> failure.
        // 4. Otherwise assume success when no explicit failure signal exists.

        if (isAsyncDispatch && !hasErrorFlag && !hasTimeout) {
            return new FailureAnalysis(false, false, "", "async_pending");
        }

        if (hasSuccessFlag && !hasErrorFlag && !hasTimeout && !hasNotFound) {
            return new FailureAnalysis(false, true, "", "success");
        }

        if (hasErrorFlag) {
            String errorMsg = extractErrorMessage(content);
            String errorType = categorizeError(lower);
            return new FailureAnalysis(true, false, errorMsg, errorType);
        }

        if (hasTimeout) {
            return new FailureAnalysis(true, false, text("Operation timed out", "A operação excedeu o prazo"), "timeout");
        }

        if (hasNotFound && !hasSuccessFlag) {
            return new FailureAnalysis(true, false, text("Target not found", "Alvo não encontrado"), "not_found");
        }

        // Default to success.
        return new FailureAnalysis(false, true, "", "success");
    }

    /**
     * Categorize errors using general patterns rather than task-specific keywords.
     */
    private static String categorizeError(String lowerContent) {
        if (lowerContent.contains("invalid") || lowerContent.contains("illegal") ||
            lowerContent.contains("missing") || lowerContent.contains("required")) {
            return "invalid_params";
        }
        if (lowerContent.contains("cannot") || lowerContent.contains("can't") ||
            lowerContent.contains("denied") || lowerContent.contains("forbidden")) {
            return "access_denied";
        }
        if (lowerContent.contains("timeout") || lowerContent.contains("timed out")) {
            return "timeout";
        }
        if (lowerContent.contains("not found") || lowerContent.contains("no such")) {
            return "not_found";
        }
        return "unknown";
    }

    /**
     * Generate stable skill IDs with a tool-sequence signature so different tasks do not overwrite one another.
     */
    private String generateSkillId(String taskDesc, String primaryTool, List<String> toolSequence) {
        // Use the task type and tool name to generate stable IDs and avoid hashCode collisions.
        String normalizedTask = taskDesc.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "_")
                .replaceAll("_+", "_");
        if (normalizedTask.length() > 20) {
            normalizedTask = normalizedTask.substring(0, 20);
        }
        // Tool-sequence signature: identical sequences identify the same skill and merge statistics;
        // different sequences identify different skills and must not overwrite one another.
        String seqSignature = String.join("->", toolSequence);
        String seqHash = Integer.toHexString(seqSignature.hashCode());
        return primaryTool + "_" + normalizedTask + "_" + seqHash;
    }

    /**
     * Extract the LLM-annotated importance field from a tool-result JSON object.
     * The LLM may include "importance": "high"/"medium"/"low" in tool-call arguments
     * to teach the system which events matter.
     * Returns null when no annotation is present.
     */
    private static String extractImportanceLabel(String jsonContent) {
        if (jsonContent == null) return null;
        String lower = jsonContent.toLowerCase();
        int idx = lower.indexOf("\"importance\"");
        if (idx < 0) return null;
        int colon = jsonContent.indexOf(':', idx);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < jsonContent.length()
                && (jsonContent.charAt(start) == ' ' || jsonContent.charAt(start) == '"')) {
            start++;
        }
        int end = jsonContent.indexOf('"', start);
        if (end < 0) end = jsonContent.length();
        return jsonContent.substring(start, end);
    }

    /** Extract a human-readable error message from a tool result JSON. */
    private static String extractErrorMessage(String jsonContent) {
        if (jsonContent == null) return text("unknown error", "erro desconhecido");
        // Simple extraction: find "error":"..." or "error":"..."
        int idx = jsonContent.indexOf("\"error\"");
        if (idx < 0) idx = jsonContent.indexOf("\"failed\"");
        if (idx < 0) return jsonContent.length() > 200
                ? jsonContent.substring(0, 200) : jsonContent;
        int colon = jsonContent.indexOf(':', idx);
        if (colon < 0) return jsonContent;
        int start = colon + 1;
        while (start < jsonContent.length()
                && (jsonContent.charAt(start) == ' ' || jsonContent.charAt(start) == '"')) {
            start++;
        }
        int end = jsonContent.indexOf('"', start);
        if (end < 0) end = jsonContent.length();
        return jsonContent.substring(start, end);
    }

    /**
     * Call the LLM with retry logic for transient errors and stale request detection.
     *
     * <p><b>Stale Request Detection</b> (borrowed from Mindcraft):
     * Before calling the LLM, record the current event generation.
     * After the LLM returns, check if a new event arrived during the call
     * (timestamp changed). If so, the response is stale — discard it and
     * return null, causing the caller to abort the turn. The inbox will
     * trigger a fresh turn with the new context.
     *
     * <p>This prevents the "AI answers a question from 10 seconds ago"
     * problem, and reduces latency for new messages by 5-10x.
     *
     * @return the LLM response, or null if all retries exhausted or stale
     */
    private record VersionedResponse(LLMResponse response, long generation) {}

    /** Emit provider-side latency and cache metrics for evidence-based tuning. */
    private void logUsage(LLMResponse response, long elapsedNanos, int attempt) {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, elapsedNanos));
        LLMResponse.Usage usage = response == null ? null : response.usage();
        if (usage == null) {
            System.out.println("[MineAgent] LLM usage unavailable latency_ms="
                    + latencyMs + " attempt=" + (attempt + 1));
            return;
        }
        System.out.println(String.format(Locale.ROOT,
                "[MineAgent] LLM usage prompt=%d cached=%d cache_create=%d "
                        + "cache_hit_rate=%.1f%% completion=%d total=%d latency_ms=%d attempt=%d",
                usage.promptTokens(), usage.cachedPromptTokens(),
                usage.cacheCreationPromptTokens(),
                usage.promptCacheHitRate() * 100.0,
                usage.completionTokens(), usage.totalTokens(), latencyMs, attempt + 1));
    }

    private VersionedResponse callLLMWithRetry(LLMProvider provider,
                                                List<Map<String, Object>> toolDefs,
                                                int roundNumber) {
        // The generation covers the snapshot, not merely the subsequent IO.
        // Speech arriving during live-context assembly must invalidate that old history too.
        long callGeneration = eventGeneration.get();
        refreshSystemPromptIfNeeded();
        List<ChatMessage> messagesToSend;
        List<ChatMessage> fallbackHistory;
        List<ConversationItem> transportItems;
        synchronized (history) {
            boolean statefulResponses = provider.capabilities().supports(
                    LLMCapabilities.Feature.STATEFUL_RESPONSES);
            String previous = providerTransportState.get().previousResponseId();
            int start = statefulResponses && previous != null
                    ? Math.max(0, Math.min(providerHistoryCursor, history.size())) : 0;
            messagesToSend = new ArrayList<>(history.subList(start, history.size()));
            // Keep a complete, locally valid history for relays that reject
            // Responses or for response ids that expired. The incremental
            // suffix above remains the normal stateful wire payload.
            fallbackHistory = new ArrayList<>(history);
            transportItems = opaqueTransportItems(providerTransportState.get());
        }
        // Every decision round receives current evidence and learned commitments,
        // including changes retained by a synchronous identity_memory call.
        // Keep this compact volatile tail out of durable dialogue and the stable prefix.
        ChatMessage live = ChatMessage.user(buildLiveContext());
        messagesToSend.add(live);
        fallbackHistory.add(live);
        messagesToSend = Collections.unmodifiableList(messagesToSend);


        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            ProviderSessionState providerStateBefore = providerTransportState.get();
            try {
                long requestStarted = System.nanoTime();
                Thread requestThread = Thread.currentThread();
                activeLLMCallThread.set(requestThread);
                if (eventGeneration.get() != callGeneration) {
                    activeLLMCallThread.compareAndSet(requestThread, null);
                    return null;
                }
                LLMResponse response;
                try {
                    // Provider instances are registry singletons, so their
                    // mutable wire session must be borrowed under a monitor.
                    // The authoritative copy remains per AgentLoop.
                    synchronized (provider) {
                        provider.restoreSessionState(providerStateBefore);
                        response = provider.complete(new LLMRequest(
                                baseUrl, apiKey, model, messagesToSend, fallbackHistory,
                                transportItems, toolDefs,
                                temperature, outputTokenBudget(roundNumber), reasoningEffort,
                                providerStateBefore.previousResponseId(), providerStateBefore.conversationId(),
                                stablePromptCacheKey(provider),
                                Duration.ofSeconds(providerDeadlineSeconds(roundNumber)),
                                provider.capabilities().supports(LLMCapabilities.Feature.SERVER_COMPACTION)));
                        providerTransportState.set(provider.sessionState(model));
                    }
                } finally {
                    activeLLMCallThread.compareAndSet(requestThread, null);
                }
                long elapsedNanos = System.nanoTime() - requestStarted;
                logUsage(response, elapsedNanos, attempt);
                metrics.recordLlm(response, elapsedNanos, false);

                // Stale Request check: if a significant event arrived during
                // the LLM call, discard the response. The situation it was
                // generated for no longer applies.
                if (eventGeneration.get() != callGeneration) {
                    // A discarded response must never become the parent of a
                    // later stateful request.  Provider implementations update
                    // their session as soon as HTTP parsing succeeds, so roll
                    // the transport state back before returning stale.
                    providerTransportState.set(providerStateBefore);
                    synchronized (provider) {
                        provider.restoreSessionState(providerStateBefore);
                    }
                    System.out.println("[MineAgent] Stale request detected — "
                            + "discarding LLM response (event arrived during call)");
                    return null;
                }

                if (persistence != null) {
                    // Provider transport state (response id and opaque
                    // compaction item) is durable but intentionally kept out
                    // of gameplay-memory search and prompts.
                    persistence.saveProviderSession(providerTransportState.get());
                }
                return new VersionedResponse(response, callGeneration);
            } catch (RuntimeException e) {
                // A provider is a registry singleton. If an implementation
                // mutated its session before failing, never expose that
                // partial state to another companion sharing the instance.
                synchronized (provider) {
                    provider.restoreSessionState(providerStateBefore);
                }
                providerTransportState.set(providerStateBefore);
                metrics.recordLlm(null, 0L, true);
                String msg = e.getMessage() != null ? e.getMessage() : "";
                System.err.println("[MineAgent] LLM call attempt " + (attempt + 1)
                        + "/" + MAX_RETRIES + " failed: " + msg);

                // Check stale on retry too
                if (eventGeneration.get() != callGeneration) {
                    // HttpClient.send propagates interruption. Consume that
                    // flag before this single-thread executor accepts the next,
                    // current request; generation remains cancellation truth.
                    Thread.interrupted();
                    System.out.println("[MineAgent] Stale request detected during retry — aborting");
                    return null;
                }

                boolean retryable = !(e instanceof LLMProviderException providerError)
                        || providerError.retryable();
                if (e instanceof IllegalArgumentException) {
                    retryable = false;
                }

                if (!retryable) {
                    speakToOwner(text("§cModel request failed: ", "§cA solicitação ao modelo falhou: ") + msg);
                    return null;
                }

                if (attempt < MAX_RETRIES - 1) {
                    long delay = RETRY_BASE_MS * (1L << attempt); // exponential backoff
                    if (e instanceof LLMProviderException providerError
                            && providerError.retryAfterMillis() != null) {
                        delay = Math.max(delay, providerError.retryAfterMillis());
                    }
                    if (!waitForRetry(delay, callGeneration)) {
                        return null;
                    }
                } else {
                    System.err.println("[MineAgent] All LLM retries exhausted");
                    // Notify the owner about the failure
                    speakToOwner(text("§cModel request failed after the retry limit: ", "§cA solicitação ao modelo falhou após o limite de tentativas: ") + msg);
                    return null;
                }
            }
        }
        return null;
    }

    /** Mark the accepted assistant response as already represented by a
     * Responses API previous_response_id. Tool outputs appended afterwards
     * remain the only continuation payload. */
    private void advanceProviderHistoryCursor(LLMProvider provider) {
        if (provider.capabilities().supports(LLMCapabilities.Feature.STATEFUL_RESPONSES)) {
            providerHistoryCursor = history.size();
        }
    }

    private static List<ConversationItem> opaqueTransportItems(
            ProviderSessionState state) {
        if (state == null || state.opaqueCompactionItem() == null
                || state.opaqueCompactionItem().isBlank()) return List.of();
        try {
            com.google.gson.JsonElement parsed =
                    com.google.gson.JsonParser.parseString(state.opaqueCompactionItem());
            if (!parsed.isJsonArray()) return List.of();
            List<ConversationItem> items = new ArrayList<>();
            for (com.google.gson.JsonElement value : parsed.getAsJsonArray()) {
                if (value.isJsonObject()) {
                    String type = value.getAsJsonObject().has("type")
                            ? value.getAsJsonObject().get("type").getAsString()
                            : "reasoning";
                    items.add(ConversationItem.opaque(type, value.toString()));
                }
            }
            return List.copyOf(items);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * Bound routine follow-up generations without constraining large build
     * coordinate payloads or the first strategic response. Reasoning models
     * still receive 4K tokens so their hidden reasoning is not starved.
     */
    private int outputTokenBudget(int roundNumber) {
        if (roundNumber <= 0 || maxTokens <= 4_096 || complexStructuredOutputLikely()) {
            return maxTokens;
        }
        return Math.min(maxTokens, 4_096);
    }

    private boolean complexStructuredOutputLikely() {
        String goal = planner.exportState().goal().toLowerCase(Locale.ROOT);
        return containsAny(goal, "build", "blueprint", "structure", "architect",
                "construction");
    }

    /** Sleep in short slices so a new owner event or cancel wakes the loop logically. */
    private boolean waitForRetry(long delayMillis, long callGeneration) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        while (eventGeneration.get() == callGeneration) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return true;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        remaining, TimeUnit.MILLISECONDS.toNanos(250)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Trim the conversation history to prevent context overflow.
     * Always keeps the system prompt (index 0) and trims the oldest
     * non-system messages when the history exceeds MAX_HISTORY.
     *
     * <p><b>Important:</b> When trimming, we must keep assistant messages
     * that contain {@code tool_calls} together with their corresponding
     * {@code tool} result messages. If we cut an assistant tool_calls message
     * but keep the following tool results, the OpenAI-compatible API will
     * reject the request with "Messages with role 'tool' must be a response
     * to a preceding message with 'tool_calls'".
     */
    private void trimHistory() {
        synchronized (history) {
            if (history.size() <= MAX_HISTORY + 1) return;

            // A Responses continuation has already stored the sent prefix on
            // the provider. Trim only that prefix while preserving the local
            // unsent suffix (tool outputs/owner events) and its cursor. The
            // ordinary trimmer can otherwise delete an unsent tool output or
            // move the cursor past it, silently losing the action result.
            if (providerTransportState.get().previousResponseId() != null
                    && providerHistoryCursor > 0) {
                trimStatefulHistory();
                return;
            }

            int oldSize = history.size();

            // Preserve the system prompt (index 0)
            ChatMessage systemPrompt = history.get(0);

            // Trim with hysteresis: trigger at MAX_HISTORY, then fall back to
            // RECENT_KEEP. Trimming back to MAX_HISTORY caused the rolling
            // summary near the front of the prompt to be rewritten after
            // almost every tool round, defeating provider prefix caches.
            int start = history.size() - RECENT_KEEP;

            // Adjust start forward if it lands on a "tool" message whose
            // corresponding assistant tool_calls message was cut.
            while (start < history.size()) {
                ChatMessage m = history.get(start);
                if ("tool".equals(m.role())) {
                    // This tool message's parent assistant message was cut;
                    // skip this orphaned tool message too.
                    start++;
                } else if ("assistant".equals(m.role())
                        && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    // This is an assistant message with tool_calls. Check if
                    // all its tool results are also within the kept range.
                    // If some results are missing (cut off), drop this assistant
                    // message too.
                    int needed = m.toolCalls().size();
                    int found = 0;
                    for (int i = start + 1; i < history.size() && found < needed; i++) {
                        ChatMessage nm = history.get(i);
                        if ("tool".equals(nm.role()) && m.toolCalls().stream()
                                .anyMatch(tc -> tc.id().equals(nm.toolCallId()))) {
                            found++;
                        } else if ("assistant".equals(nm.role())) {
                            // Reached next assistant — stop counting
                            break;
                        }
                    }
                    if (found < needed) {
                        // Some tool results missing — drop this assistant msg
                        start++;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }

            // ── Compress dropped messages into a summary ──
            // Instead of discarding old messages entirely, fold them into
            // a compact "[SUMMARY]" system message so the LLM retains
            // context without paying full token cost. Only summarize the
            // messages between index 1 (after system prompt) and `start`
            // that are being dropped.
            List<ChatMessage> trimmed = new ArrayList<>();
            trimmed.add(systemPrompt);

            if (start > 1) {
                String summary = summarizeOldMessages(1, start);
                if (!summary.isBlank()) {
                    trimmed.add(ChatMessage.system(text("Conversation summary. ", "Resumo da conversa. ") + summary));
                }
            }

            // Build the kept range, filtering out any orphan tool messages
            // whose parent assistant was cut (not in the kept range). The
            // while loop above only advances `start` past orphan tools that
            // land EXACTLY at the start position; it cannot remove orphans
            // that appear after a kept assistant's valid tool results, or
            // after a user/system message (where break leaves start at the
            // user message but start+1 may be an orphan tool). Filtering
            // here catches every case without breaking valid assistant→tool
            // pairings.
            List<ChatMessage> kept = new ArrayList<>();
            for (int i = start; i < history.size(); i++) {
                ChatMessage m = history.get(i);
                if ("tool".equals(m.role())
                        && !hasParentAssistantInRange(kept, m)) {
                    // Orphan tool message — its parent assistant was cut.
                    continue;
                }
                kept.add(m);
            }
            trimmed.addAll(kept);

            history.clear();
            history.addAll(trimmed);

            System.out.println("[MineAgent] Trimmed history from "
                    + oldSize + " to " + history.size() + " messages ("
                    + (start > 1 ? "summarized " + (start - 1) + " old msgs" : "no summary")
                    + ")");
        }
    }

    private void trimStatefulHistory() {
        int oldSize = history.size();
        int sentEnd = Math.max(1, Math.min(providerHistoryCursor, oldSize));
        int start = Math.max(1, sentEnd - RECENT_KEEP);
        // Keep the assistant parent whenever the boundary lands in its tool
        // result group. Those results may be unsent but are still needed for
        // local fallback to Chat Completions.
        while (start > 1 && "tool".equals(history.get(start).role())) start--;

        ChatMessage systemPrompt = history.get(0);
        List<ChatMessage> trimmed = new ArrayList<>();
        trimmed.add(systemPrompt);
        if (start > 1) {
            String summary = summarizeOldMessages(1, start);
            if (!summary.isBlank()) trimmed.add(ChatMessage.system(text("Conversation summary. ", "Resumo da conversa. ") + summary));
        }
        int keptPrefix = trimmed.size();
        // Keep the complete recent range, including any unsent suffix. Do not
        // run orphan filtering here: the provider-side previous response is
        // the parent for a tool output that is outside the local range.
        trimmed.addAll(history.subList(start, oldSize));
        history.clear();
        history.addAll(trimmed);

        // Cursor points immediately after the old sent prefix, before the
        // unsent suffix. The inserted summary and kept recent prefix are both
        // already represented by previous_response_id and must be skipped.
        providerHistoryCursor = keptPrefix + (sentEnd - start);
        System.out.println("[MineAgent] Trimmed stateful history from " + oldSize
                + " to " + history.size() + "; continuation cursor="
                + providerHistoryCursor);
    }

    /**
     * Summarize a range of old history messages into a compact string.
     * Extracts only the essential info: what tools were called, what the
     * outcomes were, and any key decisions. Each message becomes ~1 line.
     *
     * <p><b>Memory protection:</b> retain the first 200 characters of player instructions rather than 80,
     * so important task requirements are not cut off. Important events such as death, diamonds or danger
     * receive an [IMPORTANT] marker to direct the LLM's attention.
     *
     * @param fromIndex inclusive start index in history
     * @param toIndex exclusive end index in history
     * @return compact summary, or empty string if nothing to summarize
     */
    private String summarizeOldMessages(int fromIndex, int toIndex) {
        if (fromIndex >= toIndex) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = fromIndex; i < toIndex && count < 25; i++) {
            ChatMessage m = history.get(i);
            String role = m.role();
            String content = m.content();
            if (content == null) content = "";

            // Skip empty content (e.g. assistant messages with only tool_calls)
            if (content.isBlank() && m.toolCalls() == null) continue;

            // Evaluate importance dynamically with ImportanceEvaluator instead of hardcoded keywords.
            // Player instructions are always important: never discard what the user said.
            boolean important = false;
            if ("user".equals(role)) {
                important = true;
            } else if ("tool".equals(role) && i > 0) {
                // Find the corresponding tool name.
                String toolName = findToolNameForResult(history, i);
                important = importance.isImportant(toolName, content);
            } else {
                important = importance.isImportant("text", content);
            }

            if ("user".equals(role)) {
                // Keep 200 characters of player instructions, not 80, to preserve task requirements.
                String snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                sb.append(text("Earlier user-role message: ", "Mensagem anterior em papel de usuário: ")).append(snippet).append("; ");
                count++;
            } else if ("assistant".equals(role)) {
                // Assistant: if has tool_calls, list tool names; else first 100 chars of text
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    StringBuilder tools = new StringBuilder();
                    for (var tc : m.toolCalls()) {
                        if (tools.length() > 0) tools.append(",");
                        tools.append(tc.name());
                    }
                    sb.append(text("Assistant called tools: ", "Assistente chamou ferramentas: ")).append(tools).append("; ");
                } else if (!content.isBlank()) {
                    String snippet = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                    sb.append(text("Assistant: ", "Assistente: ")).append(snippet).append("; ");
                }
                count++;
            } else if ("tool".equals(role)) {
                // Tool result: note success/error and whether important information is present.
                String lower = content.toLowerCase();
                if (lower.contains("error") || lower.contains("failed")) {
                    sb.append(text("Reported failure; ", "Falha relatada; "));
                } else if (important) {
                    sb.append(text("Retained tool outcome; ", "Resultado de ferramenta retido; "));
                } else {
                    sb.append(text("Tool outcome retained in recent history; ", "Resultado da ferramenta mantido no histórico recente; "));
                }
                count++;
            } else if ("system".equals(role)) {
                // System messages: preserve accumulated summary from previous
                // trimHistory passes, and keep important body-log events.
                if (content.startsWith("[SUMMARY]") || content.startsWith("Conversation summary. ") || content.startsWith("Resumo da conversa. ")) {
                    // Accumulate the previous summary instead of discarding it.
                    // Without this branch, every trim would drop the prior
                    // summary and the LLM would lose ALL memory older than the
                    // recent window — a slow memory leak of long-term context.
                    // Extract everything after the "[SUMMARY]" tag.
                    String prevSummary = content.startsWith("[SUMMARY]")
                            ? content.substring("[SUMMARY]".length()).trim()
                            : content.substring(content.indexOf('.') + 1).trim();
                    if (!prevSummary.isBlank()) {
                        sb.append(prevSummary).append(" | ");
                        count++;
                    }
                } else if (content.startsWith("[EVENTS]") && important) {
                    // Body log events: skip transient ones, but keep important
                    String snippet = content.length() > 80 ? content.substring(0, 80) + "..." : content;
                    sb.append(text("Earlier server observation: ", "Observação anterior do servidor: ")).append(snippet).append("; ");
                    count++;
                }
                continue;
            }

            // Mark important events.
            if (important && sb.length() > 0) {
                // Insert the importance marker before the last semicolon.
                int lastSemi = sb.lastIndexOf("; ");
                if (lastSemi >= 0) {
                    sb.insert(lastSemi, text(" (retained evidence)", " (evidência retida)"));
                }
            }
        }
        String summary = sb.toString();
        if (summary.length() <= MAX_HISTORY_SUMMARY_CHARS) return summary;
        // Preserve both the original owner objective and the newest outcomes.
        // Keeping only one end either forgets the task or forgets what just
        // failed; a bounded head/tail ledger retains both without token drift.
        int half = (MAX_HISTORY_SUMMARY_CHARS - 32) / 2;
        return summary.substring(0, half)
                + text(" ... older details compacted ... ", " ... detalhes antigos compactados ... ")
                + summary.substring(summary.length() - half);
    }

    /**
     * Find the tool name corresponding to a tool-result message.
     * Search backward for assistant tool_calls with a matching toolCallId.
     */
    private static String findToolNameForResult(List<ChatMessage> history, int resultIndex) {
        if (resultIndex <= 0 || resultIndex >= history.size()) return "unknown";
        ChatMessage result = history.get(resultIndex);
        String resultId = result.toolCallId();
        if (resultId == null) return "unknown";
        // Search backward for matching assistant tool_calls.
        for (int i = resultIndex - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m.toolCalls() == null) continue;
            for (var tc : m.toolCalls()) {
                if (resultId.equals(tc.id())) return tc.name();
            }
        }
        return "unknown";
    }

    /**
     * Check whether a tool message has a parent assistant (with a matching
     * tool_call id) inside the already-kept portion of history. Used by
     * {@link #trimHistory()} to detect orphan tool messages whose parent
     * assistant was cut — those must be dropped, otherwise the
     * OpenAI-compatible API rejects the request with
     * "Messages with role 'tool' must be a response to a preceding message
     * with 'tool_calls'".
     *
     * @param kept    the messages already selected for keeping (in order)
     * @param toolMsg the tool message whose parent we are looking for
     * @return true if a matching assistant exists in {@code kept}
     */
    private static boolean hasParentAssistantInRange(List<ChatMessage> kept,
                                                       ChatMessage toolMsg) {
        String tcId = toolMsg.toolCallId();
        if (tcId == null) return false;
        // Scan backwards: tool results only follow their parent assistant,
        // so the parent — if kept — is the most recent assistant before us.
        for (int i = kept.size() - 1; i >= 0; i--) {
            ChatMessage pa = kept.get(i);
            if ("assistant".equals(pa.role()) && pa.toolCalls() != null) {
                for (var tc : pa.toolCalls()) {
                    if (tcId.equals(tc.id())) {
                        return true;
                    }
                }
            }
            // If we hit a user/system message, the tool's parent (if any)
            // is before this point and therefore NOT in the kept range.
            if ("user".equals(pa.role()) || "system".equals(pa.role())) {
                break;
            }
        }
        return false;
    }

    /**
     * Speak a message to the owner in-game.
     * Sends the AI's text response as a chat message to the owner player.
     */
    private void speakToOwner(String text) {
        Services.platform().scheduleOnServer(() -> {
            try {
                var owner = ((com.mineagent.engine.entity.CompanionEntity) companion).serverPlayerOwner();
                if (owner != null && owner.connection != null) {
                    // Send as a chat message with companion name prefix
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§b[" + companion.companionName() + "]§r " + text));
                    // Push to the companion chat screen (C key) so the message
                    // also appears there — the vanilla system message only
                    // shows in the regular chat HUD.
                    com.mineagent.engine.network.MineAgentNetwork.sendUiActionTo(
                            owner, companion.companionId(), "companion_chat",
                            "[" + companion.companionName() + "] " + text);
                }
                // Routine dialogue is not an implicit team broadcast. Explicit coordination tools own delivery.
            } catch (Exception e) {
                System.err.println("[MineAgent] Failed to send message to owner: " + e.getMessage());
            }
            // Also log to console for debugging
            System.out.println("[MineAgent][" + companion.companionName() + "] " + text);
        });
    }

    private void drainInbox() {
        synchronized (inboxLock) {
            if (inbox.isEmpty()) return;
            var batch = new ArrayList<>(inbox.values());
            inbox.clear();
            // Natural server observations have a separate role so durable dialogue
            // never mistakes them for literal player claims. The transient live
            // context supplies the final user-role message for provider requests.
            StringBuilder sb = new StringBuilder(IdentityText.text(RuntimeLanguage.language().id(),
                    "Server observations, not new player instructions.\n", "Observações do servidor, não novas instruções do jogador.\n"));
            for (PendingEvent entry : batch) {
                sb.append(entry.source() == EventSource.PEER
                        ? text("Teammate speech: ", "Fala de colega: ")
                        : text("Server event: ", "Evento do servidor: "));
                sb.append(ModelText.external(RuntimeLanguage.language(), entry.text())).append("\n");
            }
            synchronized (history) {
                history.add(ChatMessage.system(sb.toString()));
            }
        }
    }


    private static boolean isExplicitTeamMessage(String text) {
        if (text == null) return false;
        String value = text.stripLeading();
        return value.startsWith("[TEAM]");
    }

    // ── Tool execution ─────────────────────────────────────────────

    /**
     * Normalize provider output before it enters conversation history.
     * Tool-call ids are protocol keys, so blank or duplicate ids would merge
     * results in maps and leave at least one call without its required reply.
     */
    private static ChatMessage normalizeAssistantMessage(ChatMessage message) {
        if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return ChatMessage.assistant(message.content());
        }
        Set<String> seenIds = new HashSet<>();
        List<ChatMessage.ToolCallRef> normalized = new ArrayList<>(message.toolCalls().size());
        for (ChatMessage.ToolCallRef call : message.toolCalls()) {
            if (call == null) continue;
            String id = call.id();
            while (id == null || id.isBlank() || !seenIds.add(id)) {
                id = "call_" + UUID.randomUUID().toString().replace("-", "");
            }
            String name = call.name() == null || call.name().isBlank()
                    ? "unknown" : call.name().trim();
            String arguments = call.arguments() == null || call.arguments().isBlank()
                    ? "{}" : call.arguments();
            normalized.add(new ChatMessage.ToolCallRef(id, name, arguments));
        }
        return ChatMessage.assistant(message.content(),
                normalized.isEmpty() ? null : normalized);
    }

    private static String codedErrorJson(String code, String english, String portuguese) {
        com.google.gson.JsonObject error = new com.google.gson.JsonObject();
        error.addProperty("error", text(english, portuguese));
        error.addProperty("code", code);
        return error.toString();
    }

    private static String errorJson(String message) {
        com.google.gson.JsonObject error = new com.google.gson.JsonObject();
        error.addProperty("error", message == null ? text("Unknown error", "Erro desconhecido") : message);
        return error.toString();
    }

    private static String toolErrorJson(String toolName, Throwable failure) {
        String detail = "Tool '" + toolName + "' failed: "
                + failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            detail += " - " + failure.getMessage();
        }
        return errorJson(detail);
    }

    private record ToolExecution(String id, String name, ExecutionOwner.Lease lease,
                                 AtomicBoolean verified) {
        ExecutionProtocol.Call call() { return lease.call(); }
    }

    private static String executionUnavailable() {
        return codedErrorJson("tool_cancelled",
                "Execution was revoked or its deadline elapsed. Already committed physical effects are not rolled back; inspect current state before retrying.",
                "A execução foi revogada ou seu prazo expirou. Efeitos físicos já confirmados não são desfeitos; observe o estado atual antes de tentar novamente.");
    }

    private static String executionTimeout() {
        return codedErrorJson("tool_timeout",
                "Execution deadline elapsed. Physical effects may already exist; cancellation is queued and current state must be observed before retrying.",
                "O prazo da execução expirou. Efeitos físicos podem já existir; o cancelamento foi enfileirado e o estado atual deve ser observado antes de tentar novamente.");
    }

    /** Both native calls and alternate skill/compensation actions enter this exact protocol. */
    private ToolExecution prepareExecution(String id, Tool tool, long acceptedGeneration,
                                            java.util.function.BooleanSupplier additionalPolicy) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, tool.defaultTimeoutSeconds()));
        AtomicReference<ToolExecution> holder = new AtomicReference<>();
        long ownerRevision = executionOwner.revision();
        ExecutionOwner.Lease lease = executionOwner.open(id,
                () -> eventGeneration.get() == acceptedGeneration,
                () -> !suspended && executionOwner.revision() == ownerRevision && additionalPolicy.getAsBoolean(),
                deadline, () -> {
                    if (tool.dispatchesAsyncTask()) cancelOwnedTaskNow(id);
                    ToolExecution execution = holder.get();
                    if (execution != null) markExecutionUnverified(execution);
                });
        ToolExecution execution = new ToolExecution(id, tool.name(), lease, new AtomicBoolean());
        holder.set(execution);
        return execution;
    }

    /** Server-thread-only physical invocation, including checkpoints, callbacks and revocation scope. */
    private void dispatchExecution(ToolExecution execution, Tool tool, com.google.gson.JsonObject args) {
        execution.call().dispatch(() -> {
            publishWorldSnapshotNow(true);
            long tick = currentGameTickSafe();
            if (!tool.dispatchesAsyncTask() && SKILL_ACTION_TOOLS.contains(tool.name())) planner.bindToolCall(execution.id());
            if (SKILL_ACTION_TOOLS.contains(tool.name())) {
                actionLedger.begin(execution.id(), tool.name(),
                        text("server callback plus postcondition observation", "retorno do servidor e observação da pós-condição"),
                        semanticWorldModel, tick);
            }
            semanticWorldModel.recordAction(tool.name(), args.toString(), execution.id(), tick);
            dispatchedActionTools.put(execution.id(), tool.name());
            mechanismExplorer.onToolDispatched(execution.id(), tool.name(), args.toString(), tick);
        }, callback -> tool.onServerCall(execution.id(), args, companion, callback),
                raw -> publishToolOutcome(execution, raw),
                raw -> reconcileLateToolReceipt(execution, raw),
                failure -> toolErrorJson(tool.name(), failure),
                task -> Services.platform().scheduleOnServer(task),
                execution.lease()::requestCancellation, executionUnavailable());
        if (execution.call().terminal().state() == ExecutionProtocol.State.EXPIRED) {
            markExecutionUnverified(execution);
        }
    }

    /** Called only after terminal ownership and payload have been atomically published. */
    private void publishToolOutcome(ToolExecution execution, String raw) {
        publishWorldSnapshotNow(true);
        FailureAnalysis outcome = analyzeFailure(raw);
        if ("execution_unverified".equals(outcome.errorType())) {
            // A callback can truthfully report revocation after partial physical work.
            // Completion of its transport is not verification of the requested action.
            if (SKILL_ACTION_TOOLS.contains(execution.name())) {
                actionLedger.finish(execution.id(), ActionTransactionLedger.Status.UNVERIFIED,
                        raw, semanticWorldModel, currentGameTickSafe());
            }
            dispatchedActionTools.remove(execution.id());
            execution.lease().release();
            cognitiveState.append("unverified_tool_receipt", execution.id(), raw, currentGameTickSafe());
            mechanismExplorer.onToolResult(execution.id(), execution.name(), false, false, raw, currentGameTickSafe());
            return;
        }
        boolean async = "async_pending".equals(outcome.errorType());
        boolean success = async || outcome.isSuccess();
        long tick = currentGameTickSafe();
        if (!async) {
            dispatchedActionTools.remove(execution.id());
            if (SKILL_ACTION_TOOLS.contains(execution.name())) {
                var transaction = actionLedger.finishObserved(execution.id(), success, raw, semanticWorldModel, tick);
                success = transaction != null && transaction.status() == ActionTransactionLedger.Status.VERIFIED;
            }
            semanticWorldModel.recordOutcome(execution.name(), success, raw, execution.id(), tick,
                    durableToolOutcome(execution.name()));
            if (!"identity_memory".equals(execution.name())
                    && (IDENTITY_OBSERVATION_TOOLS.contains(execution.name()) || SKILL_ACTION_TOOLS.contains(execution.name()))) {
                identity.recordEvidence("observation", "tool:" + execution.name(), raw, tick);
            }
            execution.lease().release();
        } else if (SKILL_ACTION_TOOLS.contains(execution.name())) {
            actionLedger.markPending(execution.id(), semanticWorldModel, tick, raw);
        }
        // execute_skill has its own exact run lease, retained until the skill terminal event.
        if (async && "execute_skill".equals(execution.name())) execution.lease().release();
        execution.verified().set(success);
        mechanismExplorer.onToolResult(execution.id(), execution.name(), success, async, raw, tick);
    }

    /** Late receipts are observations, never successful/stale learned action outcomes. No rollback is invented. */
    private void reconcileLateToolReceipt(ToolExecution execution, String raw) {
        publishWorldSnapshotNow(true);
        String receipt = text("Late physical execution receipt; the original call is no longer authoritative. Verify live state before repeating work: ",
                "Recibo tardio de execução física; a chamada original não é mais autoritativa. Verifique o estado atual antes de repetir o trabalho: ")
                + execution.id() + " (" + execution.name() + "): " + truncateForLog(raw, 2400);
        cognitiveState.append("late_tool_receipt", execution.id(), receipt, currentGameTickSafe());
        enqueueInbox(receipt);
    }

    /** Server-thread-only expiry bookkeeping; a timeout is uncertainty, not an observed physical failure. */
    private void markExecutionUnverified(ToolExecution execution) {
        if (execution.call().terminal().state() != ExecutionProtocol.State.EXPIRED) return;
        if (SKILL_ACTION_TOOLS.contains(execution.name())) {
            var transaction = actionLedger.get(execution.id());
            if (transaction != null && (!transaction.terminal())) {
                actionLedger.finish(execution.id(), ActionTransactionLedger.Status.UNVERIFIED,
                        execution.call().terminal().payload(), semanticWorldModel, currentGameTickSafe());
            }
        }
        dispatchedActionTools.remove(execution.id());
        execution.lease().release();
    }

    private void expireExecution(ToolExecution execution, String payload) {
        if (!execution.call().expire(payload)) return;
        execution.lease().requestCancellation();
    }

    private List<ChatMessage> executeToolCalls(List<ChatMessage.ToolCallRef> toolCalls,
                                                long acceptedGeneration) {
        List<ToolExecution> executions = new ArrayList<>();
        Map<String, ChatMessage> resultsById = new LinkedHashMap<>();
        boolean asyncBodyReserved = false;
        for (var tc : toolCalls) {
            Optional<Tool> toolOpt = ToolRegistry.get(tc.name());
            if (toolOpt.isEmpty()) {
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(),
                        errorJson(text("Unknown tool: ", "Ferramenta desconhecida: ") + tc.name())));
                continue;
            }
            Tool tool = toolOpt.get();
            com.google.gson.JsonObject args;
            try { args = com.google.gson.JsonParser.parseString(tc.arguments()).getAsJsonObject(); }
            catch (Exception invalid) {
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(),
                        errorJson(text("Invalid arguments: ", "Argumentos inválidos: ") + invalid.getMessage())));
                continue;
            }
            if (tool.dispatchesAsyncTask() && asyncBodyReserved) {
                resultsById.put(tc.id(), ChatMessage.toolResult(tc.id(), codedErrorJson("body_task_conflict",
                        "This response already dispatched an asynchronous body task. Wait for its terminal outcome before starting another body action.",
                        "Esta resposta já despachou uma tarefa corporal assíncrona. Aguarde seu resultado final antes de iniciar outra ação corporal.")));
                continue;
            }
            if (tool.dispatchesAsyncTask()) asyncBodyReserved = true;
            ToolExecution execution = prepareExecution(tc.id(), tool, acceptedGeneration, () -> true);
            executions.add(execution);
            try { Services.platform().scheduleOnServer(() -> dispatchExecution(execution, tool, args)); }
            catch (Throwable rejected) { expireExecution(execution, toolErrorJson(tc.name(), rejected)); }
        }
        for (ToolExecution execution : executions) {
            try {
                if (!execution.call().await()) expireExecution(execution, executionTimeout());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                expireExecution(execution, executionUnavailable());
            }
            // Terminal state and payload are one immutable publication, including when terminal effects are still finishing.
            String raw = execution.call().terminal().payload();
            if (raw == null) throw new IllegalStateException("Execution ended without a terminal payload");
            resultsById.put(execution.id(), ChatMessage.toolResult(execution.id(),
                    truncateToolResult(raw, getToolResultLimit(execution.name()), execution.name())));
        }
        List<ChatMessage> results = new ArrayList<>(toolCalls.size());
        for (var tc : toolCalls) {
            ChatMessage result = resultsById.get(tc.id());
            if (result == null) continue;
            results.add(result);
            FailureAnalysis analysis = analyzeFailure(result.content());
            boolean invalid = "invalid_arguments".equals(analysis.errorType()) || "unknown_tool".equals(analysis.errorType());
            metrics.recordAction(!invalid, analysis.isSuccess(), invalid, analysis.errorType());
        }
        return results;
    }

    /** This method is reached only from a server-queued owned cancellation, never from a reasoning waiter. */
    private void cancelOwnedTaskNow(String taskId) {
        MineAgentEngine.getCompanion(companion.companionId())
                .ifPresent(state -> state.auction.cancelTask(taskId));
    }

    /** Only physical/world-changing tools belong in durable outcome memory. */
    private static boolean durableToolOutcome(String toolName) {
        return toolName != null && SKILL_ACTION_TOOLS.contains(toolName);
    }

    /**
     * Get the result truncation limit for a tool name.
     * Perception tools such as look_around need more room than simple action tools.
     */
    private static int getToolResultLimit(String toolName) {
        if (toolName == null) return 800;
        String lower = toolName.toLowerCase();
        // look_around now emits ordered, bounded JSON. Keep the complete
        // object instead of repairing a cut in the entity or terrain arrays.
        if (lower.equals("identity_memory")) return 7000;
        if (lower.contains("look_around")) return 9000;
        if (lower.contains("resolve_need")) return 7000;
        if (lower.contains("plan_acquisition")) {
            // This result is an explicitly requested dependency DAG. Cutting
            // it at the generic 800-character action limit removes deeper
            // leaves and makes the planner fabricate missing prerequisites.
            // The tool itself bounds depth/node count, so a larger complete
            // result remains deterministic and paid only on planning turns.
            return 26000;
        }
        if (lower.contains("inspect_gui") || lower.contains("inspect_block_storage")) {
            return 9000;
        }
        // Perception: allow 1200 characters for environmental context.
        if (lower.contains("look") || lower.contains("scan")
                || lower.contains("search") || lower.contains("find")) {
            return 1200;
        }
        // Status: medium length, 600 characters.
        if (lower.contains("status") || lower.contains("inventory")
                || lower.contains("health") || lower.contains("check")) {
            return 600;
        }
        // Default: 800 characters.
        return 800;
    }

    /**
     * Truncate a tool result string to a maximum length.
     *
     * <p><b>Safe truncation:</b> first try ending at the last complete JSON object/array
     * element, close the structure, and validate it with {@link com.google.gson.JsonParser}.
     * If the repair is still invalid (deep nesting, a cut inside a string, etc.),
     * fall back to plain text "Result too long; truncated: ...". Plain text is not JSON,
     * but avoids malformed JSON that could cause parsing errors or hallucinated fields.
     *
     * @param result raw tool result
     * @param maxChars maximum characters to keep
     * @param toolName tool name (for importance-aware truncation)
     * @return truncated result (possibly with a truncation marker appended)
     */
    private String truncateToolResult(String result, int maxChars, String toolName) {
        if (result == null || result.length() <= maxChars) return result;

        // Use ImportanceEvaluator to decide whether important content needs extra space.
        // Give a result judged important 1.3 times the normal room.
        boolean isImportant = importance.isImportant(toolName, result);
        if (isImportant) {
            maxChars = (int)(maxChars * 1.3);  // Retain 30% more of an important result.
        }

        String truncated = result.substring(0, Math.min(maxChars, result.length()));

        // Try valid JSON: end at the last complete element, close the structure,
        // and append "truncated":true. Use it only if validation succeeds.
        String repaired = tryRepairJsonTruncation(truncated, maxChars);
        if (repaired != null) {
            return repaired;
        }
        // Fall back to plain text, which remains readable even though it cannot be parsed as JSON.
        // This is safer than malformed JSON that leads to parsing errors or hallucinated fields.
        return "Result too long; truncated (original length=" + result.length() + " characters): " + truncated;
    }

    /**
     * Try to repair a truncated string into valid JSON.
     *
     * <p>Strategy:
     * <ul>
     *   <li>Object ({...}): keep through the last "}," boundary, append
     *       "truncated":true, and close }.</li>
     *   <li>Array ([...]): keep through the last "}," boundary, append
     *       {"truncated":true}, and close ].</li>
     *   <li>Non-JSON plain text: append a truncation marker directly.</li>
     * </ul>
     * Validate every candidate with JsonParser; return null on failure so the caller can use plain text.
     */
    private static String tryRepairJsonTruncation(String truncated, int maxChars) {
        String trimmed = truncated.trim();
        try {
            if (trimmed.startsWith("{")) {
                // Object: prefer truncating after the last complete field.
                int lastComplete = truncated.lastIndexOf("},");
                if (lastComplete > maxChars / 2) {
                    String candidate = truncated.substring(0, lastComplete + 1)
                            + "\"truncated\":true}";
                    if (isValidJson(candidate)) return candidate;
                }
                // Try closing the object directly.
                String candidate = truncated + ",\"truncated\":true}";
                if (isValidJson(candidate)) return candidate;
            } else if (trimmed.startsWith("[")) {
                // Array: prefer truncating after the last complete element.
                int lastComplete = truncated.lastIndexOf("},");
                if (lastComplete > maxChars / 2) {
                    String candidate = truncated.substring(0, lastComplete + 1)
                            + "{\"truncated\":true}]";
                    if (isValidJson(candidate)) return candidate;
                }
                String candidate = truncated + ",{\"truncated\":true}]";
                if (isValidJson(candidate)) return candidate;
            } else {
                // Non-JSON plain text only needs a truncation marker.
                return truncated + "...[truncated]";
            }
        } catch (Exception e) {
            // Any exception falls back to plain text.
        }
        return null;
    }

    /** Validate a JSON string with Gson. */
    private static boolean isValidJson(String s) {
        try {
            com.google.gson.JsonParser.parseString(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Build a cache key for the current decision context.
     *
     * <p>The key quantizes the AI's situation so that similar states
     * (same rough position, health tier, hunger tier, last owner command)
     * map to the same cache entry. This lets us skip the LLM call when
     * the situation hasn't meaningfully changed.
     *
     * <p>Quantization is intentionally coarse:
     * <ul>
     *   <li>Position: 4-block grid (a 4×4×4 cube shares one key)</li>
     *   <li>Health: 4 tiers (0-4, 5-9, 10-14, 15-20)</li>
     *   <li>Hunger: 3 tiers (0-6, 7-14, 15-20)</li>
     *   <li>Last owner command: included verbatim (most important signal)</li>
     * </ul>
     */
    private String buildDecisionCacheKey() {
        try {
            int x = (int) companion.posX();
            int y = (int) companion.posY();
            int z = (int) companion.posZ();
            float health = companion.health();
            int food = companion.foodLevel();
            int healthTier = health < 5 ? 0 : health < 10 ? 1 : health < 15 ? 2 : 3;
            int hungerTier = food < 7 ? 0 : food < 15 ? 1 : 2;
            String lastCmd = lastOwnerCommand;
            return DecisionCache.buildKey(x, y, z, healthTier, hungerTier,
                    null, lastCmd);
        } catch (Exception e) {
            return null;
        }
    }

    /** Stable cache affinity excludes volatile ticks, coordinates and UUIDs. */
    private String stablePromptCacheKey(LLMProvider provider) {
        String owner = companion.ownerUuid() == null ? "owner" : companion.ownerUuid().toString();
        String name = companion.companionName() == null ? "companion"
                : companion.companionName().trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return provider.providerId() + ":" + model + ":mineagent-prompt-v5:" + RuntimeLanguage.language().id() + ":" + owner + ":" + name;
    }

    /** HTTP deadline is bounded per reasoning round so stale requests can be cancelled. */
    private static long providerDeadlineSeconds(int roundNumber) {
        return LLMRequest.DEFAULT_DEADLINE.getSeconds();
    }

    /** Track the last owner command for cache keying. */
    private volatile String lastOwnerCommand = "";

    /** Bounded conversation retrieval cue; it confers no task or plan authority. */
    private volatile String latestOwnerSpeech = "";

    /** Timestamp of the last player message, used by decision-cache safety gates. */
    private volatile long lastOwnerMessageTime = 0;

    /** Consecutive cache hits; reaching the limit forces an LLM call. */
    private int consecutiveCacheHits = 0;

    /** Remediation attempts this turn, reset at each turn start to bound recursion. */
    private int remediationRoundCounter = 0;

    /**
     * Decision-cache safety gate: determine whether to skip cached decisions now.
     *
     * <p>Skip the cache when:
     * <ul>
     *   <li>The player spoke in the last 30 seconds (conversation needs fresh understanding)</li>
     *   <li>Consecutive cache hits reached the limit (prevent endless replay loops)</li>
     * </ul>
     */
    private boolean shouldSkipCache() {
        // 1. Recent player speech disables caching.
        if (lastOwnerMessageTime > 0) {
            long elapsed = System.currentTimeMillis() - lastOwnerMessageTime;
            if (elapsed < CACHE_SKIP_AFTER_PLAYER_MSG_MS) {
                return true;
            }
        }
        // 2. Reaching the consecutive-hit limit forces an LLM call.
        if (consecutiveCacheHits >= MAX_CONSECUTIVE_CACHE_HITS) {
            return true;
        }
        return false;
    }

    /** Legacy saved-utterance parsing only; never requested or used to gate a decision. */
    private static boolean hasExplicitNoActionMarker(String text) {
        if (text == null || text.isBlank()) return false;
        // Accept explicit English marker variants anywhere in the response.
        // Keep the canonical [NO_ACTION] protocol and tolerate common formatting variants:
        // (NO_ACTION) and [NO ACTION] remain explicit rather than matching ordinary prose.
        return text.contains("[NO_ACTION]")
                || text.contains("(NO_ACTION)")
                || text.contains("[NO ACTION]");
    }

    /**
     * Truncate text to the requested length for logging.
     * Replace excess content with "..." and remove newlines to avoid log pollution.
     */
    private static String truncateForLog(String text, int maxLen) {
        if (text == null) return "";
        String single = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (single.length() <= maxLen) return single;
        return single.substring(0, maxLen) + "...";
    }

    /**
     * Check whether text matches the last assistant message in history.
     * Prevent cache hits from replaying the identical response in an endless loop.
     */
    private boolean isSameAsLastAssistant(String text) {
        if (text == null) return false;
        synchronized (history) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage msg = history.get(i);
                if ("assistant".equals(msg.role())) {
                    String lastContent = msg.content();
                    if (lastContent == null) return false;
                    // Trim whitespace for comparison
                    return text.trim().equalsIgnoreCase(lastContent.trim());
                }
            }
        }
        return false;
    }

    private LLMProvider resolveProvider() {
        // ── Provider selection ──
        // Model switching is NOT allowed at runtime — each companion is bound
        // to one (provider, model, baseUrl) triple for its entire lifetime.
        //
        // Selection order:
        //   1. If providerId is set AND it supports the current model,
        //      use it. This lets the user explicitly choose a provider for
        //      a relay URL (e.g. picking "anthropic" when the relay speaks
        //      Anthropic native format, or "openai" when it speaks
        //      OpenAI-compatible format for the same Claude model).
        //   2. Otherwise auto-detect from the model name. This handles
        //      official URLs and the common case where providerId was
        //      not set in config.
        //
        // Note: We deliberately do NOT force OpenAI format for relay URLs —
        // many relays support Anthropic /v1/messages and Gemini /v1beta/...
        // natively, and the user's choice of providerId is authoritative.
        LLMProvider resolved;
        if (providerId != null) {
            var byId = LLMProviderRegistry.get(providerId);
            if (byId.isPresent()) {
                // An explicit provider is authoritative.  Vendor adapters
                // intentionally accept custom/future model IDs and relay
                // endpoints; rejecting them here caused a misleading
                // auto-detection failure before the request ever reached the
                // configured endpoint.
                resolved = byId.get();
            } else {
                resolved = LLMProviderRegistry.detectForModel(model)
                        .orElseThrow(() -> new IllegalStateException(
                                "Cannot detect provider for model: " + model
                                        + " (providerId=" + providerId + ")"));
            }
        } else {
            resolved = LLMProviderRegistry.detectForModel(model)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot detect provider for model: " + model
                                    + " (providerId=" + providerId + ")"));
        }
        return resolved;
    }

    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool tool : ToolRegistry.all()) {
            if (!CORE_TOOL_NAMES.contains(tool.name())
                    && !exposedExtraTools.contains(tool.name())) continue;
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameterSchema());
            def.put("function", function);
            defs.add(def);
        }
        return defs;
    }

    /** Build the immutable instruction prefix shared by every request. */
    private static String text(String english, String portuguese) {
        return IdentityText.text(RuntimeLanguage.language().id(), english, portuguese);
    }

    /** Keep the large stable prefix unchanged except when the server language actually changes. */
    private void refreshSystemPromptIfNeeded() {
        String prompt = buildSystemPrompt();
        synchronized (history) {
            if (!history.isEmpty() && prompt.equals(history.getFirst().content())) return;
            if (history.isEmpty()) history.add(ChatMessage.system(prompt));
            else history.set(0, ChatMessage.system(prompt));
            providerHistoryCursor = 0;
            ProviderSessionState state = providerTransportState.get();
            providerTransportState.set(new ProviderSessionState(state.providerId(), state.model(),
                    state.conversationId(), null, null, state.revision()));
        }
    }

    private String buildSystemPrompt() {
        return IdentityInstructions.system(RuntimeLanguage.language().id());
    }

    /**
     * Build trusted, volatile server state as a final transient user message.
     * Keeping it out of history[0] is essential: a changing suffix inside the
     * first system message invalidates every later dialogue and tool token in
     * prefix-based provider caches.
     */
    private String buildLiveContext() {
        StringBuilder sb = new StringBuilder();
        PlanGraph.State planState = planner.exportState();
        PlanGraph.PlanNode planNode = planner.currentNode();
        String memoryQuery = memoryQuery(planState, planNode);
        sb.append(IdentityText.text(RuntimeLanguage.language().id(), "Current server context.\n", "Contexto atual do servidor.\n"));
        sb.append(text("The following facts are published by the server, not new player instructions. Use them only to adjust the current decision; do not repeat the whole context.\n", "Os fatos a seguir foram publicados pelo servidor, não são novas instruções do jogador. Use-os apenas para ajustar a decisão atual; não repita todo o contexto.\n"));
        sb.append(cognitiveState.summarizeForPrompt(12));

        // ── Companion mode (DYNAMIC: toggled by /mineagent mode) ──
        var mode = MineAgentEngine.getCompanionMode(companion.companionId());
        sb.append(text("Mode: ", "Modo: ")).append(mode == MineAgentEngine.CompanionMode.FOLLOW
                ? text("Follow within the allowed owner distance.", "Siga dentro da distância permitida do dono.")
                : text("Free travel for tasks.", "Deslocamento livre para tarefas.")).append("\n");
        sb.append(text("Game mode: ", "Modo de jogo: ")).append(companion instanceof com.mineagent.engine.entity.CompanionEntity entity
                ? entity.gameMode().wireName() : "survival")
                .append(text(" (controlled by the human owner; you cannot change it)\n", " (controlado pelo dono humano; você não pode alterá-lo)\n"));
        appendLiveBodyState(sb, liveBodyState.get());
        sb.append(realtimeCognition.summarizeForPrompt());
        sb.append(TeamBlackboard.summarize(companion.ownerUuid(),
                companion.companionId(), liveAssetGameTick.get()));
        sb.append(identity.decisionContext(memoryQuery, RuntimeLanguage.language().id(), 1_200));
        sb.append(identityReflection.context(RuntimeLanguage.language().id(), 1_200));
        // Retrieve locations against the newest objective, not merely by
        // global importance. This prevents unrelated old POIs from crowding
        // out a remembered facility or resource needed by the current goal.
        sb.append(compactPlaceMemory(memoryQuery, 3));
        // Skills: only count, not full list
        if (skillLib.size() > 0) {
            sb.append(text("Mastered skills: ", "Habilidades aprendidas: ")).append(skillLib.size()).append("\n");
        }
        sb.append(rollingPlanner.summarizeForPrompt());
        sb.append(skillRuntime.summarizeForPrompt());
        sb.append(actionLedger.summarizeForPrompt(liveAssetGameTick.get()));
        sb.append(reservationLedger.summarizeForPrompt(liveAssetGameTick.get()));
        sb.append(layeredMemory.summarizeForPrompt(memoryQuery,
                liveAssetGameTick.get(), 700));
        String beliefSummary = beliefState.summarizeForPrompt();
        if (!beliefSummary.isBlank()) sb.append(beliefSummary);
        String assetSummary = worldAssetIndex.summarizeForPrompt(
                liveAssetPosition.get(), liveAssetGameTick.get());
        if (!assetSummary.isBlank()) sb.append(assetSummary);
        sb.append(semanticWorldModel.summarizeForPrompt(liveAssetGameTick.get()));
        String semanticRecall = semanticWorldModel.recallForPrompt(memoryQuery,
                liveAssetPosition.get(), liveAssetGameTick.get(), 4);
        if (!semanticRecall.isBlank()) sb.append(semanticRecall);
        sb.append(mechanismExplorer.summarizeForPrompt(memoryQuery));
        String experienceSummary = experienceStore.summarizeForPrompt(memoryQuery);
        if (!experienceSummary.isBlank()) sb.append(experienceSummary);
        // Evidence-backed identity reflection replaces heuristic canned lessons in decision context.
        // Cognitive map (spatial memory): inject nearby POIs so the LLM
        // can reason "I saw iron ore 5 blocks north" without re-scanning.
        // This is critical for spatial reasoning and avoiding redundant
        // exploration (the MINDCUBE "build map first, then reason" insight).
        String spatialMemory = cognitiveMap.summarizeForPrompt(memoryQuery, 4);
        if (!spatialMemory.isBlank()) {
            sb.append(spatialMemory);
        }
        // ImportanceEvaluator: dynamically learned importance rather than hardcoded weights.
        String learned = importance.summarizeForPrompt();
        if (!learned.isBlank()) {
            sb.append(learned).append("\n");
        }

        return sb.toString();
    }

    /**
     * Compose one retrieval query from the newest command and both planning
     * horizons. Keeping all three makes recall robust while a rolling plan is
     * being repaired, but newest owner text remains first and therefore clear.
     */
    private String memoryQuery(PlanGraph.State planState, PlanGraph.PlanNode node) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (!latestOwnerSpeech.isBlank()) parts.add(latestOwnerSpeech);
        if (planState != null && planState.goal() != null && !planState.goal().isBlank()) {
            parts.add(planState.goal());
        }
        if (node != null && node.description() != null && !node.description().isBlank()) {
            parts.add(node.description());
        }
        return truncateForLog(String.join(" | ", parts), 640);
    }

    /** Append only immutable server-published task evidence to the prompt. */
    private static void appendLiveBodyState(StringBuilder out, LiveBodyState body) {
        if (body == null || body.taskId() == null) {
            out.append(text("Body executor: idle", "Executor corporal: ocioso"));
            if (body != null && body.message() != null && !body.message().isBlank()) {
                out.append("; ").append(truncateForLog(body.message(), 240));
            }
            out.append('\n');
            return;
        }
        out.append(text("Body executor: task_id=", "Executor corporal: task_id=")).append(body.taskId())
                .append(text(" task=", " tarefa=")).append(body.taskName())
                .append(text(" state=", " estado=")).append(body.state() == null ? "UNKNOWN" : body.state());
        TaskSnapshot snapshot = body.snapshot();
        if (snapshot != null) {
            out.append(text(" stage=", " etapa=")).append(snapshot.stage())
                    .append(text(" progress=", " progresso=")).append(snapshot.completedUnits())
                    .append('/').append(snapshot.totalUnits());
            if (snapshot.hasTarget()) {
                out.append(text(" target=", " alvo=")).append(snapshot.targetX()).append(',')
                        .append(snapshot.targetY()).append(',').append(snapshot.targetZ());
            }
            if (snapshot.blockedReason() != null) {
                out.append(text(" blocked=", " bloqueio="))
                        .append(truncateForLog(snapshot.blockedReason(), 240));
            }
            if (snapshot.evidence() != null) {
                out.append(text(" evidence=", " evidência="))
                        .append(truncateForLog(snapshot.evidence(), 360));
            }
        }
        if (body.message() != null && !body.message().isBlank()) {
            out.append(text(" message=", " mensagem=")).append(truncateForLog(body.message(), 180));
        }
        out.append(text(" observed_tick=", " instante_observado=")).append(body.gameTick()).append('\n');
    }

    /** Compact place memory: only the N most recent unique subjects. */
    private String compactPlaceMemory(String query, int max) {
        String summary = placeMemory.summarizeForPrompt(query, max);
        metrics.recordMemoryQuery(summary != null && summary.contains("- "));
        return summary == null ? "" : summary;
    }

    /** Shut down the loop executor.
     *  Calls {@link ExecutorService#shutdown()} to stop accepting new
     *  tasks, then waits up to 5 seconds for the running turn (if any) to
     *  finish gracefully. If the running turn does not complete in time,
     *  forces a shutdown with {@link ExecutorService#shutdownNow()} so the
     *  JVM can exit. Previously shutdown() returned immediately without
     *  waiting, which could orphan a running turn mid-LLM-call. */
    public void shutdown() {
        synchronized (this) {
            suspended = true;
            cancel();
        }
        realtimeCognition.close();
        long shutdownTick = currentGameTickSafe();
        Services.platform().scheduleOnServer(() -> mechanismExplorer.abort(null,
                text("Agent loop shutdown", "Encerramento do ciclo do agente"), shutdownTick));
        pendingTaskActions.clear();
        dispatchedActionTools.clear();
        // Save memories before shutting down so the companion does not
        // "forget" everything on restart. Memory stores are thread-safe,
        // so saving from this thread while a turn is running is safe.
        if (persistence != null) {
            try {
                persistence.saveProviderSessionNow(providerTransportState.get());
            } catch (RuntimeException ignored) {
                // Provider teardown must never prevent semantic memory from saving.
            }
            persistence.saveAll();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[MineAgent] Loop executor did not terminate "
                        + "gracefully in 5s — forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            // awaitTermination interrupted — force shutdown and restore flag
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            // A turn that was already past its stale-response gate may append
            // its final owner-facing text while shutdown is waiting. Persist
            // once more after termination so that last complete dialogue and
            // its resulting memories are not lost across a normal restart.
            if (persistence != null) persistence.saveAll();
        }
    }
}
