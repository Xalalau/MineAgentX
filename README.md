# MineAgentX

A local-LLM Minecraft companion for **Minecraft 1.21.1, NeoForge 21.1.x, and Java 21**.

MineAgentX is derived from [bingdongni/MineAgent](https://github.com/bingdongni/MineAgent), starting at commit `701250a950154667212d3ae1be3b3197e9ce9470`. This fork supports **NeoForge only**, **local inference only**, and English project text initially. The mod ID and Java package namespace remain `mineagent` for compatibility.

## Capabilities

See [CAPABILITIES.md](CAPABILITIES.md) for the complete tool inventory, survival behavior, planning, memory, teamwork, interface, commands, and explicit limitations. The companion is a server-side fake player: it navigates, gathers, mines, places blocks, fights, crafts, and manages items through validated actions.

This is alpha software. Back up valued worlds. Source code, tests, and a successful build do not prove gameplay reliability, universal mod compatibility, or autonomous game completion.

## Local inference: Ollama or Bonsai/MTP

Choose either **native Ollama** (`POST /api/chat`, commonly at `http://127.0.0.1:11434`) or **Bonsai/MTP** through the OpenAI-compatible protocol. Configure the actual Ollama model ID; no installed model is assumed.

The documented Bonsai deployment uses:

- API root: `http://127.0.0.1:8899/v1`.
- Model: `swift-bonsai2-27b-katana-mtp`.
- Protocol: OpenAI-compatible `POST /v1/chat/completions` with tool calling.
- External host launcher directory: `<local-launcher>`.

The mod connects to the HTTP service. It does not open that directory, start the model, or change the launcher, MTP, context allocation, or template settings. Loopback refers to the machine running the Minecraft server. If inference runs on another machine on your private network, configure its reachable local address instead.


## Build and test

Install JDK 21 and set `JAVA_HOME` as needed:

```sh
python scripts/check_project.py
./gradlew :neoforge:test :neoforge:build
```

On Windows, use `gradlew.bat`. Gradle downloads its build dependencies. The output is under `neoforge/build/libs/`. No Fabric loader or Fabric API is required or supported.

## Install and use

1. Install Minecraft 1.21.1, Java 21, and NeoForge 21.1.x.
2. Build the NeoForge JAR and install it in the relevant client/server `mods` directories.
3. Start your local model service using its existing documented procedure.
4. Press `M` to configure/create a companion, or use `/mineagent quick` with saved configuration.
5. Press `C` for companion chat. Other default keys: `H` status HUD, `P` paths, `V` perception overlay, `N` labels.

The three-companions-per-owner limit remains; the inherited code also requires distinct model identifiers for companions belonging to the same owner.

## Modules

| Module | Purpose |
| --- | --- |
| `api` | Shared tool, task, model, configuration, and networking contracts. |
| `engine` | Agent loop, fake player, movement, survival, memory, and shared client UI. |
| `tools` | AI-callable tools and instructional skill documents. |
| `neoforge` | Only loader integration, packaging, networking, client bootstrap, and regression tests. |

## Privacy and security

Chat and world context are sent to the configured local inference service. Runtime configuration and companion saves can contain plaintext credentials if supplied. Never publish these files, private logs, chat, or world saves. See [SECURITY.md](SECURITY.md).

## Development status

Read [agents.md](agents.md) for the source map, design decisions, verification rules, and parallel-agent workflow. Keep it current whenever the structure or supported behavior changes.

See [PROGRESS.md](PROGRESS.md) for the current preparation state and actual verification evidence. The construction design/preview/revision environment and additional configurable language layers are future work, not existing capabilities.

## License and attribution

Original MineAgent copyright (c) 2026 bingdongni. Distributed under **LGPL-3.0-only**; see [LICENSE](LICENSE). Preserve upstream attribution when modifying or distributing this fork.

This is an unofficial community project, not affiliated with or endorsed by Mojang Studios or Microsoft.
