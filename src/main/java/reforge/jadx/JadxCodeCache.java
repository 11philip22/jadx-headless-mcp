package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import java.io.IOException;
import java.nio.file.*;
import jadx.api.JadxArgs;
import jadx.core.dex.nodes.*;
import jadx.gui.cache.code.disk.*;

/** Owns native cache installation, invalidation, and source-request counters. */
final class JadxCodeCache {
    private final Path directory;
    private final JadxArgs args;
    private final RootNode root;
    private final Map<String, ClassNode> classes;
    private BufferCodeCache cache;
    private long hits, misses;

    JadxCodeCache(Path directory, JadxArgs args, RootNode root, Map<String, ClassNode> classes) {
        this.directory = directory;
        this.args = args;
        this.root = root;
        this.classes = classes;
    }

    void recordRequest(String originalId) { if (cache.contains(originalId)) hits++; else misses++; }
    void clear() throws IOException { invalidate(); hits = misses = 0; }

    void invalidate() throws IOException {
        // shortcut: edits invalidate all classes; use usage-based invalidation if frequent edits become expensive.
        if (cache != null) {
            cache.close(); // Wait for the disk writer before removing cached data.
            List<ClassNode> unloaded = new ArrayList<>();
            for (ClassNode cls : classes.values()) if (!cls.isInner() && cls.getState() != ProcessState.NOT_LOADED) {
                cls.unloadCode();
                unloaded.add(cls);
            }
            // Deep unload replaces method nodes; restore metadata-only passes before the next source request.
            unloaded.forEach(root::runPreDecompileStageForClass);
        }
        Path path = directory.resolve("cache");
        if (Files.exists(path)) try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(child);
        }
        cache = new BufferCodeCache(new DiskCodeCache(root, path));
        args.setCodeCache(cache);
    }

    CacheStats stats() throws IOException {
        long bytes = 0;
        Path path = directory.resolve("cache");
        if (Files.exists(path)) try (var files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) bytes += Files.size(file);
        }
        return new CacheStats("JADX BufferCodeCache + DiskCodeCache", 20,
                classes.values().stream().filter(c -> !c.isInner() && cache.contains(c.getRawName())).count(),
                bytes, hits, misses);
    }

}
