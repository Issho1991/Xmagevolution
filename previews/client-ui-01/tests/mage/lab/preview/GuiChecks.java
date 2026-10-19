package mage.lab.preview;
import mage.client.*;
import mage.client.dialog.NewTableDialog;
import mage.game.match.MatchOptions;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

public final class GuiChecks {
    static Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception {Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}
    static void shot(Component c,Path out)throws Exception {
        BufferedImage image=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();c.printAll(g);g.dispose();ImageIO.write(image,"png",out.toFile());
    }
    public static void main(String[] args)throws Exception {
        Path runtime=Paths.get(args[0]),work=Paths.get(args[1]);boolean modern=Boolean.parseBoolean(args[2]);int result=1;
        Files.createDirectories(runtime.resolve("prova/home"));
        Process server=new ProcessBuilder(PreviewLauncher.command(runtime,runtime.resolve("prova/anteprima.jar"),false,modern))
                .directory(runtime.resolve("mage-server").toFile()).redirectErrorStream(true).redirectOutput(work.resolve("gui-server-"+modern+".log").toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);boolean ready=false;
            while(System.nanoTime()<deadline&&server.isAlive()) {
                try(Socket s=new Socket()){s.connect(new InetSocketAddress("127.0.0.1",PreviewLauncher.PORT),200);ready=true;break;}
                catch(Exception e){Thread.sleep(250);}
            }
            if(!ready)throw new AssertionError("Preview server did not start");
            PreviewLauncher.main(new String[]{"--client"});
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);
            while(System.nanoTime()<deadline) {
                if(MageFrame.getInstance()!=null&&SessionHandler.isConnected()&&SessionHandler.getSession().getMainRoomId()!=null)break;
                Thread.sleep(250);
            }
            if(!SessionHandler.isConnected())throw new AssertionError("Client did not connect");
            final NewTableDialog[] dialog={null};
            SwingUtilities.invokeAndWait(()->{
                try {
                    MageFrame frame=MageFrame.getInstance();frame.setSize(1100,820);
                    Object home=field(frame,"labHome");
                    if(modern != (home!=null))throw new AssertionError("Wrong mode at startup");
                    if(modern) {MageFrame.setActive((MagePane)home);shot(frame,work.resolve("client-home.png"));}
                    dialog[0]=new NewTableDialog();MageFrame.getDesktop().add(dialog[0],JLayeredPane.MODAL_LAYER);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            SwingUtilities.invokeLater(()->dialog[0].showDialog(SessionHandler.getSession().getMainRoomId()));
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(()->{
                try {
                    if(!dialog[0].isVisible())throw new AssertionError("New game dialog did not open");
                    if(modern) {
                        String deck=(String)((JComboBox<?>)field(dialog[0],"cbDeckType")).getSelectedItem();
                        for(int count:new int[]{2,3,4,2}) {
                            call(dialog[0],"applyPreviewProfile",new Class<?>[]{int.class},count);
                            MatchOptions options=(MatchOptions)call(dialog[0],"getMatchOptions",new Class<?>[0]);
                            if(options.getPlayerTypes().size()!=count||options.getWinsNeeded()!=2||!options.isCustomStartLifeEnabled()||options.getCustomStartLife()!=30||!options.getDeckType().equals(deck))throw new AssertionError("Profile changed the wrong rules");
                        }
                        call(dialog[0],"onSaveSettings",new Class<?>[]{int.class},1);
                        ((JSpinner)field(dialog[0],"spnNumWins")).setValue(1);
                        call(dialog[0],"onLoadSettings",new Class<?>[]{int.class},1);
                        MatchOptions saved=(MatchOptions)call(dialog[0],"getMatchOptions",new Class<?>[0]);
                        if(saved.getWinsNeeded()!=2||saved.getCustomStartLife()!=30)throw new AssertionError("Save/load changed profile");
                        shot(dialog[0],work.resolve("new-game.png"));
                    }else shot(dialog[0],work.resolve("classic-game.png"));
                    dialog[0].hideDialog();
                }catch(Exception e){throw new RuntimeException(e);}
            });
            System.out.println("PASS actual client startup, local connection, new-game dialog, mode="+modern);
            result=0;
        }catch(Throwable error){
            error.printStackTrace();
            CountDownLatch captured = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                try { if (MageFrame.getInstance() != null) shot(MageFrame.getInstance(),work.resolve("client-failure-"+modern+".png")); }
                catch (Exception snapshotError) { snapshotError.printStackTrace(); }
                finally { captured.countDown(); }
            });
            captured.await(5,TimeUnit.SECONDS);
        }
        finally {
            server.destroy();if(!server.waitFor(8,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(8,TimeUnit.SECONDS);}
            System.exit(result);
        }
    }
}
