# Live-agent migration status (Minecraft Java 1.21.4)

## Implemented in this stage
- An explicit Java instrumentation entrypoint supports both `premain` and `agentmain`.
- The agent records Java/runtime details and attempts to identify the already-loaded Minecraft client class and its defining classloader.
- The report is appended to `%TEMP%/orange-jvm-agent.log` on Windows (the JVM temp directory on other systems).
- Discovery-only behavior is deliberate; a diagnostic agent must not claim that it has initialized Orange Client.

## Not implemented / not yet verified
1. **Orange Client bootstrap:** the current `OrangeClient` implements `ClientModInitializer` and imports Fabric API types. Reflectively invoking it without Fabric's runtime is not a supported bootstrap path.
2. **Tick/render/HUD hooks:** the existing implementation registers Fabric API callbacks. These need a separate hook adapter with a defined lifecycle and thread-safety rules.
3. **Mixin replacements:** the client references mixins and accessors for game internals. Each must be mapped to the exact 1.21.4 runtime names and replaced with verified instrumentation transformations or redesigned to avoid private internals. This agent currently installs no transformers.
4. **Runtime test:** a successful Java compilation or agent attachment is not proof that the game GUI, HUD, or modules work. The test must run against a real 1.21.4 client and record initialization, GUI, tick, render, and module outcomes.

## Safe next milestones
- Inventory every Fabric API call and every mixin/accessor, and classify each by replacement strategy.
- Implement a dedicated, version-pinned bootstrap that does not call Fabric entrypoints.
- Add narrowly scoped bytecode transformations with class-name/version guards and fail-closed diagnostics.
- Run a reproducible client test on Java 21 and keep the logs/artifact with the CI result.

Do not distribute this stage as a functioning hot-loader. It is the instrumentation/discovery foundation only.
