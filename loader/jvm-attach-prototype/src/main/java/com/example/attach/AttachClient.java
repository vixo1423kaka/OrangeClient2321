package com.example.attach;

import com.sun.tools.attach.VirtualMachine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit command-line helper: attaches only to the PID supplied by the user. */
public final class AttachClient {
    private AttachClient() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: AttachClient <pid> <agent-jar>");
            System.exit(2);
        }

        String pid = args[0];
        Path agent = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(agent)) {
            throw new IllegalArgumentException("Agent JAR not found: " + agent);
        }
        if (!pid.matches("[0-9]+")) {
            throw new IllegalArgumentException("PID must be numeric.");
        }

        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(agent.toString(), "orange-client-discovery");
            System.out.println("Discovery agent attached. Check the temp file orange-jvm-agent.log; this does not initialize Orange Client yet.");
        } finally {
            vm.detach();
        }
    }
}
