# MineAgentX agent guide

## Read first and keep current

Read this file, `PROGRESS.md`, and the relevant source before editing. This guide is the canonical project map and design intent; `PROGRESS.md` is the single task/recovery ledger, and `CAPABILITIES.md` describes implemented capabilities and limitations.

**Keep this guide updated as work proceeds.** When a change adds, moves, or removes a module, important file, protocol, validation command, or supported feature, update the corresponding section in the same change before committing. Replace obsolete statements instead of appending contradictory warnings. Do not duplicate transient task status or test totals here.

The coordinating agent owns shared documentation updates unless explicitly delegated. Every subagent must read this guide, respect assigned file ownership, and return any necessary documentation corrections with its findings. The coordinator integrates those corrections while other agents work, not only at the end.

## Project identity and current direction

- Minecraft **1.21.1**, **NeoForge 21.1.x only**, **Java 21**. No Fabric module, Forge 1.20.1, Architectury migration, or cloud inference.
- Current codebase derives from `bingdongni/MineAgent` at `701250a950154667212d3ae1be3b3197e9ce9470`. Preserve upstream attribution and the LGPL-3.0-only license.
- Preserve the `mineagent` mod ID, Java package namespace, save formats, and network contracts unless a separately authorized migration changes them. The displayed project/artifact name is MineAgentX.
- Source identifiers, comments, and documentation remain English. Runtime language is configurable: `en_us` by default and `pt_br` as the first additional language. Every new model-facing instruction, tool description, observation, result, memory, and player-facing message must support both. Technical identifiers remain stable. Do not implement localization as a fixed natural-language intent parser.
- Local inference has **two supported options: native Ollama and Bonsai/MTP via an OpenAI-compatible API**. Do not accidentally remove Ollama when removing cloud providers.
- Favor general, composable actions and verified outcomes over hardcoded task scripts. The AI should decide how to achieve an objective; deterministic execution, permissions, safety checks, cancellation, and truthful result reporting still matter.

## Current development requirements

- Build player-like general intelligence from compact context, discoverable capabilities, lived observations, and reasoned memory rather than predetermined routines. Farming, logistics, shelter, transport, and construction are goals the model composes.
- Player/companion attacks are denied by default and explicitly configurable by the server; enforce the policy at execution, not only in prompts. Preserve ordinary player permissions, item accounting, reach, visibility, and cancellation. Recovery must not destroy player assets.
- Construction requires persistent versioned designs, material/effort estimates, candidate-site assessment, explicit terrain-change awareness, six orthographic previews, optional local visual-model review, revisions, world transforms, and physical block execution with observed outcomes.
- Replace inherited randomly assigned personality/canned dialogue with evidence-backed evolving preferences and periodic bounded reflection. Store reasons and revisions; persuasion can change beliefs without automatically erasing identity.
- Expose only a small useful entry surface; reveal detailed tools, design records, and memory when relevant. Keep deterministic math/accounting automatic, without extra reasoning calls.
- Write model instructions as general principles, decision criteria, constraints, and questions to consider. Avoid worked examples, scripted dialogue, sample personalities, prescribed designs, and fixed action sequences: the local model can imitate them instead of reasoning from current evidence. Express required tool syntax concisely through schemas, separate from behavioral guidance. Evaluate decisions across varied situations rather than rewarding imitation of a reference answer.
- Communication is ordinary conversation: do not require player tags, `@name`, special words, or markup. Model instructions should use natural prose, not a tag-based dialogue protocol. Keep technical IDs internal and distinguish actual speakers, observations, preferences, and goals without turning chat into commands. Likes, goals, and remembered world facts must affect real choices and remain revisable through experience and reasoned conversation.

## Design preferences: do not silently undo these

