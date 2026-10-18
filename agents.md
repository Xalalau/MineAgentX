# MineAgentX agent guide

## Read first and keep current

Read this file, `PROGRESS.md`, and the relevant source before editing. This guide is the canonical project map and design intent; `PROGRESS.md` is the single task/recovery ledger, and `CAPABILITIES.md` describes implemented capabilities and limitations.

**Keep this guide updated as work proceeds.** When a change adds, moves, or removes a module, important file, protocol, validation command, or supported feature, update the corresponding section in the same change before committing. Replace obsolete statements instead of appending contradictory warnings. Do not duplicate transient task status or test totals here.

The coordinating agent owns shared documentation updates unless explicitly delegated. Every subagent must read this guide, respect assigned file ownership, and return any necessary documentation corrections with its findings. The coordinator integrates those corrections while other agents work, not only at the end.

## Project identity and current direction

- Minecraft **1.21.1**, **NeoForge 21.1.x only**, **Java 21**. No Fabric module, Forge 1.20.1, Architectury migration, or cloud inference.
- Current codebase derives from `bingdongni/MineAgent` at `701250a950154667212d3ae1be3b3197e9ce9470`. Preserve upstream attribution and the LGPL-3.0-only license.
- Preserve the `mineagent` mod ID, Java package namespace, save formats, and network contracts unless a separately authorized migration changes them. The displayed project/artifact name is MineAgentX.
- English is the initial language of documentation, code comments, prompts, UI, and built-in data. Future localization should layer over English through configuration, not introduce duplicated language-specific logic or fixed Portuguese command routing.
- Local inference has **two supported options: native Ollama and Bonsai/MTP via an OpenAI-compatible API**. Do not accidentally remove Ollama when removing cloud providers.
- Favor general, composable actions and verified outcomes over hardcoded task scripts. The AI should decide how to achieve an objective; deterministic execution, permissions, safety checks, cancellation, and truthful result reporting still matter.

## Design preferences: do not silently undo these

- Do not carry over fixed natural-language shortcuts merely because the old project has them; they can intercept intent incorrectly.
- Do not introduce predefined personalities or canned spontaneous dialogue. Inherited persona/emotion code is still present: translating it is not the same as removing it. Report that distinction honestly.
- Let the AI reason about home, bed, rest, and shelter rather than depending on dedicated player-facing commands for each concept.
- Do not port the old predefined cottage. The desired future building system is **design in coordinates, visualize, revise until satisfactory, map design coordinates to world coordinates, then build through normal companion actions**.
- Existing `build` executes block-coordinate operations; it is not that future design/preview environment. Preserve navigation, reach/visibility checks, item use, and post-placement verification rather than adding arbitrary-distance world editing.
- Legacy optional-mod integrations are not a priority. Do not automatically port Cobblemon, AE2, backpack, team, gadget, or artifact integrations.
- Use the old project as evidence and reference, not as an automatic source of architecture. Reuse decisions belong in the comparison report under `docs/` and require source-backed justification.

## Repository map

All paths below are relative to this repository unless explicitly labeled external.

| Path | Responsibility |
| --- | --- |
| `README.md` | Overview, local connection setup, build/install entry points. |
| `CAPABILITIES.md` | Complete registered-tool inventory, slash commands, automatic behavior, and limitations. Section 6 covers local models only. |
| `PROGRESS.md` | Authorization, exact recovery state, completed checks, remaining integration work, and blockers. Read after interruption or compaction. |
| `CONTRIBUTING.md`, `LICENSE` | Contribution rules and original license. |
| `settings.gradle`, `build.gradle`, `gradle.properties` | Module graph, common build/test settings, Minecraft/NeoForge versions, identity. Inspect current values rather than guessing versions. |
| `api/src/main/java/com/mineagent/api/` | Loader-independent configuration, model/tool/task contracts, companion game modes, payload contracts, platform API. |
| `engine/src/main/java/com/mineagent/engine/` | Companion runtime and action execution. |
| `engine/src/client/java/com/mineagent/engine/client/` | Shared client screens, controls, rendering, and UI model/preset code. |
| `engine/src/client/resources/assets/mineagent/lang/en_us.json` | Base English UI translations. |
| `tools/src/main/java/com/mineagent/tools/` | AI-callable tool implementations and authoritative registration. |
| `tools/src/main/resources/skills/` | Instructional knowledge supplied to the model; not automatically executable routines. |
| `neoforge/src/main/java/com/mineagent/neoforge/` | Only loader entry point, platform adapter, payload registration, client integration, and mixins. |
| `neoforge/src/main/resources/META-INF/` | Mod metadata and access transformer. |
| `neoforge/src/test/java/` | Migrated upstream regressions and new local-provider/English-protocol tests. Keep applicable tests when removing unsupported implementations. |
| `scripts/check_project.py` | Repository gates: no Chinese project text and documented coverage of registered tools. |
| `.github/workflows/build.yml`, `.github/workflows/release.yml` | NeoForge test/build and artifact/release automation. |
| `neoforge/build/libs/` | Generated installable NeoForge JAR; never mistake a source/archive download for an installable mod. |

