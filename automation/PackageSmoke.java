package mage.lab.launcher;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** A candidate is trusted only inside this isolated test, before publication/signing. */
public final class PackageSmoke {
 public static void main(String[] args)throws Exception {
  Path root=Paths.get(args[0]).toRealPath(),zip=Paths.get(args[1]),evidence=Paths.get(args[2]);String id=args[3];
  Properties trust=VersionManager.trust();
  trust.setProperty(id+".zip",Installation.sha256(zip));
  try(ZipFile z=new ZipFile(zip.toFile())) {
   java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();
   try(java.io.InputStream in=z.getInputStream(z.getEntry("files.sha256"))) {byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);}
   trust.setProperty(id+".inventory",UpdateNetwork.hash(b.toByteArray()));
  }
  VersionManager versions=new VersionManager(root,trust);Installation original=versions.active();
  Path marker=original.data.resolve("automatic-check.txt");Files.createDirectories(marker.getParent());Files.write(marker,"before".getBytes(StandardCharsets.UTF_8));
  try(ProcessSupervisor processes=new ProcessSupervisor(original)) {
   processes.lock();if(!id.equals(versions.install(zip,processes,System.out::println)))throw new AssertionError("Wrong package");
   Path backup=versions.activate(id,processes,System.out::println);Installation runtime=processes.installation;
   if(!runtime.data.equals(original.data)||runtime.verify()<190)throw new AssertionError("Invalid runtime");
   Path log=evidence.resolve("server.log");
   Process server=new ProcessBuilder(processes.serverCommand(Installation.currentJava())).directory(runtime.root.resolve("mage-server").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
   try {ProcessSupervisor.awaitReady(server,log,runtime.endpoint(),90000);}
   finally {server.destroy();if(!server.waitFor(8,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(5,TimeUnit.SECONDS);}}
   Files.write(marker,"after".getBytes(StandardCharsets.UTF_8));versions.activate(VersionManager.ORIGINAL,processes,System.out::println);
   if(!"after".equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)))throw new AssertionError("New data lost");
   Path copy=versions.recover(backup,processes,System.out::println);
   if(!"before".equals(new String(Files.readAllBytes(copy.resolve("laboratorio/automatic-check.txt")),StandardCharsets.UTF_8)))throw new AssertionError("Backup invalid");
   System.out.println("PASS BACKUP_ACTIVATION_SERVER_ROLLBACK");
  }
 }
}