- Do not carry over fixed natural-language shortcuts merely because the old project has them; they can intercept intent incorrectly.
- Do not introduce predefined personalities or canned spontaneous dialogue. Inherited persona/emotion code is still present: translating it is not the same as removing it. Report that distinction honestly.
- Let the AI reason about home, bed, rest, and shelter rather than depending on dedicated player-facing commands for each concept.
- Do not port the old predefined cottage. The desired future building system is **design in coordinates, visualize, revise until satisfactory, map design coordinates to world coordinates, then build through normal companion actions**.
- Existing `build` executes block-coordinate operations; it is not that future design/preview environment. Preserve navigation, reach/visibility checks, item use, and post-placement verification rather than adding arbitrary-distance world editing.
- Legacy optional-mod integrations are not a priority. Do not automatically port Cobblemon, AE2, backpack, team, gadget, or artifact integrations.
- Base architecture changes on inspected public source and verified requirements. Do not import private-repository references or comparison reports into this project.

Release history belongs in release notes, not a tracked changelog. Do not recreate a standalone changelog unless requested.

## Repository map

All paths below are relative to this repository unless explicitly labeled external.

| Path | Responsibility |
| --- | --- |
| `README.md` | Overview, local connection setup, build/install entry points. |
| `CAPABILITIES.md` | Complete registered-tool inventory, slash commands, automatic behavior, and limitations. Section 6 covers local models only. |
| `PROGRESS.md` | Authorization, exact recovery state, completed checks, remaining integration work, and blockers. Read after interruption or compaction. |
| `LICENSE` | Original LGPL-3.0-only license; preserve upstream attribution. |
| `settings.gradle`, `build.gradle`, `gradle.properties` | Module graph, common build/test settings, Minecraft/NeoForge versions, identity. Inspect current values rather than guessing versions. |
| `api/src/main/java/com/mineagent/api/` | Loader-independent configuration, model/tool/task contracts, companion game modes, payload contracts, platform API. |
| `engine/src/main/java/com/mineagent/engine/` | Companion runtime and action execution. |
| `engine/src/client/java/com/mineagent/engine/client/` | Shared client screens, controls, rendering, and UI model/preset code. |
| [Client UI design system](engine/src/client/java/com/mineagent/engine/client/ui/DESIGN_SYSTEM.md) | Visual hierarchy, colors, layout, and screen responsibilities. Read before changing client UI. Preserve this document; the user explicitly requested keeping it. |
| `engine/src/client/resources/assets/mineagent/lang/en_us.json` | Base English UI translations. |
| `tools/src/main/java/com/mineagent/tools/` | AI-callable tool implementations and authoritative registration. |
| `tools/src/main/resources/skills/` | Instructional knowledge supplied to the model; not automatically executable routines. |
| `neoforge/src/main/java/com/mineagent/neoforge/` | Only loader entry point, platform adapter, payload registration, client integration, and mixins. |
| `neoforge/src/main/resources/META-INF/` | Mod metadata and access transformer. |
| `neoforge/src/test/java/` | Migrated upstream regressions and new local-provider/English-protocol tests. Keep applicable tests when removing unsupported implementations. |
| `scripts/check_project.py` | Repository gates: English project text, no personal machine paths, and documented coverage of registered tools. |
| `neoforge/build/libs/` | Generated installable NeoForge JAR; never mistake a source/archive download for an installable mod. |

### Important runtime entry points

