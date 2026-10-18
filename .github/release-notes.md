# MineAgentX fork preparation

Minecraft 1.21.1, Java 21, NeoForge 21.1.x only. Local inference only, with the documented Bonsai/MTP deployment as the default target. English is the initial project language.

This fork retains the upstream agent tool catalog, survival, planning, memory, and teamwork while removing other loader and model-provider implementations. See CAPABILITIES.md for the complete inventory and PROGRESS.md for actual verification limits.

Install only the NeoForge JAR produced under `neoforge/build/libs/`. Back up worlds. Neither a passing build nor protocol tests prove real Bonsai decision quality or live-game behavior. Runtime configuration and companion saves can contain plaintext secrets if configured; do not publish them.
