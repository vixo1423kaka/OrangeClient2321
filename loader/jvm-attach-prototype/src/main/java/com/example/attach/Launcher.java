package com.example.attach;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Launcher stage 2: installs the bundled Fabric mod into a user-selected game
 * instance. It does not claim to hot-load Fabric mods into an already running JVM.
 */
public final class Launcher {
    private final JFrame frame = new JFrame("Orange Client");
    private final JPasswordField licenseField = new JPasswordField(24);
    private final JLabel status = new JLabel("Choose your Minecraft instance when ready.");
    private final JTextArea log = new JTextArea(8, 42);

    private Launcher() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(540, 400));

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        root.setBackground(new Color(24, 25, 30));

        JLabel heading = new JLabel("ORANGE CLIENT");
        heading.setForeground(new Color(255, 139, 46));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 23f));
        JLabel subtitle = new JLabel("Minecraft Java 1.21.4 • installer preview");
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
        c.gridx = 0; c.gridy = 0;
        fields.add(licenseLabel, c);
        c.gridx = 1; c.weightx = 1;
        fields.add(licenseField, c);

        JButton install = new JButton("INSTALL TO INSTANCE");
        install.setFont(install.getFont().deriveFont(Font.BOLD, 14f));
        install.addActionListener(e -> installPayload());
        c.gridx = 1; c.gridy = 1; c.weightx = 0;
        fields.add(install, c);
        root.add(fields, BorderLayout.CENTER);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setText("This build installs the bundled Fabric mod into a selected instance's mods folder.\n"
                + "License verification is not connected. Hot-loading into a running game is not implemented.\n");
        JScrollPane scroll = new JScrollPane(log);
        scroll.setPreferredSize(new Dimension(490, 145));
        JPanel bottom = new JPanel(new BorderLayout(4, 6));
        bottom.setOpaque(false);
        status.setForeground(new Color(255, 190, 120));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private void installPayload() {
        char[] license = licenseField.getPassword();
        try {
            if (license.length == 0) {
                JOptionPane.showMessageDialog(frame, "Enter your license key first.",
                        "License required", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // Do not log or persist the key: there is no configured verifier yet.
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select your Minecraft / Modrinth instance folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

            Path instance = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            Path mods = instance.resolve("mods");
            Files.createDirectories(mods);
            String resource = "/payload/orange-client-inner-0.1.0.jar";
            Path destination = mods.resolve("orange-client-inner-0.1.0.jar");
            try (InputStream in = Launcher.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("Bundled client payload is missing. Download the newest Orange-Client-Launcher artifact.");
                }
                Path temp = Files.createTempFile(mods, "orange-client-", ".tmp");
                try {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException atomicMoveUnsupported) {
                        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            String message = "Orange Client mod copied to:\n" + destination
                    + "\n\nClose Minecraft completely and launch this instance again. "
                    + "This installer does not hot-load Fabric mods into a running game.\n"
                    + "License was NOT verified because no license server is configured.";
            append(message);
            status.setText("Installed to instance; restart Minecraft to load the mod.");
            JOptionPane.showMessageDialog(frame, message, "Installation complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable ex) {
            showError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            Arrays.fill(license, '\\0');
            licenseField.setText("");
        }
    }

    private void append(String text) {
        log.append("\n" + text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void showError(String message) {
        status.setText("Installation failed.");
        append(message);
        JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void show() { frame.setVisible(true); }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Launcher().show());
    }
}
