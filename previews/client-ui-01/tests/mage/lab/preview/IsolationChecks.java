package mage.lab.preview;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.prefs.*;
public final class IsolationChecks {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath(); Files.createDirectories(root);
        if (args.length > 1) {
            Preferences p = Preferences.userRoot().node("/mage/client/nodo à");
            if (args[1].equals("write")) { p.put("image", "solo-prova"); p.flush(); }
            else if (!p.get("image", "").equals("solo-prova")) throw new AssertionError("Preferences did not survive a fresh JVM");
            return;
        }
        Path original = root.resolve("stabile-preferenze.txt"); Files.write(original, "NON MODIFICARE".getBytes(StandardCharsets.UTF_8));
        for (String mode : new String[]{"write", "read"}) {
            java.util.List<String> cmd = new ArrayList<>(Arrays.asList(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                    "-Djava.util.prefs.PreferencesFactory=mage.lab.preview.PrivatePreferences", "-Dlab.preview.preferences="+root.resolve("private"),
                    "-cp",System.getProperty("java.class.path"),IsolationChecks.class.getName(),root.toString(),mode));
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            if (!p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS) || p.exitValue()!=0) throw new AssertionError("Isolated preference process failed");
        }
        System.setProperty("lab.preview.preferences", root.resolve("private").toString());
        PrivatePreferences f = new PrivatePreferences();
        if (!Arrays.asList(f.userRoot().node("/mage/client").childrenNames()).contains("nodo à")) throw new AssertionError("Lost child node");
        f.userRoot().node("/mage/client/nodo à").removeNode(); f.userRoot().flush();
        if (new PrivatePreferences().userRoot().nodeExists("/mage/client/nodo à")) throw new AssertionError("Removed node returned");
        for (boolean modern : new boolean[]{true,false}) {
            java.util.List<String> cmd=PreviewLauncher.command(root,root.resolve("prova/anteprima.jar"),true,modern);
            if (!cmd.contains("-Dxmage.lab.ui.preview="+modern) || !cmd.contains("-Dxmage.lab.dir="+root.resolve("laboratorio")) || !cmd.contains("-Djava.util.prefs.PreferencesFactory=mage.lab.preview.PrivatePreferences")) throw new AssertionError("Mode or isolation was lost");
        }
        if (!new String(Files.readAllBytes(original),StandardCharsets.UTF_8).equals("NON MODIFICARE")) throw new AssertionError("Stable copy changed");
        System.out.println("PASS preference persistence across JVMs, private nodes, classic/new commands, stable marker");
    }
}
