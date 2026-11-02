package mage.lab.preview;
import mage.client.lab.LabHomePane;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
public final class HomeChecks {
    public static JButton button(Container root,String text) {
        for (Component c:root.getComponents()) {
            if (c instanceof JButton && ((JButton)c).getText().equals(text)) return (JButton)c;
            if (c instanceof Container) { JButton found=button((Container)c,text); if(found!=null)return found; }
        } return null;
    }
    private static void layout(Container c) { c.doLayout(); for(Component child:c.getComponents())if(child instanceof Container)layout((Container)child); }
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                int[] counts={0,0,0,0,0};
                LabHomePane home=new LabHomePane(()->counts[0]++,()->counts[1]++,()->counts[2]++,()->counts[3]++,()->counts[4]++);
                button(home,"Nuova partita").doClick();if(counts[0]!=0)throw new AssertionError("Disconnected create allowed");
                home.setConnected(true);
                for(String label:new String[]{"Nuova partita","Apri editor mazzi","Apri Laboratorio","Tavoli e partite","Connessione…"})button(home,label).doClick();
                for(int count:counts)if(count!=1)throw new AssertionError("Wrong action callback");
                for(int width:new int[]{1024,1280}) {
                    home.setSize(width,720);layout(home);
                    BufferedImage image=new BufferedImage(width,720,BufferedImage.TYPE_INT_RGB);
                    Graphics2D g=image.createGraphics();home.printAll(g);g.dispose();
                    ImageIO.write(image,"png",Paths.get(args[0],"home-"+width+".png").toFile());
                    for(String label:new String[]{"Nuova partita","Apri editor mazzi","Apri Laboratorio"}) {
                        JButton b=button(home,label);if(b.getWidth()<190||b.getHeight()<30)throw new AssertionError("Primary action clipped");
                    }
                }
                System.out.println("PASS home action wiring, connection state, rendering at 1024 and 1280");
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }
}