- `engine/.../MineAgentEngine.java`: engine initialization, `/mineagent` command registration, companion lifecycle, ownership, and provider registration. Enumerate slash commands from this registration, not only help text.
- `engine/.../conversation/ConversationDelivery.java`: language-aware natural speaker attribution. `MineAgentEngine.onPlayerChat` preserves literal speech, without tag/mention parsing, and targets the owner's primary companion; peer speech uses the separate teammate channel, not owner-command handling.
- `engine/.../loop/AgentLoop.java`: model context, reasoning rounds, native tool dispatch, tactical/strategic orchestration, summaries, and transport deadlines. This notation abbreviates `engine/src/main/java/com/mineagent/engine/`.
- `api/.../execution/ExecutionToken.java`, `engine/.../execution/ExecutionProtocol.java`, `ExecutionOwner.java`: shared direct-tool/skill admission, monotonic deadline and generation checks, atomic terminal/payload publication, retained task leases and server-queued exact-task cancellation. Craft observes revocation between atomic batches, without rolling back committed receipts. Late receipts are separate observations, not retroactive success. The enclosing `SkillRuntime` carries typed verified/unverified/cancelled authority, arms callbacks before dispatch, rechecks exact run/action afterward and freezes terminals before cleanup. Uncertainty cannot train failure, retry or advance steps. Retain async authority until its terminal receipt; remove definitive non-admissions that cannot produce one.
- `engine/.../entity/`: fake-player body, inventory, game-mode interaction, and companion persistence.
- `engine/.../act/`, `pathing/`, `scan/`, `survival/`: block/entity interactions, physical navigation, observations, and fast body/survival control. Keep server-owned world access on the proper thread.
- `engine/.../planning/`, `cognition/`, `world/`, `task/`, `scheduler/`: plan/dependency repair, teamwork, semantic facts/assets, action transactions, reservations, and asynchronous task execution.
- `engine/.../memory/`, `exploration/`, `skill/`: memories, bounded mechanism experiments, learned skills and their evidence checks. Match producer/consumer labels when translating prompts.
- `engine/.../persona/`, `behavior/`, `theory/`: inherited compatibility classes, physical idle behavior, and owner-intent estimation. Their continued existence is not permission to reintroduce assigned persona or canned dialogue into decisions.
- `tools/.../ToolRegistration.java`: authoritative tool catalog. Follow each tool into its executor before claiming a capability works.
- `tools/.../inventory/InventorySlotTransactions.java`: real menu-slot transfer/equip rules and verified drop receipts; preserve backing-slot aliases, component identity and extraction/insertion restrictions. `InventoryCraftTransactions.java` preflights a transient write set against the actual inventory before committing a complete recipe batch; it is not a second persistent inventory. `InventoryMessages.java` selects EN/PT-BR through the shared runtime language. The collection tool only admits a task; `engine/.../task/CollectItemsTask.java` owns physical pickup and terminal observations. `CollectionPickupObservation.java` corroborates a scoped exact-player/exact-entity Post event with immediate matching item/component gain. Never clone/split an oversized target to force a cap; refuse it before further navigation/touch and retain its metadata. Exhausted partial delivery is failure, with observed/requested/remaining counts. Automatic collision pickup outside the observed touch is not counted and is not globally capped; live hooks remain unverified.
- `api/.../config/MineAgentConfig.java`: defaults and persistence for runtime `config/mineagent.json`, including exact server language, default-denied combat, explicitly configured optional vision and bounded reflection settings. Preserve all eight fields when updating settings; the legacy four-argument constructor supplies defaults, not a configuration copy. `MineAgentEngine.applyConfiguration` activates validated language/combat on init/reload.
- `engine/.../i18n/ExecutorMessages.java`: trusted task/survival prose for active execution, including cancellation fallbacks. `SchedulerMessages.java` covers scheduler admission/lifecycle/fallback/pause/UI/log prose without changing transitions or transport. Preserve technical diagnosis markers and literal observed arguments; ready task messages and raw historic/external diagnostics are not translated.
- `api/.../i18n/`: strict bilingual catalogs, exact language IDs, shared server selection, deferred localized records, external-text attribution and schema metadata localization. Client locale remains separate. Preserve literal external/user history; wrapping English data in Portuguese does not translate it.
- `api/.../safety/` and `engine/.../safety/`: runtime combat policy, scoped mutation causes, execution guards and NeoForge event subscribers. Temporary cleanup is scoped as `TEMPORARY_CLEANUP`, never legacy explicit mining; unknown ownership, including same-type replacement, does not authorize automatic removal.
- `api/.../construction/`, `engine/.../construction/`, `tools/.../construction/`: immutable versioned designs, disk repository, site assessment, material accounting, deterministic six-view PNG renderer and bounded execution through existing `BuildTool`. `construction` and `execute_construction` are registered; optional advisory visual review is exposed as `review`/`review_status` and uses only explicit `config.vision()`. Bounded workers receive immutable six-view requests; server-thread polling publishes only revision/configuration-current evidence. `AgentLoop.onTaskFinished` reconciles exact dispatched coordinates before obsolete-skill learning rejection. Rename is rejected for existing/unknown owner-name namespaces rather than inventing a migration.
- `engine/.../identity/`, `tools/.../identity/`: evidence-backed evolving identity, bounded reflection and native `identity_memory` retrieval/revisions. `AgentLoop` attaches persistence and current identity context. Legacy persona saves/accessors remain compatible; no assigned persona/canned idle dialogue in the decision context. Typed callback authority rejects stale learned outcomes while retaining partial physical observations. The reflection gate reads the current server configuration dynamically, including interval zero to disable/revoke open opportunities and reload for existing loops; it does not introduce HTTP calls.
- `api/.../llm/LLMRequest.java`, `LLMResponse.java`, `ChatMessage.java`: transport contracts, deadlines, conversation history, tool calls/results. `ChatMessage.userWithImages` adds bounded inline visual observations while preserving the existing text-only constructor and saved-history defaults.
- `api/.../llm/model/ThinkingEffortSpec.java`: shared automatic reasoning input policy and restricted migration of legacy local effort values during restoration.
- `engine/.../llm/OpenAICompatibleProvider.java`, `OllamaNativeProvider.java`: the two supported wire-protocol implementations; shared validation/serialization lives alongside them.
- `engine/src/client/java/com/mineagent/engine/client/ui/ProviderPresets.java` and `screen/SpawnCompanionScreen.java`: supported local choices, model/endpoint editing, and connection setup.

