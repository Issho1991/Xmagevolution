package mage.lab.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Bounded, portable ZIP handling. No overwrite, traversal, links or Windows aliases. */
final class ArchiveFiles {
    static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    static final int MAX_FILES = 100000;
    static final String INVENTORY = "files.sha256";
    static String safeName(String name) throws IOException {
        if (name.isEmpty() || name.length() > 220 || name.startsWith("/") || name.contains("\\") || name.contains(":"))
            throw new IOException("Percorso ZIP non valido: " + name);
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(" ") || part.endsWith(".")
                    || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?"))
                throw new IOException("Percorso ZIP non portabile: " + name);
            for (char c : part.toCharArray()) if (c < 32 || "<>\"|?*".indexOf(c) >= 0) throw new IOException("Nome ZIP non valido.");
        }
        return name;
    }
    static boolean payloadName(String n) {
        return n.equals("package.properties") || n.equals("launcher/distribuzione.properties") || n.equals(INVENTORY)
            || n.startsWith("mage-client/") || n.startsWith("mage-server/");
    }
    static Map<String, String> inventory(Path root) throws IOException {
        Path list = root.resolve(INVENTORY);
        if (Files.size(list) > 16 * 1024 * 1024) throw new IOException("Indice archivio troppo grande.");
        Map<String, String> entries = new LinkedHashMap<>(); Set<String> folded = new HashSet<>();
        try (BufferedReader in = Files.newBufferedReader(list, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] fields = line.split("\t", 2);
                if (fields.length != 2 || !fields[0].matches("[a-f0-9]{64}")) throw new IOException("Indice archivio non valido.");
                String name = safeName(fields[1]);
                if (name.equals(INVENTORY) || !folded.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Voce duplicata nell’indice.");
                entries.put(name, fields[0]);
                if (entries.size() > MAX_FILES) throw new IOException("Troppi file nell’archivio.");
            }
        }
        if (entries.isEmpty()) throw new IOException("Archivio vuoto.");
        return entries;
    }
    static int verify(Path root, boolean exact) throws IOException {
        Map<String, String> entries = inventory(root);
        for (Map.Entry<String,String> e : entries.entrySet()) {
            Path f = root.resolve(e.getKey());
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || !f.toRealPath().startsWith(root.toRealPath())
                    || !Installation.sha256(f).equals(e.getValue())) throw new IOException("File dell’archivio mancante o alterato: " + e.getKey());
        }
        if (exact) {
            Map<String,Path> found = new TreeMap<>(); collect(root, root, found);
            found.remove(INVENTORY);
            if (!found.keySet().equals(entries.keySet())) throw new IOException("File non dichiarati nell’archivio.");
        }
        return entries.size();
    }
    static void extract(Path zip, Path target, boolean packageOnly) throws IOException {
        Set<String> names = new HashSet<>(); long total = 0; int count = 0;
        try (ZipFile in = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> it = in.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement(); String name = e.getName();
                if (e.isDirectory()) { safeName(name.substring(0, name.length()-1)); continue; }
                safeName(name);
                if (++count > MAX_FILES || !names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("ZIP con file duplicati o troppi file.");
                if (packageOnly && !payloadName(name)) throw new IOException("Il pacchetto include dati o percorsi non consentiti: " + name);
                if (e.getSize() < 0 || e.getSize() > MAX_BYTES || total + e.getSize() > MAX_BYTES) throw new IOException("Archivio troppo grande o dimensione sconosciuta.");
                Path output = target.resolve(name); Files.createDirectories(output.getParent());
                long size = 0; CRC32 crc = new CRC32();
                try (InputStream source = in.getInputStream(e); OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = source.read(buffer)) != -1) {
                        size += n; total += n;
                        if (size > e.getSize() || total > MAX_BYTES) throw new IOException("Dimensioni ZIP incoerenti.");
                        crc.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                }
                if (size != e.getSize() || crc.getValue() != e.getCrc()) throw new IOException("ZIP incompleto o danneggiato: " + name);
            }
        }
        verify(target, true);
    }
    static void collect(Path origin, Path path, Map<String,Path> out) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) throw new IOException("Collegamento non supportato nel backup: " + dir);
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("File speciale o collegamento non supportato: " + file);
                String name = safeName(origin.relativize(file).toString().replace(File.separatorChar, '/'));
                out.put(name, file);
                if (out.size() > MAX_FILES) throw new IOException("Troppi file da archiviare.");
                return FileVisitResult.CONTINUE;
            }
        });
    }
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { if(e!=null) throw e; Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }
    static void atomicMove(Path from, Path to, boolean replace) throws IOException {
        // Same filesystem is required. Without atomic rename leave the previous state intact.
        if (replace) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else {
            if (Files.exists(to)) throw new IOException("Destinazione già presente: " + to);
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
    }
    static Properties properties(Path file) throws IOException {
        if (Files.size(file) > 1024 * 1024) throw new IOException("Descrizione troppo grande.");
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(file)) { p.load(in); } return p;
    }
    static String writeEntry(ZipOutputStream zip, String name, InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            zip.putNextEntry(new ZipEntry(safeName(name))); byte[] b = new byte[65536]; int n;
            while ((n=in.read(b)) != -1) { zip.write(b,0,n); digest.update(b,0,n); }
            zip.closeEntry(); StringBuilder s=new StringBuilder(); for(byte v:digest.digest())s.append(String.format(Locale.ROOT,"%02x",v&255)); return s.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
