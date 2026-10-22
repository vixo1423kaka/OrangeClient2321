# JVM attach prototype (Windows / Java 21)

This is an isolated proof of concept on branch `experiment/jvm-attach-prototype`.
It does not modify the existing launcher, Fabric mod, or `main`.

## Purpose

Verify whether a user-selected Java 21 Minecraft process accepts a standard Java Attach API agent. The agent only writes a diagnostic line to the temp directory; it does not load game modules, alter Minecraft, evade anti-cheat, or hide from the user.

## Build

From this directory, with JDK 21 installed:

```powershell
gradle clean jar
```

## Test

1. Start a local Minecraft 1.21.4 profile normally.
2. Find the Java process PID using Task Manager / Details.
3. From a JDK 21 terminal, run:

```powershell
java --add-modules jdk.attach -cp build/libs/jvm-attach-prototype.jar com.example.attach.AttachClient <PID> build/libs/jvm-attach-prototype.jar
```

4. Check `%TEMP%\orange-jvm-agent.log` for the agent diagnostic line.

The target JVM must permit dynamic agent loading; this can be disabled by JVM configuration or restricted by platform/security policy. A successful attach only proves the agent can execute in the JVM. It does **not** make the current Fabric client work as an injected client: that code depends on Fabric lifecycle events, Fabric API, and likely mixins, which need a separate design.
