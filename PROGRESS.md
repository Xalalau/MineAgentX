# MineAgentX fork preparation

## Authorized scope

- Base: bingdongni/MineAgent, commit `701250a950154667212d3ae1be3b3197e9ce9470`.
- Checkout identity: MineAgentX.
- Local branch is `master`, tracking `origin/master`. The user-authorized `ours` merge preserved both histories without importing the older remote codebase. The newer NeoForge tree and unfinished work were retained. Privacy cleanup rewrote the affected local commits; the sanitized privacy commit has since been confirmed on the remote. Publication remains user-owned.
- `origin` now points to `https://github.com/Xalalau/MineAgentX.git`; stale branch tracking removed. Latest user instruction: prepare incremental local commits only; the user will push. Do not push, restart authentication, or force-push. Reconciliation preserves the existing remote history as an ancestor without importing the old 1.20.1 project content.
- Keep Minecraft 1.21.1, support only NeoForge, remove Fabric while preserving relevant regression tests.
- Keep only local inference: native Ollama `/api/chat` or the documented Bonsai/MTP OpenAI-compatible endpoint (`http://127.0.0.1:8899/v1`, model `swift-bonsai2-27b-katana-mtp`). Do not inspect or modify the personal host/launcher.
- Translate project content to English; preserve behavior and upstream attribution. The user explicitly authorized removing personal paths/names and private-repository references from files and history. Do not reintroduce them in documentation or commit messages.
- Add complete English `CAPABILITIES.md`, with an English rendering of the previous assistant checkpoint as its opening and local inference only in section 6.
- Exercise tests/build, inspect packaged JAR, and commit locally. Do not claim gameplay or real Bonsai inference without testing.

## Initial observations

- Initial worktree clean. No Java or GitHub CLI on PATH.
- The public destination initially described a different older project; its history was later reconciled without importing its source tree.
- At initial inspection, regression tests were under `fabric/src/test/java`; applicable tests have since been migrated to NeoForge.
- Initial Chinese content included documentation, locale data, comments, prompts, memory/personality labels and tests. English-content checks and the final translated-label regression review now pass.

## Privacy cleanup

- Removed personal machine paths/names and all references to private repositories from current project text. Removed the unpublished comparison report.
- Rewrote all affected local history while preserving merge topology and upstream attribution. Final verification scanned every locally stored Git object, including unreachable objects; no targeted privacy matches remained. Obsolete recovery objects and cleanup tooling were removed after restoring and verifying the unfinished work.
- Preserved all 352 working-file checksums/deletions across the privacy rewrite before completing and committing the technical integration.
- Added a personal-path publication guard and exercised it RED/GREEN. All three repository checks pass.
- Remote inspection with `git ls-remote` now confirms sanitized privacy commit `995face814496a30b3fafab67cfc0a0614c800f7` on `master`. The assistant performed no push. Previously exposed GitHub commit pages, caches, and other clones have not been verified purged. Do not merge contaminated history back in.

## Repository simplification

- Preserve the client UI design-system document. Its repository-relative link and read-before-UI-edit guidance are now in `AGENTS.md`; do not remove it as part of documentation cleanup.

- Removed contribution documentation and the entire GitHub configuration directory at the user's request, including issue/PR templates, release notes, and automatic build/release workflows. Updated the agent guide accordingly.
- Removed the inherited changelog at the user's request. Release history belongs in release notes rather than a separate tracked changelog.
- Local Gradle builds, regression tests, repository checks, license, and upstream attribution are retained. Removing GitHub automation does not remove the requirement to verify changes locally.

## Active player-like autonomy implementation

Previous preparation below is complete. New implementation is authorized and not yet verified.

- [ ] Default-safe, configurable player/companion combat; player-style world mutation protection and regressions.
- [ ] Inventory conservation, cancellation and bounded execution with truthful observations.
- [ ] Configurable English/PT-BR covering all model-facing language and new features.
- [ ] AI-composed farming, storage, shelter and transport decisions with progressive context/tool disclosure.
- [ ] Natural conversation without mandatory tags, mentions, special words or markup; preferences/goals/world memories influence decisions, not decorative roleplay.
- [ ] Persistent construction design/revision/site assessment/material accounting/six-view previews/local visual review/physical execution.
- [ ] Evidence-backed evolving identity, memory, periodic reflection and reasoned revision through conversation.
- [ ] Integration review, regression tests, clean build, artifact inspection, documentation and incremental local commits; no push.

Coordinator owns shared documentation and final integration. Disjoint implementation ownership is active:

