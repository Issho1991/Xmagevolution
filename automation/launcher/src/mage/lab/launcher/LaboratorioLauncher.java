package mage.lab.launcher;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import javax.swing.*;

public final class LaboratorioLauncher {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ProcessSupervisor processes = null;
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                Path root = args.length == 2 && "--root".equals(args[0]) ? Paths.get(args[1])
                    : Paths.get(LaboratorioLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent();
                VersionManager versions = new VersionManager(root);
                Installation installation = versions.active();
                processes = new ProcessSupervisor(installation); processes.lock();
                LauncherPanel panel = new LauncherPanel(installation, processes);
                JFrame frame = new JFrame("XMage Laboratorio — Launcher 0.3.2");
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.setContentPane(panel); frame.setSize(980, 720); frame.setMinimumSize(new Dimension(880, 660));
                frame.setLocationRelativeTo(null);
                frame.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { panel.requestClose(); } });
                frame.setVisible(true); panel.monitor();
            } catch (Exception e) {
                if (processes != null) try { processes.close(); } catch (Exception ignored) { }
                JOptionPane.showMessageDialog(null, "Impossibile aprire il launcher.\n" + e.getMessage()
                    + "\n\nEstrai il pacchetto dentro la cartella XMage Laboratorio v9.1.", "XMage Laboratorio", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
