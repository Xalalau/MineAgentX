# MineAgentX capabilities

## Initial checkpoint

The following is an English rendering of the assistant's last checkpoint before this fork preparation. It records the starting state, not the final state of the changes described below.

> I checked the files and Git state. I am aligned with the current request:
>
> - The new base had been cloned from upstream on `main`, commit `701250a950154667212d3ae1be3b3197e9ce9470`, with no local changes.
>
> The direction is Minecraft 1.21.1 with NeoForge as the primary platform, English as the base with configurable localization, compatibility with your Bonsai/MTP setup, and more decisions made by the AI. Do not automatically carry over phrase shortcuts, predefined personalities or spontaneous lines, dedicated home/bed commands, or a ready-made cottage. For construction, the desired cycle is design, visualize, revise, convert coordinates, and execute in the game.
>
> The last request was to clone and list capabilities, not begin the conversion. That was delivered. MineAgent had not been built, installed, or modified.
>

## Scope and evidence

This inventory expands the capability list from the source review of original MineAgent revision `701250a950154667212d3ae1be3b3197e9ce9470`, updated for this fork's NeoForge-only, local-inference-only scope. The checkout is now named `MineAgentX`; the Git remote is `https://github.com/Xalalau/MineAgentX.git`.

Source implementation is not proof of reliable performance in a live world. Completion depends on the selected model, available resources, permissions, pathfinding, and the installed modpack. See `PROGRESS.md` for actual validation results. Documentation is in English. Configurable EN/PT-BR runtime support is under integration; complete legacy-producer coverage is not yet verified.

## 1. Actions available to the AI

The built-in registry contains **43 tools**. Specialized tools can be exposed on demand rather than sending all schemas in every request.

### Perception and location

| Tool | Capability |
| --- | --- |
| `look_around` | Read structured world observations: self state, immediate threats, nearby living entities, vertical profile, notable 3D blocks, and a compact local map. |
| `scan_blocks` | Find loaded nearby blocks by exact registered ID or block tag and remember observed positions. |
| `scan_nearby_entities` | Find nearby entities, reporting type, position, ID, and health where applicable. |
| `get_self_status` | Read health, hunger, position, inventory, equipment, and active effects. |
| `get_owner_status` | Read the online owner's health, hunger, position, game mode, experience, and effects. |
| `get_world_info` | Read dimension, time, weather, difficulty, relevant game rules, and spawn position. |
| `inspect_block` | Inspect a loaded block's ID, state properties, block-entity data, solidity, and hardness. |
| `inspect_block_storage` | Inspect item slots in a nearby loaded container without transferring items. |
| `locate_structure` | Find a structure by registered ID or tag using the server locator, rather than human-style visual exploration. |
| `locate_biome` | Find a biome using the server search. |

Perception is structured server data, not screenshots delivered to a vision model. The debug vision overlay is a player-facing visualization, not an image-input tool for the LLM.

### Movement, construction, interaction, and combat

| Tool | Capability |
| --- | --- |
| `goto` | Navigate to horizontal coordinates, an exact position, an altitude, or a block's neighboring position. Pathfinding can dig, bridge, climb pillars, and use supported movement maneuvers subject to policy and resources. |
| `auto_mine` | Find and mine a requested block type and quantity, navigating to targets and collecting drops where reachable. |
| `build` | Place or clear a sequence of block coordinates. Each request uses one block type and accepts at most 512 positions. |
| `construction` | Create and revise persisted coordinate designs, assess candidate sites, inspect material/effort estimates, and generate six orthographic PNG views. |
| `execute_construction` | Execute a design revision through physical `build` tasks in bounded batches and reconcile observed block outcomes. Task admission alone is not completion. |
| `interact_at` | Use or attack a block, or use the offhand, optionally selecting an item and interaction duration. |
| `interact_entity` | Use or attack an entity, including offhand use. |
| `melee_attack` | Approach a target and attack repeatedly with the equipped weapon until a terminal outcome or cancellation. |
| `ranged_attack` | Aim and execute the charge/release lifecycle of supported bows, crossbows, tridents, and thrown projectiles. |

Construction navigates to valid placement positions, checks reach and line of sight, uses the Minecraft item-on-block interaction, and verifies resulting block state. It is not arbitrary long-distance world editing. Creative construction may use temporary block items; Survival and Adventure remain constrained by inventory and permissions.

