package com.example.attach;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Minimal, visible diagnostic agent. Does not inspect or modify Minecraft classes.
 */
public final class DiagnosticAgent {
    private DiagnosticAgent() {}

    public static void agentmain(String args, Instrumentation instrumentation) {
        String line = Instant.now() + " agentmain invoked; args=" + String.valueOf(args)
                + "; loadedClasses=" + instrumentation.getAllLoadedClasses().length
                + System.lineSeparator();
        try {
            Path log = Path.of(System.getProperty("java.io.tmpdir"), "orange-jvm-agent.log");
            Files.writeString(log, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // The agent must not crash the target JVM if diagnostics cannot be written.
        }
    }
}
