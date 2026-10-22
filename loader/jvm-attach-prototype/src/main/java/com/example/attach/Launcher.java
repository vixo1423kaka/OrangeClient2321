package com.example.attach;

import com.sun.tools.attach.VirtualMachine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Attach UI: detects Java processes and attaches the bundled agent to a selected
 * Minecraft process. It does not ask the user to locate a game folder.
 */
public final class Launcher {
    private final JFrame frame = new JFrame("Orange Client");
    private final JPasswordField licenseField = new JPasswordField(24);
    private final JComboBox<GameProcess> processes = new JComboBox<>();
    private final JLabel status = new JLabel("Detect running Minecraft Java instances.");
    private final JTextArea log = new JTextArea(8, 42);
    private final JButton loadButton = new JButton("LOAD INTO MINECRAFT");

    private Launcher() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(560, 420));

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        root.setBackground(new Color(24, 25, 30));

        JLabel heading = new JLabel("ORANGE CLIENT");
        heading.setForeground(new Color(255, 139, 46));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 23f));
        JLabel subtitle = new JLabel("Minecraft Java 1.21.4 • live attach prototype");
        subtitle.setForeground(new Color(205, 207, 215));
        JPanel top = new JPanel(new GridLayout(0, 1, 4, 4));
        top.setOpaque(false);
        top.add(heading);
        top.add(subtitle);
        root.add(top, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 0, 6, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel licenseLabel = new JLabel("License key");
        licenseLabel.setForeground(Color.WHITE);
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        fields.add(licenseLabel, c);
        c.gridx = 1; c.weightx = 1;
        fields.add(licenseField, c);

        JLabel processLabel = new JLabel("Minecraft process");
        processLabel.setForeground(Color.WHITE);
        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        fields.add(processLabel, c);
        c.gridx = 1; c.weightx = 1;
        fields.add(processes, c);

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshProcesses());
        c.gridx = 0; c.gridy = 2; c.weightx = 0;
        fields.add(refresh, c);
        loadButton.setFont(loadButton.getFont().deriveFont(Font.BOLD, 14f));
        loadButton.addActionListener(e -> attachAgent());
        c.gridx = 1; c.weightx = 1;
        fields.add(loadButton, c);
        root.add(fields, BorderLayout.CENTER);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setText("No folder selection or manual file copying. Start Minecraft first, then select its Java process.\n"
                + "Current agent performs diagnostics only; it does not yet initialize Orange Client, GUI, or modules.\n"
                + "License verification is not connected to a license server.\n");
        JScrollPane scroll = new JScrollPane(log);
        scroll.setPreferredSize(new Dimension(500, 145));
        JPanel bottom = new JPanel(new BorderLayout(4, 6));
        bottom.setOpaque(false);
        status.setForeground(new Color(255, 190, 120));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        refreshProcesses();
    }

    private void refreshProcesses() {
        processes.removeAllItems();
        List<GameProcess> found = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(p -> {
            ProcessHandle.Info info = p.info();
            String command = info.command().orElse("");
            String args = String.join(" ", info.arguments().orElse(new String[0]));
            String combined = (command + " " + args).toLowerCase(Locale.ROOT);
            if (!combined.contains("java")) return;
            if (combined.contains("minecraft") || combined.contains("net.fabricmc")
                    || combined.contains("modrinth") || combined.contains("knotclient")
                    || combined.contains("gameprovider")) {
                found.add(new GameProcess(p.pid(), command.isBlank() ? "Java" : Path.of(command).getFileName().toString(),
                        args.length() > 100 ? args.substring(0, 100) + "…" : args));
            }
        });
        found.sort(Comparator.comparingLong(GameProcess::pid));
        for (GameProcess process : found) processes.addItem(process);
        status.setText(found.isEmpty()
                ? "No Minecraft Java process detected. Launch the instance, then Refresh."
                : "Found " + found.size() + " possible game process(es).");
        append("Process scan: " + found.size() + " candidate(s).");
    }

    private void attachAgent() {
        char[] license = licenseField.getPassword();
        try {
            if (license.length == 0) {
                JOptionPane.showMessageDialog(frame, "Enter your license key first.",
                        "License required", JOptionPane.WARNING_MESSAGE);
                return;
            }
            GameProcess selected = (GameProcess) processes.getSelectedItem();
            if (selected == null) {
                JOptionPane.showMessageDialog(frame, "No Minecraft process selected. Start the game and press Refresh.",
                        "Minecraft not found", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Path agentJar = Path.of(Launcher.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            if (!agentJar.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalStateException("Run the packaged launcher JAR, not from an IDE/classes directory.");
            }

            status.setText("Attaching to PID " + selected.pid() + "…");
            VirtualMachine vm = VirtualMachine.attach(Long.toString(selected.pid()));
            try {
                vm.loadAgent(agentJar.toString(), "orange-client-discovery");
            } finally {
                vm.detach();
            }
            String message = "Agent attached to PID " + selected.pid() + ".\n"
                    + "Diagnostic log: " + System.getProperty("java.io.tmpdir") + "/orange-jvm-agent.log\n\n"
                    + "Important: this build verifies agent attachment and Minecraft class discovery only. "
                    + "It does not yet load the Orange Client GUI or modules. The license was not verified.";
            append(message);
            status.setText("Agent attached; check the diagnostic log.");
            JOptionPane.showMessageDialog(frame, message, "Attach result", JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable ex) {
            String message = ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + "\n\nCheck that Minecraft and this launcher use compatible Java permissions, "
                    + "and run both under the same Windows user.");
            showError(message);
        } finally {
            java.util.Arrays.fill(license, '\\0');
            licenseField.setText("");
        }
    }

    private void append(String text) {
        log.append("\n" + text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void showError(String message) {
        status.setText("Attach failed.");
        append(message);
        JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void show() { frame.setVisible(true); }

    private record GameProcess(long pid, String executable, String arguments) {
        @Override public String toString() {
            return pid + " — " + executable + (arguments.isBlank() ? "" : " — " + arguments);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Launcher().show());
    }
}