| Workstream | Owned source | Coordinator integration still required |
| --- | --- | --- |
| Safety | Combat entry points/tasks, interaction/world-mutation guards, new safety contracts/tests | Apply server policy at initialization/reload and review all bypass paths. |
| Construction | New construction contracts, engine services, tools and tests | Register tools, connect persistence/lifecycle and optional visual review, inspect generated views. |
| Identity (handoff received) | New identity services/tool and memory persistence; AgentLoop ownership released | Tool registered; review persistence/context and verify the separate execution fixes. |
| Execution follow-up | AgentLoop, narrowly scoped execution helpers/tests, between-batch craft revocation | Resolve all eight cancellation gaps; release loop for final construction/vision hooks. |
| Localization foundation | Runtime language API/catalog, configuration and EN/PT-BR UI resources/tests | Wire configuration lifecycle and verify strict language/key handling. |
| Tool language migration | Remaining base-tool descriptions, schemas and results, excluding safety/inventory-owned files | Review actual producer coverage and preserve stable technical identifiers. |
| Context language migration | Existing world/cognition/planning/memory/skill/scan/exploration/theory producers, excluding identity-owned persistence | Review agent-loop input boundaries, instruction resources and legacy saved text. |
| Inventory | Transfer/drop/equip/collect/craft paths and conservation tests | Review cancellation findings and exercise Minecraft integration. |
| Coordinator | Provider/image contract, natural chat routing, registration/lifecycle, shared documentation and Git | Sole Gradle/build owner; review integrated results before completing checklist items. |

Current verified checkpoints (not final integration):

- Local commit `df10a86` contains bounded image transport. Actual loopback HTTP fixtures verify OpenAI-compatible text/image parts and native Ollama base64 images. The message contract preserves existing text constructors, rejects remote/invalid image data, and bounds each observation to six inline PNG/JPEG images of at most 2 MiB each. Strict RED/GREEN passed all five new tests; standalone provider regression selection passed 23 JUnit tests. Client preset tests require the Minecraft classpath and remain assigned to final Gradle verification.
- Natural conversation entry now preserves literal owner speech rather than parsing special tags or mentions; bilingual natural speaker attribution uses the shared runtime language API. Peer speech retains the separate teammate channel and no longer uses a `from=... message=...` protocol. Three standalone conversation tests pass, including actual formatter behavior and supplementary source-wiring checks. This remains uncommitted until the shared locale foundation is reviewed and committed. It has not been exercised in a running game.
- Recovery helpers needed by the coordinator are in ignored `.tools/parent-test/`: `python .tools/parent-test/run.py vision` and `python .tools/parent-test/run.py conversation` reproduce the standalone checkpoints. These intentionally do not replace the final Gradle suite. Other agents own their ignored work directories and must report indispensable output paths.
- The inventory handoff was reviewed and its standalone runner re-executed by the coordinator: 28 tests, zero failures/errors/skips in three XML reports. Transfer/equip use real menu slots and partial extraction/insertion rules; drop uses normal toss delivery with refused-attempt restoration; craft preflights complete output/remainders and reports committed batches despite later notification failure. This does not verify gameplay, protection-mod hooks or the final integrated build. Recovery: `.tools/inventory-work/run_tests.py`, `reports/`, `reports-red-drop/` and detailed `.tools/inventory-work/review.md`.
- Collection handoff received and its four source/test files reviewed. Coordinator independently reran `python .tools/collection-work/run_tests.py green-parent-review`: 17 tests, zero failures/errors/skips in actual XML. `CollectItemsTask` now refuses oversized targets before navigation/touch without splitting entities, retains original metadata, counts only corroborated exact-player/exact-entity Post receipts with matching components, reports exhausted partial delivery as failure and supports EN/PT-BR. The source-root and partial-success defects in the first Gradle snapshot are covered by this passing selection. Recovery: `.tools/collection-work/review.md`, `reports-green-parent-review/`, and the documented behavioral RED reports. Automatic collision pickup remains outside this receipt scope and can exceed the request; no live hooks/GameTest were exercised. Final integrated Gradle rerun remains pending. Ownership of the four collection files is released; this does not release the execution agent's AgentLoop/CraftTool ownership.
- The inventory review identifies eight untested cancellation gaps in `.tools/inventory-work/review.md`: queued deadlines, late callback side effects, non-atomic completion/payload publication, off-thread scheduler cancellation, exact-task cancellation races, accepted tasks surviving turn cancellation, absent between-batch revocation, and the separate skill dispatch path. The identity owner has released `AgentLoop`; a dedicated execution follow-up now owns that loop and minimal craft token wiring. No cancellation fix is claimed yet.
- Local commit `79c74fa` records the reviewed strict EN/PT-BR API/configuration foundation, client catalogs and its 30 standalone tests. This is not a claim of complete legacy localization. Repository checks currently pass 3/3; no push was performed.
- Four main handoffs have arrived and were inspected. Coordinator independently reproduced 30 foundation tests, 21 safety tests, 8 construction tests and 31 identity/integration tests; these overlap other selections and are NOT an additive final total. The actual six-view contact sheet was visually inspected: all six labeled projections are visible and differ consistently; colored voxels are not game-texture screenshots. Recovery runners: `.tools/i18n-work/run_tests.py`, `.tools/parent-test/run.py safety`, `.tools/construction-work/check.py`, `.tools/identity-work/compile.py` followed by `verify_integration.py`; handoff maps remain in the respective ignored work directories.
- Coordinator exercised four additional RED/GREEN source-wiring guards supplementing the executable suites: init/reload apply language/combat policy; connection/command updates preserve all eight config fields; identity/construction tools are registered; temporary cleanup runs under its restrictive cause instead of explicit-mining authority. Without authoritative ownership, automatic temporary cleanup fails closed.
- First full Gradle snapshot compiled all API/production/client/test sources, then failed tests: actual XML reports 355 tests, 3 failures, 0 errors/skips. Failures are the active context localization test (new probe event remains English), collection source-root resolution under Gradle, and collection labeling 2/5 pickup complete. Both active owners received exact findings; do not repair their files concurrently or remove assertions. Build is NOT verified successful.
- Remaining coordinator hooks include automatic construction terminal reconciliation, configured optional local visual review, per-request locale consistency and command/literal coverage. Reflection interval/disable config is not wired to the identity gate yet (it still uses a fixed 1200-tick interval). Construction storage currently uses owner + exact name; rename migration is unresolved. No real visual model or gameplay was exercised. Do not mark broad checklist items complete from these partial checkpoints.