### Important runtime entry points

- `engine/.../MineAgentEngine.java`: engine initialization, `/mineagent` command registration, companion lifecycle, ownership, and provider registration. Enumerate slash commands from this registration, not only help text.
- `engine/.../loop/AgentLoop.java`: model context, reasoning rounds, native tool dispatch, tactical/strategic orchestration, summaries, and transport deadlines. This notation abbreviates `engine/src/main/java/com/mineagent/engine/`.
- `engine/.../entity/`: fake-player body, inventory, game-mode interaction, and companion persistence.
- `engine/.../act/`, `pathing/`, `scan/`, `survival/`: block/entity interactions, physical navigation, observations, and fast body/survival control. Keep server-owned world access on the proper thread.
- `engine/.../planning/`, `cognition/`, `world/`, `task/`, `scheduler/`: plan/dependency repair, teamwork, semantic facts/assets, action transactions, reservations, and asynchronous task execution.
- `engine/.../memory/`, `exploration/`, `skill/`: memories, bounded mechanism experiments, learned skills and their evidence checks. Match producer/consumer labels when translating prompts.
- `engine/.../persona/`, `behavior/`, `theory/`: inherited personality, idle behavior, and owner-intent estimation. Do not advertise these as removed.
- `tools/.../ToolRegistration.java`: authoritative tool catalog. Follow each tool into its executor before claiming a capability works.
- `api/.../config/MineAgentConfig.java`: defaults and persistence for runtime `config/mineagent.json`.
- `api/.../llm/LLMRequest.java`, `LLMResponse.java`, `ChatMessage.java`: transport contracts, deadlines, conversation history, tool calls/results.
- `engine/.../llm/OpenAICompatibleProvider.java`, `OllamaNativeProvider.java`: the two supported wire-protocol implementations; shared validation/serialization lives alongside them.
- `engine/src/client/java/com/mineagent/engine/client/ui/ProviderPresets.java` and `screen/SpawnCompanionScreen.java`: supported local choices, model/endpoint editing, and connection setup.

## Local inference contracts

| Backend | Configuration and protocol |
| --- | --- |
| Bonsai/MTP | Documented API root `http://127.0.0.1:8899/v1`; `POST /v1/chat/completions`; configurable recorded alias `swift-bonsai2-27b-katana-mtp`. |
| Ollama | Native `POST /api/chat`; typical root `http://127.0.0.1:11434`; the actual installed model must be configured, never guessed. |

- Loopback refers to the machine running the Minecraft server, not necessarily the player's client or this assistant's container. Local/private-LAN endpoint validation must remain consistent in presets, spawn/configuration, and request execution.
- Preserve the server's automatic reasoning/template policy. Do not inject cloud-only reasoning fields, launcher MTP flags, or assume a model's advertised maximum is the live context allocation.
- Check timeouts end to end: provider HTTP defaults, `LLMRequest`, and the explicit `AgentLoop` deadline can differ. Do not claim a longer deadline is implemented merely because one layer changed.
- The external launcher reference is `<local-launcher>`. The mod connects over HTTP; it does not load files from that directory or start/modify the launcher.
- Never load or download models, alter the external launcher, or treat an unrelated installed model as a suitable companion model without authorization.
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
