package mage.lab.preview;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.prefs.*;

/** File-backed preferences for the preview on Windows as well as Unix. Never opens the OS store. */
public final class PrivatePreferences implements PreferencesFactory {
    private final Preferences user;
    private final Preferences system;
    public PrivatePreferences() {
        String location = System.getProperty("lab.preview.preferences");
        if (location == null || location.isEmpty()) throw new IllegalStateException("Preview preferences directory required");
        Path root = Paths.get(location).toAbsolutePath();
        user = new Node(null, "", root.resolve("user"));
        system = new Node(null, "", root.resolve("system"));
    }
    public Preferences userRoot() { return user; }
    public Preferences systemRoot() { return system; }
    private static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    private static String decode(String value) {
        byte[] bytes = new byte[value.length()/2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i*2, i*2+2), 16);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static final class Node extends AbstractPreferences {
        private final Path directory;
        private final Properties values = new Properties();
        Node(Node parent, String name, Path directory) {
            super(parent, name); this.directory = directory;
            try { read(); } catch (IOException e) { throw new IllegalStateException("Impostazioni della prova non leggibili", e); }
        }
        private void read() throws IOException {
            values.clear();
            if (Files.exists(directory.resolve("values.properties")))
                try (InputStream in = Files.newInputStream(directory.resolve("values.properties"))) { values.load(in); }
        }
        private void save() {
            try {
                Files.createDirectories(directory);
                Path temporary = Files.createTempFile(directory, "prefs-", ".tmp");
                try {
                    try (OutputStream out = Files.newOutputStream(temporary)) { values.store(out, "XMage UI preview only"); }
                    try { Files.move(temporary, directory.resolve("values.properties"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temporary, directory.resolve("values.properties"), StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temporary); }
            } catch (IOException e) { throw new IllegalStateException("Impossibile salvare le impostazioni della prova", e); }
        }
        protected void putSpi(String key, String value) { values.setProperty(key, value); save(); }
        protected String getSpi(String key) { return values.getProperty(key); }
        protected void removeSpi(String key) { values.remove(key); save(); }
        protected String[] keysSpi() { return values.stringPropertyNames().toArray(new String[0]); }
        protected String[] childrenNamesSpi() throws BackingStoreException {
            if (!Files.isDirectory(directory)) return new String[0];
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) if (Files.isDirectory(child)) names.add(decode(child.getFileName().toString()));
            } catch (IOException | IllegalArgumentException e) { throw new BackingStoreException(e); }
            return names.toArray(new String[0]);
        }
        protected AbstractPreferences childSpi(String name) { return new Node(this, name, directory.resolve(encode(name))); }
        protected void removeNodeSpi() throws BackingStoreException {
            try { Files.deleteIfExists(directory.resolve("values.properties")); Files.deleteIfExists(directory); }
            catch (IOException e) { throw new BackingStoreException(e); }
        }
        protected void syncSpi() throws BackingStoreException {
            try { read(); } catch (IOException e) { throw new BackingStoreException(e); }
        }
        protected void flushSpi() { if (!isRemoved()) save(); }
    }
}