## Local inference contracts

| Backend | Configuration and protocol |
| --- | --- |
| Bonsai/MTP | Documented API root `http://127.0.0.1:8899/v1`; `POST /v1/chat/completions`; configurable recorded alias `swift-bonsai2-27b-katana-mtp`. |
| Ollama | Native `POST /api/chat`; typical root `http://127.0.0.1:11434`; the actual installed model must be configured, never guessed. |

- Loopback refers to the machine running the Minecraft server, not necessarily the player's client or this assistant's container. Local/private-LAN endpoint validation must remain consistent in presets, spawn/configuration, and request execution.
- Preserve the server's automatic reasoning/template policy: new inputs accept only blank/`auto`. `ThinkingEffortSpec` is shared by UI and server. During restoration only, recognized legacy effort levels for the two supported local adapters migrate to `auto` with a diagnostic; never silently remap unsupported providers or saved endpoints.
- Partial configuration defaults are provider-aware: Ollama uses its native endpoint but requires an explicit model; Bonsai keeps its documented defaults. Preserve explicit endpoint/model overrides.
- Reject malformed announced tool-call structures and blank/non-string/duplicate explicit IDs through the retryable malformed-response path. Preserve valid IDs exactly; generate unique IDs only when absent/null. Text/reasoning-only responses without calls remain valid.
- Do not inject cloud-only reasoning fields, launcher MTP flags, or assume a model's advertised maximum is the live context allocation.
- `LLMRequest.DEFAULT_DEADLINE` is the shared 180-second local inference default used by provider transport and every `AgentLoop` reasoning round. Preserve explicit caller deadlines and cancellation. The shorter connection-establishment timeout is separate from the inference deadline; check all layers together when changing either.
- The external launcher reference is `<local-launcher>`. The mod connects over HTTP; it does not load files from that directory or start/modify the launcher.
- Never load or download models, alter the external launcher, or treat an unrelated installed model as a suitable companion model without authorization.
- Visual observations use at most six inline PNG/JPEG data URLs, at most 2 MiB decoded per image. OpenAI-compatible requests encode text/image content parts; native Ollama receives base64 in `images`. Remote image URLs and malformed payloads are rejected. A separate, explicitly configured local visual model must support images; do not infer vision capability from a model name or enable/download a model automatically.
- Protocol fixtures prove request/response handling, not real model quality, MTP operation, or in-world behavior.

