package mage.lab.launcher;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;

/** Runs from the OLD launcher copy, after the UI exits, including on Windows. */
public final class LauncherUpdater {
    interface Restart { boolean start() throws Exception; }

    static Path validateStage(Path home,Path stage) throws IOException {
        Path root=home.toRealPath(), real=stage.toRealPath();
        Path parent=root.resolve("launcher/aggiornamento-launcher");
        if(!real.getParent().equals(parent) || !real.getFileName().toString().startsWith("update-")
                || Files.isSymbolicLink(stage) || !parent.toRealPath().equals(parent))throw new IOException("Cartella aggiornamento non valida.");
        return real;
    }
    static void acknowledge(Path home,Path stage) throws IOException {
        Files.write(validateStage(home,stage).resolve("riavviato"),new byte[]{1});
    }
    static Process launchHelper(Path home,Path stage) throws Exception {
        validateStage(home,stage);
        Process helper=new ProcessBuilder(Installation.currentJava().toString(),"-cp",stage.resolve("precedente.jar").toString(),
                LauncherUpdater.class.getName(),home.toString(),stage.toString()).redirectErrorStream(true)
                .redirectOutput(stage.resolve("aggiornamento.log").toFile()).start();
        for(int n=0;n<100;n++) {
            if(Files.isRegularFile(stage.resolve("pronto")) && helper.isAlive())return helper;
            if(!helper.isAlive())break;
            Thread.sleep(100);
        }
        helper.destroyForcibly();helper.waitFor(5,TimeUnit.SECONDS);
        throw new IOException("Impossibile preparare il riavvio. Il launcher attuale resta attivo.");
    }
    static void replaceAndRestart(Path target,Path candidate,Path backup,String expectedOld,String expectedNew,Restart restart) throws Exception {
        if(Files.isSymbolicLink(target)||Files.isSymbolicLink(candidate)||!expectedOld.equals(Installation.sha256(target))||!expectedNew.equals(Installation.sha256(candidate)))
            throw new IOException("I file del launcher sono cambiati: aggiornamento annullato.");
        Files.copy(target,backup);
        if(!expectedOld.equals(Installation.sha256(backup)))throw new IOException("Backup del launcher non valido.");
        Path next=Files.createTempFile(target.getParent(),".launcher-",".jar");boolean replaced=false;
        try {
            Files.copy(candidate,next,StandardCopyOption.REPLACE_EXISTING);
            if(!expectedNew.equals(Installation.sha256(next)))throw new IOException("Copia del launcher non valida.");
            IOException last=null;
            for(int n=0;n<100;n++) {
                if(!expectedOld.equals(Installation.sha256(target)))throw new IOException("Un altro aggiornamento ha cambiato il launcher.");
                try { Files.move(next,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); replaced=true;last=null;break; }
                catch(AtomicMoveNotSupportedException e){throw new IOException("Il disco non supporta la sostituzione atomica. Launcher invariato.",e);}
                catch(IOException e){last=e;Thread.sleep(200);}
            }
            if(last!=null)throw last;
            if(!expectedNew.equals(Installation.sha256(target)) || !restart.start())throw new IOException("Il nuovo launcher non ha confermato l’avvio.");
        } catch(Exception failure) {
            if(replaced) {
                Files.copy(backup,next,StandardCopyOption.REPLACE_EXISTING);
                if(!expectedOld.equals(Installation.sha256(next)))throw new IOException("Copia precedente da recuperare: "+backup,failure);
                Files.move(next,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            throw failure;
        } finally { Files.deleteIfExists(next); }
    }
    private static Process startLauncher(Path home,Path stage,boolean ack) throws IOException {
        java.util.List<String> command=new java.util.ArrayList<>();
        command.add(Installation.currentJava().toString());command.add("-jar");command.add(home.resolve("launcher/laboratorio-launcher.jar").toString());
        command.add("--root");command.add(home.toString());
        if(ack){command.add("--update-ack");command.add(stage.toString());}
        return new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).redirectOutput(stage.resolve(ack?"riavvio.log":"ripristino.log").toFile()).start();
    }
    public static void main(String[] args) {
        Path home=null,stage=null;boolean parentClosed=false;
        try {
            if(args.length!=2)throw new IOException("Parametri aggiornamento non validi.");
            home=Paths.get(args[0]).toRealPath();stage=validateStage(home,Paths.get(args[1]));
            Path target=home.resolve("launcher/laboratorio-launcher.jar");
            LauncherUpdates updates=new LauncherUpdates(home);
            LauncherUpdates.Release release=LauncherUpdates.verify(Files.readAllBytes(stage.resolve("catalogo.properties")),updates.key);
            if(!release.newer()||!java.util.Arrays.equals(release.encoded,updates.cached().encoded))throw new IOException("Aggiornamento obsoleto o non confermato.");
            release.verifyJar(stage.resolve("nuovo.jar"),updates.key);
            final Path root=home,folder=stage;
            try(FileChannel guard=FileChannel.open(home.resolve("launcher/aggiornamento.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock single=guard.tryLock()) {
                if(single==null)throw new IOException("Un aggiornamento del launcher è già in corso.");
                Files.write(stage.resolve("pronto"),new byte[]{1});
                try(FileChannel channel=FileChannel.open(home.resolve("launcher/session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                    FileLock acquired=null;
                    for(int n=0;n<450 && acquired==null;n++) { acquired=channel.tryLock();if(acquired==null)Thread.sleep(100); }
                    if(acquired==null)throw new IOException("Il launcher precedente è ancora aperto. Nessun file sostituito.");
                    final FileLock session=acquired;parentClosed=true;
                    try {
                        VersionManager versions=new VersionManager(home);
                        Installation.requireFreePort(versions.active().endpoint());
                        Installation.requireFreePort(Installation.load(home).endpoint());
                        replaceAndRestart(target,stage.resolve("nuovo.jar"),stage.resolve("backup-launcher.jar"),Installation.sha256(stage.resolve("precedente.jar")),release.hash,()->{
                            session.release();
                            Process next=startLauncher(root,folder,true);
                            for(int n=0;n<300;n++) {
                                if(Files.isRegularFile(folder.resolve("riavviato"))&&next.isAlive())return true;
                                if(!next.isAlive())break;
                                Thread.sleep(100);
                            }
                            if(next.isAlive()){next.destroyForcibly();next.waitFor(5,TimeUnit.SECONDS);}
                            return false;
                        });
                    } finally { if(session.isValid())session.release(); }
                }
            }
            Files.write(stage.resolve("completato"),release.version.getBytes(StandardCharsets.UTF_8));
        } catch(Exception e) {
            System.err.println("Aggiornamento non completato: "+e.getMessage());
            if(parentClosed && home!=null && stage!=null)try{startLauncher(home,stage,false);}catch(Exception ignored){}
            if(!java.awt.GraphicsEnvironment.isHeadless())JOptionPane.showMessageDialog(null,
                "Aggiornamento del launcher non completato.\n"+e.getMessage()+"\nLa copia precedente è conservata nella cartella di aggiornamento.","Aggiornamento launcher",JOptionPane.WARNING_MESSAGE);
            System.exit(1);
        }
    }
}
