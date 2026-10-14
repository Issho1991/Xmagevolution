package mage.lab.launcher;

import java.util.prefs.Preferences;
import java.nio.file.*;

/** Set the same connection preferences as the client's Connect dialog, then run its unchanged entry point. */
public final class ClientBootstrap {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("lab.launcher.port"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Porta non valida");
        Preferences p = Preferences.userRoot().node("/mage/client");
        p.put("serverAddress", "127.0.0.1");
        p.putInt("serverPort", port);
        // Preserve the resolved image locations when the runtime moves to a versioned folder.
        String base = System.getProperty("lab.launcher.assets.base");
        if (base != null && !Paths.get(base).toRealPath().equals(Paths.get("").toRealPath())) {
            Path origin = Paths.get(base).toAbsolutePath();
            if (p.getBoolean("cardImagesUseDefault", true)) {
                p.put("cardImagesPath", origin.resolve("plugins/images").toString());
                p.putBoolean("cardImagesUseDefault", false);
            } else {
                absolutePreference(p, "cardImagesPath", origin);
            }
            absolutePreference(p, "backgroundImage", origin);
            absolutePreference(p, "battlefieldImage", origin);
        }
        // Username, password, auto-connect and game/AI settings are preserved.
        p.flush();
        Class.forName("mage.client.MageFrame").getMethod("main", String[].class).invoke(null, (Object) args);
    }
    private static void absolutePreference(Preferences p, String key, Path origin) {
        String value = p.get(key, "");
        if (!value.isEmpty() && !Paths.get(value).isAbsolute()) p.put(key, origin.resolve(value).normalize().toString());
    }
}