The versioned design/preview/revision subsystem is implemented and registered, with standalone regression coverage. Previews are colored voxels rather than Minecraft textures or special block geometry. Automatic terminal-event reconciliation and optional local visual-model review still require final integration; no live construction or visual inference is claimed. Projects currently use owner + exact companion name for storage; renaming requires a storage migration.

### Inventory and item use

| Tool | Capability |
| --- | --- |
| `equip_item` | Equip armor, offhand, and hotbar slots, swapping existing contents back to the source slot. |
| `eat_item` | Eat a requested food or select an available safe food, respecting use duration and interruption. |
| `drop_items` | Drop an item quantity at the companion's current location. |
| `collect_items` | Physically navigate to eligible dropped items, optionally filtering by item. Count only corroborated scoped pickup receipts; refuse oversized targets without splitting away entity metadata. Exhausted partial delivery is reported as incomplete, not success. |
| `transfer_items` | Move items between player slots or between inventory and the currently open container. |
| `use_item` | Use an item without targeting a block/entity: throw, eat, drink, or hold and release, with optional 3D aim and either hand. |
| `inspect_gui` | Read slots of the currently open container menu. |
| `close_gui` | Close the open container. |

These primitives allow workflows such as opening a furnace, inserting fuel and input, waiting for processing, and collecting output. They do not guarantee support for every custom menu action or private mod API.

Collection reports observed/requested/remaining quantities in EN/PT-BR. A scoped pickup Post event for the exact player/entity must agree with immediate inventory gain for the same item/components; unrelated grants and entity disappearance are not delivery receipts. Oversized partial pickups are refused without replacing the original entity or altering its ownership, age, lifespan, delay or components. Vanilla automatic collision pickup outside the observed touch can still occur, is not attributed to this task, and can exceed the request. This is not a global pickup cap or proof of live protection-mod behavior.

### Recipes, resources, and planning

| Tool | Capability |
| --- | --- |
| `lookup_recipe` | Find registered recipes producing or consuming an exact item ID, including ingredient alternatives. |
| `craft` | Execute registered crafting recipes with the 2x2 grid or a nearby crafting table for 3x3 recipes. Counts represent recipe batches. Each batch requires carried space for its complete output and recipe leftovers before ingredients are consumed; partial results distinguish committed and remaining batches. |
| `resolve_need` | Search carried items, equipment, inspected storage, observed drops, and known facilities before making or replacing an item. |
| `plan_acquisition` | Expand an item/count target into a bounded dependency graph accounting for inventory, observed assets, yields, alternatives, and planned surplus. |
| `todowrite` | Create or repair a long-horizon plan, retaining verified checkpoints and requiring evidence before accepting the final goal. |

Registered modded recipes can be discovered. Unknown station mechanics remain explicit exploration needs rather than assumed executable recipes.

### Memory and learning

| Tool | Capability |
| --- | --- |
| `recall_memory` | Retrieve remembered locations and points of interest, including resources, storage, facilities, structures, and hazards. |
| `identity_memory` | Progressively retrieve recorded evidence, learned preferences, goals and world knowledge, and submit justified, revision-checked updates. Player claims remain distinct from observations. |
| `explore_mechanism` | Propose, rank, inspect, or abort bounded experiments on unfamiliar blocks, items, menus, recipes, or mod rules. |
| `load_skill` | Load a built-in instructional skill document into the model's context. |
| `list_learned_skills` | Retrieve reusable skills learned from verified successful action sequences. |
| `execute_skill` | Execute a learned skill one step at a time with preconditions, executor outcomes, and postcondition checks. |

### Execution and teamwork control

| Tool | Capability |
| --- | --- |
| `task_status` | Query an asynchronous task's state. |
| `task_stop` | Request cancellation of a task. |
| `wait_for` | Wait for a bounded, stable, server-observed condition: duration, item count, semantic fact, GUI slot, dimension, block state, or entity presence/absence. |
| `coordinate_team` | Inspect team state, claim a role/objective, request support, or release a commitment. |
| `query_extra_tools` | Expose specialized tool schemas for subsequent requests within the active owner goal. |

## 2. Automatic body and survival behavior

- Eat automatically according to configured hunger thresholds and safe available food.
- Seek air and react to drowning risk.
- Attempt water-bucket fall mitigation when conditions and equipment allow.
- Defend against mobs and react to danger, including configured creeper avoidance.
- Pick up items according to configuration.
- Follow the owner or operate in free mode.
- Detect and attempt recovery from being stuck.
- Perform idle head movement, arm swings, and small collision-aware shuffles.
- Run body/survival control independently from slow model requests; tactical observations and open-ended LLM planning operate at different rates.