## Build and verification

Run from the repository with **JDK 21**. Explicitly set `JAVA_HOME` and prepend its `bin` in every Java-dependent tool call if environment persistence is uncertain. An ignored portable JDK may be present under `.tools/`; inspect its actual directory before using it.

```sh
python scripts/check_project.py
bash ./gradlew --no-daemon --console=plain --max-workers=2 :neoforge:test :neoforge:build
git diff --check
```

Use `gradlew.bat` on Windows. `clean` is optional for a deliberate clean verification, not something to run while another agent owns an active Gradle build.

Before calling a change done:

1. Follow RED/GREEN for behavior changes. Exercise the real code, not only source-text assertions.
2. Ensure new tests are registered with JUnit: a class containing only `main` is not executed by `:neoforge:test`.
3. Parse JUnit XML under `neoforge/build/test-results/test/`; require actual tests and inspect failures/errors/skips. `NO-SOURCE` is not successful regression coverage.
4. Re-run final tests/build after all relevant parallel source edits finish. An earlier successful snapshot is not final integration evidence.
5. Inspect the JAR with `zipfile`: CRC, expanded NeoForge metadata, API/engine/tool/client classes and required resources, exact license, no Fabric/cloud/test-only classes, and Java class major version 65. Compute its SHA-256 when delivering a binary.
6. Report build/protocol results separately from gameplay and live inference. Do not claim either was tested without actual execution.
7. Keep README, capabilities, this guide, and the progress ledger consistent with supported behavior.

## Parallel work and Git rules

- Assign disjoint file ownership and include the current user decisions in every delegation. Subagents cannot infer newer corrections from older task titles.
- Only one owner runs Gradle at a time. Coordinate compile/test tasks rather than racing shared build outputs.
- Subagents must not commit, push, change branches, reset, clean, stage shared files, or edit another owner's files unless explicitly assigned. Return exact changed paths, test commands/results, limitations, and documentation deltas.
- The coordinator reviews and commits ready logical changes incrementally, staging explicit paths instead of all pending work. Preserve originals and unfinished work.
- Branch: **`master`**, tracking **`origin/master`**. Destination: `https://github.com/Xalalau/MineAgentX.git`.
- **The user handles pushes. Do not push or restart GitHub authentication.** Never force-push or overwrite remote history.
- The remote originally contained a different Minecraft 1.20.1 project. Its history was preserved by an explicit `ours` merge while retaining this NeoForge codebase. Do not reimport that old tree or assume renaming a branch fixes divergence. Fetch/inspect ancestry before future history changes; never silently reset/rebase away new work.
- Do not invent the user's commit identity. Use an explicitly configured identity or transparently identified assistant commits; the existing local assistant commits use `Hermes Agent <hermes@localhost>`.

## Runtime data and privacy

- Runtime configuration, companion saves (`<world>/data/mineagent_companions.json`), and memories (`<world>/mineagent_memory/`) are user data. Never commit actual keys, private chat, logs, worlds, or inference state. Do not inspect unrelated runtime files.
- Keep task-owned temporary artifacts minimal and ignored. `.tools/` and generated build outputs are not public deliverables.
- Never access, modify, or restart an external model launcher or game server without explicit authorization.
- Never put personal names, machine usernames, personal filesystem paths, or private-repository references in project text, public artifacts, or commit messages. Use repository-relative references and generic setup instructions. Redact diagnostics before copying them; verify reachable Git history as well as current files after privacy corrections.
