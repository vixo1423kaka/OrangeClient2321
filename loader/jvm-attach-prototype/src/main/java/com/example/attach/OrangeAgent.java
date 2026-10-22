package com.example.attach;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;

/**
 * Attach/startup agent foundation for Minecraft 1.21.4.
 *
 * This stage intentionally performs discovery only: it does not define classes,
 * execute arbitrary client code, or transform Minecraft bytecode. Orange Client
 * still depends on Fabric entrypoints and Mixin startup transformations, so
 * claiming that discovery alone hot-loads the client would be incorrect.
 */
public final class OrangeAgent {
    private static final String[] MINECRAFT_CLIENT_NAMES = {
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.class_310"
    };

    private OrangeAgent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        report("premain", args, instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        report("agentmain", args, instrumentation);
    }

    private static void report(String phase, String args, Instrumentation instrumentation) {
        StringBuilder report = new StringBuilder()
                .append("time=").append(Instant.now()).append(System.lineSeparator())
                .append("phase=").append(phase).append(System.lineSeparator())
                .append("args=").append(String.valueOf(args)).append(System.lineSeparator())
                .append("java=").append(System.getProperty("java.version")).append(System.lineSeparator())
                .append("vm=").append(System.getProperty("java.vm.name")).append(System.lineSeparator())
                .append("loadedClassCount=").append(instrumentation.getAllLoadedClasses().length)
                .append(System.lineSeparator());

        Class<?> minecraftClient = findMinecraftClient(instrumentation);
        if (minecraftClient == null) {
            report.append("minecraftClientFound=false").append(System.lineSeparator())
                    .append("bootstrapStatus=NOT_READY: Minecraft client class not found; "
                            + "attach may have occurred before game initialization or runtime names differ.")
                    .append(System.lineSeparator());
        } else {
            ClassLoader loader = minecraftClient.getClassLoader();
            report.append("minecraftClientFound=true").append(System.lineSeparator())
                    .append("minecraftClientClass=").append(minecraftClient.getName()).append(System.lineSeparator())
                    .append("minecraftClientClassLoader=")
                    .append(loader == null ? "<bootstrap>" : loader.getClass().getName())
                    .append(System.lineSeparator())
                    .append("bootstrapStatus=DISCOVERED_ONLY: Orange Client is not initialized by this agent.")
                    .append(System.lineSeparator());
        }

        writeReport(report.toString());
    }

    private static Class<?> findMinecraftClient(Instrumentation instrumentation) {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (Arrays.asList(MINECRAFT_CLIENT_NAMES).contains(type.getName())) {
                return type;
            }
        }
        return null;
    }

    private static void writeReport(String report) {
        try {
            Path log = Path.of(System.getProperty("java.io.tmpdir"), "orange-jvm-agent.log");
            Files.writeString(log, report + System.lineSeparator() + "---" + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException ignored) {
            // Diagnostic failure must never terminate the Minecraft JVM.
        }
    }
}