These mechanisms reduce latency for immediate reactions but are not guarantees of survival.

## 3. Planning, memory, and autonomy

- Separate strategic acceptance from individual tactical action success.
- Maintain rolling tactical plans and explicit dependency graphs.
- Validate dependencies and reject cycles.
- Repair failed plan suffixes without discarding verified progress.
- Verify final conditions against server evidence instead of trusting narrative completion.
- Detect stalls, try recovery actions, and retain fallback plans.
- Reserve resources and track temporary construction resources.
- Index inventory, equipment, observed drops, inspected storage, and known facilities.
- Record semantic facts with provenance, confidence, timestamps, expiry, and correlation.
- Retrieve working, episodic, semantic, procedural, and team memories relevant to the current objective.
- Persist goals, verified experiences, learned skills, and knowledge across restarts without restoring stale in-flight ownership.
- Recover journal/projection/checkpoint state and quarantine corrupt memory files.
- Investigate unfamiliar mechanisms with bounded budgets, risk assessment, hypotheses, counterexamples, and compensation rules.
- Require independent evidence before promoting discovered rules into reusable skills; invalidate contradicted or environment-stale rules.
- Track action transactions and callbacks to avoid duplicate or unsupported success reports.
- Maintain evidence-backed evolving preferences, goals and world knowledge with bounded reflection and persistence. Legacy persona data/accessors remain compatible, but randomly assigned persona and canned idle dialogue no longer drive the decision context. Actual model-driven behavior and cancellation-safe late evidence remain under integration.

## 4. Multiple companions

- Up to three companions per owner.
- Independent connection/session configuration and game mode per companion.
- Coordination among the same owner's companions through a shared blackboard.
- Roles, objective commitments, targeted support requests, duplicate-work detection, and expiring leases.
- Commitment reclamation after death, disconnection, or despawn.
- Companion persistence and restoration.

Current limitation: the same owner cannot create a second companion using the same model identifier. This matters with a single local model and has not been silently changed.

## 5. Game modes and interface

### Game modes

- Survival, Creative, and Adventure use Minecraft's game modes and restrictions.
- Per-companion Hardcore is Survival with permanent body death; changing mode or calling respawn cannot revive that body.
- Only the human owner can change a companion's game mode. The AI has no game-mode-switching tool.

### Interface

- Unified creation and model-connection setup.
- Chat with companion selection and live game-mode selection.
- Companion naming, renaming, skins, and default-skin restoration.
- Status HUD, path debugging, perception overlay, and companion labels.
- Rebindable keyboard controls: `M` menu, `C` chat, `H` HUD, `P` path debug, `V` perception overlay, and `N` labels.
- English and PT-BR UI resources are available. The server model language is configured independently from the client locale; full legacy runtime coverage and reload consistency remain under integration.

## 6. Local model connection

MineAgentX supports two local inference backends: **native Ollama** and **Bonsai/MTP through an OpenAI-compatible API**. No cloud-provider adapters are supported.

### Ollama

- Use the native Ollama chat protocol, `POST /api/chat`, with the server endpoint and model ID configured explicitly.
- A typical local server root is `http://127.0.0.1:11434`; this is an example, not evidence that Ollama is running or a specific model is installed.
- The model must support the agent tool-calling workflow; validate it against the actual server. Do not substitute a guessed installed model name.
- Partial Ollama configuration selects the native endpoint by default but never inherits the Bonsai model alias; a missing model must be configured explicitly.

### Bonsai/MTP


- Launcher directory on the owner's host: `<local-launcher>`.
- Documented launcher command: `./scripts/start.sh`, with the Katana profile as its recorded default.
- OpenAI-compatible API root: `http://127.0.0.1:8899/v1`.
- Recorded model alias: `swift-bonsai2-27b-katana-mtp`.
- Chat endpoint: `POST /v1/chat/completions`.
- The endpoint and model remain configurable for the local deployment. Loopback refers to the machine running the Minecraft server, not necessarily the player's client or this assistant's container.
- The companion connects over HTTP; it does not load a GGUF from the launcher directory, start the launcher, or change MTP, model, context allocation, or template settings.
- Automatic/default reasoning behavior preserves the server's template policy. New inputs accept blank/`auto`, not explicit cloud-style budgets. Restoring supported local saves migrates recognized legacy effort values to `auto` with a diagnostic; unsupported adapters and saved endpoints are not silently remapped.
- Request defaults, provider transport, and agent reasoning rounds share a 180-second local inference deadline. Explicit request deadlines remain supported; cancellation and current-task checks still apply. Output limits and actual model compatibility must be validated before claiming it works in-world.