## Completed preparation

- Maintained `AGENTS.md` maps source files, design intent, local protocols, verification, privacy, Git ownership, and parallel-agent coordination. The preserved UI design-system document is linked and updated for NeoForge and local providers; stale cross-loader source comments were corrected without altering rendering behavior.
- `CAPABILITIES.md` opens with the preceding checkpoint in English, inventories all 40 registered tools and player commands, and restricts section 6 to local inference. Final source cross-check corrected `remove` to describe removal of all companions owned by the caller; `seteffort` already documents `auto` only. README, command help, UI guidance, prompts, memory/persona text, and runtime protocol labels are aligned with the English/local-only fork. Inherited persona/emotion behavior was translated, not removed.
- Removed Fabric integration and unsupported cloud providers while preserving applicable NeoForge regression tests. Native Ollama and Bonsai/MTP remain independently supported; model aliases and private-LAN endpoints are editable, and launcher settings are not modified.
- Independent review found no translated producer/consumer label mismatch. Closed five concrete integration issues: malformed tool-call envelopes, invalid/duplicate explicit call IDs, provider-aware partial configuration defaults, runtime effort UI/server mismatch, and restoration of legacy local effort values.
- Partial Ollama configuration uses its native endpoint and requires an explicit model rather than inheriting a Bonsai alias. Explicit connection overrides remain intact; unsupported adapters are not silently remapped.
- Shared reasoning policy accepts blank/`auto`. Only restoration of recognized effort levels for the two supported local adapters migrates to `auto`, with a diagnostic and unchanged endpoint validation. Runtime Settings reports commands as queued, not prematurely applied.
- `LLMRequest.DEFAULT_DEADLINE` supplies the shared 180-second request/provider/agent-round default. Explicit deadlines and cancellation remain supported. Valid server tool IDs are preserved exactly; omitted/null IDs receive unique internal IDs; malformed announced calls and ambiguous explicit IDs enter the retryable malformed-response path. Text/reasoning-only replies remain valid.
- Focused fixes were exercised RED/GREEN. Standalone review checks passed 11 configuration tests and 8 reasoning-policy/wiring tests before final integration. These tests are included in the complete JUnit run below, not added to its total.

## Preparation verification (historical baseline)

These results cover the completed fork preparation, not the active autonomy implementation above. Its final integrated verification remains pending.

- Verified portable Temurin JDK 21 archive checksum `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`. The ignored JDK directory is `.tools/jdk-21.0.12.1+1`; Gradle wrapper version is 8.10.2.
- After all delegated source edits, `:neoforge:clean :neoforge:test :neoforge:build` exited 0. A final test/build rerun after the remaining locale-text correction also exited 0.
- Parsed actual JUnit XML under `neoforge/build/test-results/test/`: **30 suites, 139 tests, zero failures, errors, or skipped tests**. This supersedes all earlier partial snapshots.
- All three `python scripts/check_project.py` checks and `git diff --check` passed. Independent protocol fixtures exercise actual HTTP requests against temporary loopback test servers, not a real loaded model.
- Inspected `neoforge/build/libs/mineagentx-neoforge-0.4.0.jar`: CRC passes; metadata identifies MineAgentX 0.4.0; required API/engine/tool/client classes and English resources are present; original license bytes match exactly; all 493 classes use Java major version 65; unsupported provider/Fabric/test classes and targeted privacy strings are absent.
- JAR SHA-256: `4fbdb6cfadabff6aaffac237ad6b36ae3f4a8d81865918a53dbf9e5a8824c738`.
- Reviewed implementation, English runtime, regression tests, capability documentation, and agent-guide changes are committed locally in `e6f0938` (`feat: finish local-only inference and English runtime`). This ledger is maintained in a separate documentation commit. All assistant commits use the explicit identity `Hermes Agent <hermes@localhost>`.
- No Minecraft installation/gameplay session or live Ollama/Bonsai inference was performed. The future construction design/preview/revision environment and additional configurable localization layers were not implemented as part of this preparation. Push remains user-owned; no authentication or push is required from the assistant.
