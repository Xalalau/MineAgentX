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

## Completed preparation

- Maintained `AGENTS.md` maps source files, design intent, local protocols, verification, privacy, Git ownership, and parallel-agent coordination. The preserved UI design-system document is linked and updated for NeoForge and local providers; stale cross-loader source comments were corrected without altering rendering behavior.
- `CAPABILITIES.md` opens with the preceding checkpoint in English, inventories all 40 registered tools and player commands, and restricts section 6 to local inference. Final source cross-check corrected `remove` to describe removal of all companions owned by the caller; `seteffort` already documents `auto` only. README, command help, UI guidance, prompts, memory/persona text, and runtime protocol labels are aligned with the English/local-only fork. Inherited persona/emotion behavior was translated, not removed.
- Removed Fabric integration and unsupported cloud providers while preserving applicable NeoForge regression tests. Native Ollama and Bonsai/MTP remain independently supported; model aliases and private-LAN endpoints are editable, and launcher settings are not modified.
- Independent review found no translated producer/consumer label mismatch. Closed five concrete integration issues: malformed tool-call envelopes, invalid/duplicate explicit call IDs, provider-aware partial configuration defaults, runtime effort UI/server mismatch, and restoration of legacy local effort values.
- Partial Ollama configuration uses its native endpoint and requires an explicit model rather than inheriting a Bonsai alias. Explicit connection overrides remain intact; unsupported adapters are not silently remapped.
- Shared reasoning policy accepts blank/`auto`. Only restoration of recognized effort levels for the two supported local adapters migrates to `auto`, with a diagnostic and unchanged endpoint validation. Runtime Settings reports commands as queued, not prematurely applied.
- `LLMRequest.DEFAULT_DEADLINE` supplies the shared 180-second request/provider/agent-round default. Explicit deadlines and cancellation remain supported. Valid server tool IDs are preserved exactly; omitted/null IDs receive unique internal IDs; malformed announced calls and ambiguous explicit IDs enter the retryable malformed-response path. Text/reasoning-only replies remain valid.
- Focused fixes were exercised RED/GREEN. Standalone review checks passed 11 configuration tests and 8 reasoning-policy/wiring tests before final integration. These tests are included in the complete JUnit run below, not added to its total.

## Final verification

- Verified portable Temurin JDK 21 archive checksum `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`. The ignored JDK directory is `.tools/jdk-21.0.12.1+1`; Gradle wrapper version is 8.10.2.
- After all delegated source edits, `:neoforge:clean :neoforge:test :neoforge:build` exited 0. A final test/build rerun after the remaining locale-text correction also exited 0.
- Parsed actual JUnit XML under `neoforge/build/test-results/test/`: **30 suites, 139 tests, zero failures, errors, or skipped tests**. This supersedes all earlier partial snapshots.
- All three `python scripts/check_project.py` checks and `git diff --check` passed. Independent protocol fixtures exercise actual HTTP requests against temporary loopback test servers, not a real loaded model.
- Inspected `neoforge/build/libs/mineagentx-neoforge-0.4.0.jar`: CRC passes; metadata identifies MineAgentX 0.4.0; required API/engine/tool/client classes and English resources are present; original license bytes match exactly; all 493 classes use Java major version 65; unsupported provider/Fabric/test classes and targeted privacy strings are absent.
- JAR SHA-256: `4fbdb6cfadabff6aaffac237ad6b36ae3f4a8d81865918a53dbf9e5a8824c738`.
- Reviewed implementation, English runtime, regression tests, capability documentation, and agent-guide changes are committed locally in `e6f0938` (`feat: finish local-only inference and English runtime`). This ledger is maintained in a separate documentation commit. All assistant commits use the explicit identity `Hermes Agent <hermes@localhost>`.
- No Minecraft installation/gameplay session or live Ollama/Bonsai inference was performed. The future construction design/preview/revision environment and additional configurable localization layers were not implemented as part of this preparation. Push remains user-owned; no authentication or push is required from the assistant.