No cloud-provider integrations are part of this fork's supported connection surface. A documented alias or endpoint is not proof that the service is currently running. A protocol regression test against a local test server is not a real Bonsai quality evaluation.

## 7. Built-in instructional knowledge

The repository includes skill documents for combat, containers, farming, equipment progression, Nether entry, blaze rods, ender pearls, stronghold finding, dragon combat, End progression, enchanting, brewing, villager trading, basic redstone, and a world atlas.

These are instructions available to the model, not guaranteed complete executable routines. Their presence does not prove autonomous dragon completion, working enchantment-menu automation, or universal modpack support.

## 8. Player commands

All commands use `/mineagent`:

| Command | Purpose |
| --- | --- |
| `help` | Show help. |
| `quick [name] [effort]` | Create a companion using saved configuration. |
| `spawn <name> <provider> <model> <apikey> [temperature]` | Legacy explicit creation syntax; local configuration through the UI is preferred. |
| `remove` | Permanently remove all companions owned by the caller and their saved state. |
| `respawn` | Respawn an eligible dead companion; not a permanently dead Hardcore body. |
| `list` | List active companions. |
| `providers` | List the supported local adapters: Ollama and Bonsai/OpenAI-compatible. |
| `models [provider]` | Show model information made available by the adapter; this is not proof that a model is loaded. |
| `config` | Show configuration without revealing the key. |
| `setconfig <key> <value>` | Save supported configuration fields. |
| `reload` | Reload the configuration file. |
| `mode` | Toggle or set follow/free mode, including for a named companion. |
| `locate` | Report the primary companion's position and state. |
| `tp` | Teleport the human player to the companion, not the other way around. |
| `setskin <playername\|default\|steve\|alex>` | Apply or reset a skin. |
| `resetskin` | Restore the default skin. |
| `rename <name>` | Rename the companion. |
| `seteffort [name] <auto>` | Keep the local server in control of reasoning/template policy; explicit cloud-style effort levels are not supported. |

## 9. Platform, limits, and future direction

- Minecraft **1.21.1**, Java **21**, **NeoForge only**. The Fabric implementation has been removed; its applicable regression tests are retained under NeoForge.
- NeoForge is this fork's only target. That project decision does not retroactively establish broad gameplay validation; the original implementation had less live-game coverage on NeoForge.
- Preserve the existing `mineagent` mod ID, package namespace, world data, and license attribution unless a separately authorized migration changes them.
- Local inference keeps model traffic on the configured local infrastructure. Sensitive chat/world context still reaches that server.
- Configuration/persistence can contain plaintext credentials if supplied; do not commit actual runtime configuration, keys, private chat, or worlds.
- Existing server-backed locators expose knowledge beyond a human player's direct observations.
- Legacy personality/emotion classes still exist for compatibility; evidence-backed identity replaces assigned personality in decision context. This is not a claim that every inherited class has been removed.
- Persistent coordinate designs, six-view previews, world transforms and physical construction dispatch are implemented; live-world behavior, optional visual review and complete lifecycle integration still require verification.
- Configurable EN/PT-BR is being integrated. Autonomous home/bed decisions and task-specific gameplay reliability still require model/gameplay validation.
- The mod remains alpha software. Back up worlds and test on a disposable copy before using a valued world.

### Source anchors

- `tools/src/main/java/com/mineagent/tools/ToolRegistration.java`: authoritative tool inventory.
- Tool classes under the same tree: schemas and dispatch semantics.
- `engine/src/main/java/com/mineagent/engine/MineAgentEngine.java`: commands, companion lifecycle, registration, and ownership.
- `engine/src/main/java/com/mineagent/engine/loop/AgentLoop.java`: planning/context orchestration and communication.
- `engine/src/main/java/com/mineagent/engine/survival/`: automatic survival and reflex behavior.
- `tools/src/main/resources/skills/`: instructional knowledge, distinct from executable skills.
