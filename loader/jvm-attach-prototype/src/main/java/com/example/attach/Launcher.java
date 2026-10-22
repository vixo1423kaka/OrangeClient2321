package com.example.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Early Orange Client launcher UI. The current agent is diagnostic only;
 * this UI intentionally does not claim to inject/load the Fabric client.
 */
public final class Launcher {
    private final JFrame frame = new JFrame("Orange Client");
    private final JPasswordField licenseField = new JPasswordField(24);
    private final JComboBox<ProcessChoice> processBox = new JComboBox<>();
    private final JLabel status = new JLabel("Ready.");
    private final JTextArea log = new JTextArea(7, 42);

    private Launcher() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(520, 390));

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        root.setBackground(new Color(24, 25, 30));

        JLabel heading = new JLabel("ORANGE CLIENT");
        heading.setForeground(new Color(255, 139, 46));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 23f));

        JLabel subtitle = new JLabel("Minecraft Java 1.21.4 • experimental loader");
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
        c.gridx = 0; c.gridy = 0;
        JLabel licenseLabel = new JLabel("License key");
        licenseLabel.setForeground(Color.WHITE);
        fields.add(licenseLabel, c);
        c.gridx = 1; c.weightx = 1;
        fields.add(licenseField, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        JLabel processLabel = new JLabel("Minecraft process");
        processLabel.setForeground(Color.WHITE);
        fields.add(processLabel, c);
        c.gridx = 1; c.weightx = 1;
        processBox.setPrototypeDisplayValue(new ProcessChoice("12345", "Minecraft Java 1.21.4"));
        fields.add(processBox, c);

        JButton refresh = new JButton("Refresh processes");
        refresh.addActionListener(e -> refreshProcesses());
        c.gridx = 1; c.gridy = 2; c.weightx = 0;
        fields.add(refresh, c);

        JButton load = new JButton("LOAD");
        load.setFont(load.getFont().deriveFont(Font.BOLD, 15f));
        load.addActionListener(e -> loadDiagnosticAgent());
        c.gridx = 1; c.gridy = 3;
        fields.add(load, c);
        root.add(fields, BorderLayout.CENTER);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setText("This is an experimental UI. License verification is not connected.\n"
                + "LOAD currently tests JVM Attach only; it does NOT load Orange Client into Minecraft.\n");
        JScrollPane scroll = new JScrollPane(log);
        scroll.setPreferredSize(new Dimension(470, 125));

        JPanel bottom = new JPanel(new BorderLayout(4, 6));
        bottom.setOpaque(false);
        status.setForeground(new Color(255, 190, 120));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        refreshProcesses();
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private void refreshProcesses() {
        processBox.removeAllItems();
        List<ProcessChoice> found = new ArrayList<>();
        try {
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                String name = d.displayName() == null ? "" : d.displayName();
                String lower = name.toLowerCase();
                if (lower.contains("minecraft") || lower.contains("net.minecraft")
                        || lower.contains("modrinth") || lower.contains("fabric")) {
                    found.add(new ProcessChoice(d.id(), name.isBlank() ? "(Java process)" : name));
                }
            }
        } catch (Throwable ex) {
            append("Could not enumerate JVMs: " + ex.getMessage());
        }
        for (ProcessChoice choice : found) processBox.addItem(choice);
        if (found.isEmpty()) {
            status.setText("No matching JVM found. Start Minecraft first, then refresh.");
        } else {
            status.setText("Found " + found.size() + " possible Java process(es).");
        }
    }

    private void loadDiagnosticAgent() {
        char[] license = licenseField.getPassword();
        try {
            if (license.length == 0) {
                JOptionPane.showMessageDialog(frame, "Enter your license key first.",
                        "License required", JOptionPane.WARNING_MESSAGE);
                return;
            }
            ProcessChoice selected = (ProcessChoice) processBox.getSelectedItem();
            if (selected == null) {
                JOptionPane.showMessageDialog(frame, "No Minecraft process selected. Start the game and refresh.",
                        "Process not found", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Path jar = Path.of(Launcher.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            if (!jar.toString().toLowerCase().endsWith(".jar")) {
                throw new IOException("Launcher must be run from the packaged JAR.");
            }

            VirtualMachine vm = VirtualMachine.attach(selected.pid);
            try {
                vm.loadAgent(jar.toString(), "orange-launcher-diagnostic");
            } finally {
                vm.detach();
            }

            String message = "Diagnostic agent attached to PID " + selected.pid
                    + ". Check %TEMP%\\orange-jvm-agent.log.\n"
                    + "The license was NOT verified (no license server is configured).\n"
                    + "Orange Client itself was NOT loaded; Fabric lifecycle/mixins are not implemented for live attach.";
            append(message);
            status.setText("Diagnostic attach succeeded; client loading is not implemented.");
            JOptionPane.showMessageDialog(frame, message, "Diagnostic result", JOptionPane.INFORMATION_MESSAGE);
        } catch (AttachNotSupportedException ex) {
            showError("The selected process does not support JVM Attach: " + ex.getMessage());
        } catch (Throwable ex) {
            showError("Attach failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            java.util.Arrays.fill(license, '\0');
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

    private void show() {
        frame.setVisible(true);
    }

    private record ProcessChoice(String pid, String name) {
        @Override public String toString() { return pid + " — " + name; }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Launcher().show());
    }
}
