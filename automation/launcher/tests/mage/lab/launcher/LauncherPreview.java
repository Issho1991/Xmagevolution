package mage.lab.launcher;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Synthetic fixture, no real matches, no game launch and no network. */
public final class LauncherPreview {
    static void layout(Component c){if(c instanceof Container){((Container)c).doLayout();for(Component x:((Container)c).getComponents())layout(x);}}
    static void click(Component c,String label){if(c instanceof JButton && label.equals(((JButton)c).getText()))((JButton)c).doClick();if(c instanceof Container)for(Component x:((Container)c).getComponents())click(x,label);}
    static void capture(LauncherPanel panel,Path file) throws Exception {
        panel.setSize(1040,750);layout(panel);layout(panel);
        BufferedImage image=new BufferedImage(1040,750,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);panel.printAll(g);g.dispose();ImageIO.write(image,"png",file.toFile());
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("launcher-preview-");
        try {
            Files.createDirectories(root.resolve("launcher"));
            Files.write(root.resolve("launcher/distribuzione.properties"),"schema=1\nlab.version=10.0\nxmage.version=1.4.62-V1\nai.version=6\n".getBytes(StandardCharsets.UTF_8));
            SwingUtilities.invokeAndWait(()->{
                try {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                    Installation installation=Installation.load(root);LauncherPanel panel=new LauncherPanel(installation,new ProcessSupervisor(installation));
                    capture(panel,Paths.get(args[0]).resolve("launcher-home.png"));
                    click(panel,"Aggiornamenti");capture(panel,Paths.get(args[0]).resolve("launcher-aggiornamenti.png"));
                }catch(Exception e){throw new RuntimeException(e);}
            });
        } finally { ArchiveFiles.deleteTree(root); }
    }
}
