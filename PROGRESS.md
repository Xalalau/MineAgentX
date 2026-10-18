# MineAgentX fork preparation

## Authorized scope

- Base: bingdongni/MineAgent, commit `701250a950154667212d3ae1be3b3197e9ce9470`.
- Checkout identity: MineAgentX.
- Local branch is `master`, tracking `origin/master`. The user-authorized `ours` merge preserved both histories without importing the older remote codebase. The newer NeoForge tree and unfinished work were retained. Later privacy cleanup rewrites the affected local commits; publishing that rewritten history is a separate user-owned action.
- `origin` now points to `https://github.com/Xalalau/MineAgentX.git`; stale branch tracking removed. Latest user instruction: prepare incremental local commits only; the user will push. Do not push, restart authentication, or force-push. Reconciliation preserves the existing remote history as an ancestor without importing the old 1.20.1 project content.
- Keep Minecraft 1.21.1, support only NeoForge, remove Fabric while preserving relevant regression tests.
- Keep only local inference: native Ollama `/api/chat` or the documented Bonsai/MTP OpenAI-compatible endpoint (`http://127.0.0.1:8899/v1`, model `swift-bonsai2-27b-katana-mtp`). Do not inspect or modify the personal host/launcher.
- Translate project content to English; preserve behavior and upstream attribution. The user explicitly authorized removing personal paths/names and private-repository references from files and history. Do not reintroduce them in documentation or commit messages.
- Add complete English `CAPABILITIES.md`, with an English rendering of the previous assistant checkpoint as its opening and local inference only in section 6.
- Exercise tests/build, inspect packaged JAR, and commit locally. Do not claim gameplay or real Bonsai inference without testing.

## Initial observations

- Initial worktree clean. No Java or GitHub CLI on PATH.
- The public destination initially described a different older project; its history was later reconciled without importing its source tree.
- Existing regression tests are under `fabric/src/test/java`; migrate rather than discard them.
- Chinese text exists in documentation, locale data, comments, prompts, memory/personality labels and tests; translation must include executable strings and preserve consistent labels.

## Privacy cleanup

- Removed personal machine paths/names and all references to private repositories from current project text. Removed the unpublished comparison report.
- Rewrote all affected local history while preserving merge topology and upstream attribution. Scanned reachable blobs, commit/tag metadata, and tree entries; no targeted privacy matches remained.
- Preserved all 352 working-file checksums/deletions across the rewrite; unrelated implementation work remains uncommitted and intact.
- Added a personal-path publication guard and exercised it RED/GREEN. All three repository checks pass.
- Some affected commits were already on the public remote. No remote write was performed. Publishing the sanitized history requires the user's explicit force-with-lease push; a normal pull/fetch must not be used to merge the contaminated history back in. GitHub caches and other clones are outside this local cleanup.

## Work in progress

- User requested a maintained project guide: `agents.md` now maps source files, design intent, supported local protocols, validation, Git ownership, and parallel-agent coordination. Read it on resume and include it in future delegation context; update it in the same change when the project structure or supported behavior changes.

- Documentation now includes the complete 40-tool catalog in `CAPABILITIES.md`, with the preceding checkpoint rendered in English and a local-only section 6. English README replaces the duplicated language-specific READMEs; contribution/security/issue documentation is aligned with the fork.
- `scripts/check_project.py` was exercised RED: missing capabilities and Chinese text were detected. Its tool-catalog coverage check now passes.
- Verified Temurin JDK 21 archive checksum `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`; local JAVA_HOME is `.tools/jdk-21.0.12.1+1` (ignored). Gradle 8.10.2 wrapper executed successfully with that JDK.
- Delegated independent ownership: local provider/connection code; NeoForge build/test migration; English memory/personality sources; English AgentLoop prompts/runtime tags. Parent owns documentation and final integration/build/commit.
- User clarified that local inference means **Ollama or Bonsai**, not Bonsai exclusively. Provider and review agents were steered to support both local protocols and remove only cloud integrations.
- The initial capability/scope documentation was committed locally using the assistant's explicit identity `Hermes Agent <hermes@localhost>`, not an inferred user identity.
- Gradle `:neoforge:dependencies --configuration compileClasspath` exited 0. This resolves dependency metadata only; it is not a compile/test/build result. Its task-owned log is `build-dependencies.log` (ignored).
- Earlier HTTPS push dry-run was blocked by missing authentication. The device login process `proc_3fded90ce158` was stopped after the user took over pushing. No push occurred. Do not restart authentication.
- English/CJK guard and tool-documentation coverage both passed. Interim full Gradle tests/build (`proc_7115e3f87c1e`, log `build-verification.log`) exited 0: 23 JUnit suites, 98 tests, zero failures/errors/skips. This snapshot preceded completion of the local-provider changes; rerun before final acceptance.
- NeoForge-only build reviewed: removed Fabric sources/build/plugin repositories, migrated relevant JUnit regressions, tightened Minecraft version range, updated fork metadata and CI, and preserved attribution. Cloud-specific test suites are removed with their unsupported provider implementations; Ollama coverage is retained/replaced by dedicated local-provider tests.
- Integration review, final test rerun, JAR inspection, and remaining incremental local commits are pending. Push is user-owned and not part of the remaining work.
