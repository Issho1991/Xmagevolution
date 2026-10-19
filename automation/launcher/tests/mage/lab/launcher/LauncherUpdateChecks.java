package mage.lab.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

public final class LauncherUpdateChecks {
    private static int checks;
    interface Check { void run() throws Exception; }
    static void expect(boolean ok) { checks++;if(!ok)throw new AssertionError("Check "+checks); }
    static void reject(Check c) throws Exception { try{c.run();}catch(IOException e){checks++;return;}throw new AssertionError("Accepted invalid input"); }
    static byte[] catalog(KeyPair key,String version,String hash,long size,String product) throws Exception {
        String payload="product="+product+"\nversion="+version+"\nserial="+LauncherUpdates.number(version)+"\njar.size="+size+"\njar.sha256="+hash+"\nsource.commit="+String.join("",Collections.nCopies(40,"a"))+"\nurl=https://github.com/Issho1991/Xmagevolution/releases/download/launcher-v"+version+"/laboratorio-launcher.jar\n";
        Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key.getPrivate());s.update(payload.getBytes(StandardCharsets.UTF_8));
        return ("catalog.schema=1\npayload="+Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"\nsignature="+Base64.getEncoder().encodeToString(s.sign())+"\n").getBytes(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("launcher-checks-");
        try {
            KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair key=gen.generateKeyPair();
            Path jar=root.resolve("new.jar");Manifest m=new Manifest();m.getMainAttributes().putValue("Manifest-Version","1.0");m.getMainAttributes().putValue("Main-Class",LaboratorioLauncher.class.getName());m.getMainAttributes().putValue("Implementation-Version","0.4.1");
            try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar),m)){out.putNextEntry(new JarEntry("lab-updates-public.der"));out.write(key.getPublic().getEncoded());out.closeEntry();}
            String hash=Installation.sha256(jar);byte[] valid=catalog(key,"0.4.1",hash,Files.size(jar),"xmage-laboratorio-launcher");
            LauncherUpdates updates=new LauncherUpdates(root,key.getPublic(),null);
            LauncherUpdates.Release release=updates.accept(valid);expect(release.newer());release.verifyJar(jar,key.getPublic());checks++;
            expect(updates.accept(valid).serial==4001);
            reject(()->updates.accept(catalog(key,"0.4.0",hash,Files.size(jar),"xmage-laboratorio-launcher")));
            reject(()->updates.accept(catalog(key,"0.4.1",String.join("",Collections.nCopies(64,"b")),Files.size(jar),"xmage-laboratorio-launcher")));
            reject(()->LauncherUpdates.verify(catalog(key,"0.4.1",hash,Files.size(jar),"xmage-laboratorio"),key.getPublic()));
            reject(()->LauncherUpdates.verify(valid,gen.generateKeyPair().getPublic()));
            byte[] tampered=valid.clone();tampered[30]^=1;reject(()->LauncherUpdates.verify(tampered,key.getPublic()));
            reject(()->LauncherUpdates.number("0.04.1"));reject(()->LauncherUpdates.number("1.0.0-beta"));
            reject(()->LauncherUpdates.verify(catalog(key,"0.4.1",hash,LauncherUpdates.MAX_JAR+1,"xmage-laboratorio-launcher"),key.getPublic()));
            Files.write(jar,new byte[]{1},StandardOpenOption.APPEND);reject(()->release.verifyJar(jar,key.getPublic()));
            Path target=root.resolve("launcher.jar"),candidate=root.resolve("candidate.jar");Files.write(target,new byte[]{1,2,3});Files.write(candidate,new byte[]{4,5,6});
            String old=Installation.sha256(target),next=Installation.sha256(candidate);
            Path backup=root.resolve("old.jar");LauncherUpdater.replaceAndRestart(target,candidate,backup,old,next,()->true);
            expect(Installation.sha256(target).equals(next));expect(Installation.sha256(backup).equals(old));
            Files.copy(backup,target,StandardCopyOption.REPLACE_EXISTING);
            reject(()->LauncherUpdater.replaceAndRestart(target,candidate,root.resolve("backup2.jar"),old,next,()->false));
            expect(Installation.sha256(target).equals(old));
            reject(()->LauncherUpdater.replaceAndRestart(target,candidate,root.resolve("backup3.jar"),old,old,()->true));expect(Installation.sha256(target).equals(old));
            Path protectedData=root.resolve("laboratorio/report.json");Files.createDirectories(protectedData.getParent());Files.write(protectedData,new byte[]{42});
            LauncherUpdater.replaceAndRestart(target,candidate,root.resolve("backup4.jar"),old,next,()->true);expect(Files.readAllBytes(protectedData)[0]==42);
            reject(()->LauncherUpdater.validateStage(root,root));
            expect(LauncherPanel.overviewText(false,false,false,false).equals("Controllo da eseguire"));
            expect(LauncherPanel.overviewText(false,true,false,false).equals("Tutto aggiornato"));
            expect(LauncherPanel.overviewText(false,true,true,false).equals("Controllo non completato"));
            expect(LauncherPanel.overviewText(false,true,false,true).equals("Aggiornamento disponibile"));
            expect(LauncherPanel.overviewText(true,true,false,false).equals("Controllo in corso…"));
            expect(LabUpdates.compareVersions("10.0.u402688371","10.0")>0);
            expect(LabUpdates.compareVersions("1.4.62-V1","1.4.61-V1")>0);
            expect(LabUpdates.compareVersions("9.2","10.0")<0);
            System.out.println(checks+" launcher security, rollback and status checks passed");
        } finally { ArchiveFiles.deleteTree(root); }
    }
}
