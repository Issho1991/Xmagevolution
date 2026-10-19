package mage.lab.launcher;

import java.io.*;
import java.net.ServerSocket;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Real helper JVM, file locks and restart handshake; the simulated app never plays. */
public final class LauncherRestartChecks {
    static byte[] app(Path work,boolean fail) throws Exception {
        Path source=work.resolve("LaboratorioLauncher.java");
        String code="package mage.lab.launcher; public class LaboratorioLauncher { public static void main(String[] a) throws Exception { ";
        code+=fail?"System.exit(2);":"java.nio.file.Path root=java.nio.file.Paths.get(a[1]); if(a.length==4) java.nio.file.Files.write(java.nio.file.Paths.get(a[3]).resolve(\"riavviato\"),new byte[]{1}); else java.nio.file.Files.write(root.resolve(\"precedente-riavviato\"),new byte[]{1}); Thread.sleep(1500);";
        Files.write(source,(code+"} }").getBytes(StandardCharsets.UTF_8));
        int status=ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-d",work.toString(),source.toString());
        if(status!=0)throw new AssertionError("Fixture compile failed");
        return Files.readAllBytes(work.resolve("mage/lab/launcher/LaboratorioLauncher.class"));
    }
    static void fixtureJar(Path real,Path target,byte[] app,KeyPair key,String version) throws Exception {
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class",LaboratorioLauncher.class.getName());manifest.getMainAttributes().putValue("Implementation-Version",version);
        try(JarFile original=new JarFile(real.toFile());JarOutputStream out=new JarOutputStream(Files.newOutputStream(target),manifest)) {
            Enumeration<JarEntry> entries=original.entries();
            while(entries.hasMoreElements()) {
                JarEntry e=entries.nextElement();String name=e.getName();
                if(name.equals("META-INF/MANIFEST.MF")||name.startsWith("lab-catalog-"))continue;
                out.putNextEntry(new JarEntry(name));
                if(name.equals("lab-updates-public.der"))out.write(key.getPublic().getEncoded());
                else if(name.equals("mage/lab/launcher/LaboratorioLauncher.class"))out.write(app);
                else try(InputStream in=original.getInputStream(e)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                out.closeEntry();
            }
        }
    }
    static void scenario(Path real,boolean failure) throws Exception {
        Path home=Files.createTempDirectory("launcher-restart-");
        try {
            Path launcher=Files.createDirectories(home.resolve("launcher")),folder=Files.createDirectories(launcher.resolve("aggiornamento-launcher/update-test"));
            Files.createDirectories(home.resolve("mage-server/config"));int port;
            try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
            Files.write(home.resolve("mage-server/config/config.xml"),("<config><server serverAddress=\"127.0.0.1\" port=\""+port+"\"/></config>").getBytes(StandardCharsets.UTF_8));
            Files.write(launcher.resolve("distribuzione.properties"),"schema=1\nlab.version=10.0\nai.version=6\nxmage.version=1.4.62-V1\n".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(home.resolve("laboratorio"));Path report=home.resolve("laboratorio/report.json");Files.write(report,new byte[]{10,20,30});
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair key=generator.generateKeyPair();
            Path target=launcher.resolve("laboratorio-launcher.jar");
            fixtureJar(real,target,app(home,false),key,"0.4.0");String old=Installation.sha256(target);
            fixtureJar(real,folder.resolve("nuovo.jar"),app(home,failure),key,"0.4.1");String next=Installation.sha256(folder.resolve("nuovo.jar"));
            Files.copy(target,folder.resolve("precedente.jar"));
            byte[] signed=LauncherUpdateChecks.catalog(key,"0.4.1",next,Files.size(folder.resolve("nuovo.jar")),"xmage-laboratorio-launcher");
            Files.write(folder.resolve("catalogo.properties"),signed);Files.write(launcher.resolve("catalogo-launcher.properties"),signed);
            Process helper;
            try(FileChannel channel=FileChannel.open(launcher.resolve("session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock held=channel.lock();JarFile heldJar=new JarFile(target.toFile())) {
                helper=new ProcessBuilder(Installation.currentJava().toString(),"-Djava.awt.headless=true","-cp",folder.resolve("precedente.jar").toString(),LauncherUpdater.class.getName(),home.toString(),folder.toString())
                    .redirectErrorStream(true).redirectOutput(folder.resolve("test.log").toFile()).start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(!Files.exists(folder.resolve("pronto"))&&helper.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
                if(!Files.exists(folder.resolve("pronto")))throw new AssertionError(new String(Files.readAllBytes(folder.resolve("test.log")),StandardCharsets.UTF_8));
                if(!old.equals(Installation.sha256(target)))throw new AssertionError("Replaced while parent held session lock");
            }
            if(!helper.waitFor(30,TimeUnit.SECONDS)){helper.destroyForcibly();throw new AssertionError("Updater timed out");}
            if(helper.exitValue()!=(failure?1:0))throw new AssertionError(new String(Files.readAllBytes(folder.resolve("test.log")),StandardCharsets.UTF_8));
            if(!Installation.sha256(target).equals(failure?old:next))throw new AssertionError("Replacement/rollback mismatch");
            if(!Installation.sha256(folder.resolve("backup-launcher.jar")).equals(old))throw new AssertionError("Lost previous launcher");
            if(!Arrays.equals(Files.readAllBytes(report),new byte[]{10,20,30}))throw new AssertionError("Touched game report");
            if(failure) {
                for(int i=0;i<100&&!Files.exists(home.resolve("precedente-riavviato"));i++)Thread.sleep(50);
                if(!Files.exists(home.resolve("precedente-riavviato")))throw new AssertionError("Previous launcher not restarted");
            }
            // Simulated application exits itself; allow its JAR handles to close on Windows.
            Thread.sleep(2000);
        } finally { ArchiveFiles.deleteTree(home); }
    }
    public static void main(String[] args) throws Exception {
        scenario(Paths.get(args[0]),false);scenario(Paths.get(args[0]),true);
        System.out.println("PASS real JVM handoff, parent lock, verified replacement, restart acknowledgement, rollback and data preservation");
    }
}
