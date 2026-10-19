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
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                Path root = args.length >= 2 && "--root".equals(args[0]) ? Paths.get(args[1])
                    : Paths.get(LaboratorioLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent();
                VersionManager versions = new VersionManager(root);
                Installation installation = versions.active();
                processes = new ProcessSupervisor(installation); processes.lock();
                LauncherPanel panel = new LauncherPanel(installation, processes);
                JFrame frame = new JFrame("XMage Laboratorio — Launcher " + LauncherInfo.VERSION);
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.setContentPane(panel); frame.setSize(1080, 800); frame.setMinimumSize(new Dimension(940, 720));
                frame.setLocationRelativeTo(null);
                frame.addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { panel.requestClose(); } });
                frame.setVisible(true);
                if(args.length==4 && "--update-ack".equals(args[2]))LauncherUpdater.acknowledge(root,Paths.get(args[3]));
                panel.monitor();
            } catch (Exception e) {
                if (processes != null) try { processes.close(); } catch (Exception ignored) { }
                JOptionPane.showMessageDialog(null, "Impossibile aprire il launcher.\n" + e.getMessage()
                    + "\n\nIl launcher deve trovarsi nella cartella del Laboratorio, accanto a mage-client e mage-server.", "XMage Laboratorio", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
