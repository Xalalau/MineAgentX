# MineAgentX fork preparation

## Authorized scope

- Base: bingdongni/MineAgent, commit `701250a950154667212d3ae1be3b3197e9ce9470`.
- Local checkout renamed to `.`.
- `origin` now points to `https://github.com/Xalalau/MineAgentX.git`; stale branch tracking removed. User subsequently authorized incremental commits and requested pushing to their repository. Preserve the existing, different `master` history; publish the current `main` without force-push if authentication permits.
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
- Final full tests, English guard, build/JAR inspection, review, and commit remain pending.
