# Live agent status (Minecraft 1.21.4, Fabric)

The live-injection prototype is under development. It is intended to locate the game's Knot classloader, add the bundled client JAR to its class path, invoke `OrangeClient.onInitializeClient()` on the game thread, and report the result in `%TEMP%/orange-agent-result.txt`.

**Not verified against a real Minecraft 1.21.4 session.** A successful build or agent attachment does not prove the client GUI or modules work.

Known limitations:
- Fabric mixins/accessors are applied during initial class loading. The 22 existing mixins cannot simply be retroactively applied to classes already loaded in a running game. Modules that depend on them may fail or remain inactive.
- Fabric resource packs are registered at startup, so Orange Client fonts and textures may not load after injection.
- The target instance needs Fabric Loader and Fabric API.
