# MineAgentX fork preparation

## Authorized scope

- Base: bingdongni/MineAgent, commit `701250a950154667212d3ae1be3b3197e9ce9470`.
- Local checkout renamed to `.`.
- Local branch is `master`, tracking `origin/master`; local `origin/HEAD` points to `origin/master`. After the user explicitly requested fixing non-fast-forward/divergent histories, merge `cb93195` joined the unrelated histories with the `ours` strategy: parents `c7a47e3` (new NeoForge codebase) and `b7c6719` (old remote tip). Verified committed tree unchanged and remote tip now an ancestor. Existing new commits and uncommitted work remain intact. A normal fast-forward push is now possible relative to the fetched remote tip; no push was performed.
- `origin` now points to `https://github.com/Xalalau/MineAgentX.git`; stale branch tracking removed. Latest user instruction: prepare incremental local commits only; the user will push. Do not push, restart authentication, or force-push. Reconciliation preserves the existing remote history as an ancestor without importing the old 1.20.1 project content.
- Keep Minecraft 1.21.1, support only NeoForge, remove Fabric while preserving relevant regression tests.
- Keep only local inference: native Ollama `/api/chat` or the documented Bonsai/MTP OpenAI-compatible endpoint (`http://127.0.0.1:8899/v1`, model `swift-bonsai2-27b-katana-mtp`). Do not inspect or modify the personal host/launcher.
- Translate all tracked project content to English; preserve code behavior and attribution. Historical Git objects are not rewritten.
- Add complete English `CAPABILITIES.md`, with an English rendering of the previous assistant checkpoint as its opening and local inference only in section 6.
- Exercise tests/build, inspect packaged JAR, and commit locally. Do not claim gameplay or real Bonsai inference without testing.

## Initial observations

- Initial worktree clean. No Java or GitHub CLI on PATH.
- Public destination page describes a different older project; do not pull/merge it or force-push.
- Existing regression tests are under `fabric/src/test/java`; migrate rather than discard them.
- Chinese text exists in documentation, locale data, comments, prompts, memory/personality labels and tests; translation must include executable strings and preserve consistent labels.

## Work in progress

- Documentation now includes the complete 40-tool catalog in `CAPABILITIES.md`, with the preceding checkpoint rendered in English and a local-only section 6. English README replaces the duplicated language-specific READMEs; contribution/security/issue documentation is aligned with the fork.
- `scripts/check_project.py` was exercised RED: missing capabilities and Chinese text were detected. Its tool-catalog coverage check now passes.
- Verified Temurin JDK 21 archive checksum `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`; local JAVA_HOME is `.tools/jdk-21.0.12.1+1` (ignored). Gradle 8.10.2 wrapper executed successfully with that JDK.
- Delegated independent ownership: local provider/connection code; NeoForge build/test migration; English memory/personality sources; English AgentLoop prompts/runtime tags. Parent owns documentation and final integration/build/commit.
- User clarified that local inference means **Ollama or Bonsai**, not Bonsai exclusively. Provider and review agents were steered to support both local protocols and remove only cloud integrations.
- First local commit: `e4e0f1a` (`docs: document MineAgentX capabilities and local-only NeoForge scope`). No configured author identity was available; this commit uses the assistant's explicit local identity `Hermes Agent <hermes@localhost>`, not an inferred user identity.
- Gradle `:neoforge:dependencies --configuration compileClasspath` exited 0. This resolves dependency metadata only; it is not a compile/test/build result. Its task-owned log is `build-dependencies.log` (ignored).
- Earlier HTTPS push dry-run was blocked by missing authentication. The device login process `proc_3fded90ce158` was stopped after the user took over pushing. No push occurred. Do not restart authentication.
- English/CJK guard and tool-documentation coverage both passed. Interim full Gradle tests/build (`proc_7115e3f87c1e`, log `build-verification.log`) exited 0: 23 JUnit suites, 98 tests, zero failures/errors/skips. This snapshot preceded completion of the local-provider changes; rerun before final acceptance.
- NeoForge-only build reviewed: removed Fabric sources/build/plugin repositories, migrated relevant JUnit regressions, tightened Minecraft version range, updated fork metadata and CI, and preserved attribution. Cloud-specific test suites are removed with their unsupported provider implementations; Ollama coverage is retained/replaced by dedicated local-provider tests.
- Integration review, final test rerun, JAR inspection, and remaining incremental local commits are pending. Push is user-owned and not part of the remaining work.
