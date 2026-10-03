import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import jadx.api.*;
import jadx.core.dex.nodes.*;
import jadx.gui.cache.code.disk.*;

/** Owns the loaded job, shared inventories, edits, and source cache. */
final class JadxSession implements AutoCloseable {
    final Path directory;
    final JadxArgs args = new JadxArgs();
    final JadxDecompiler decompiler;
    final RootNode root;
    final JsonArray inventory;
    final List<String> loadedInputs = new ArrayList<>();
    final Map<String, ClassNode> classes = new LinkedHashMap<>();
    final Map<String, Object> resources = new LinkedHashMap<>();
    final List<String> loadFailures = new ArrayList<>(), resourceErrors = new ArrayList<>();
    final int loadErrors;
    final double loadSeconds;
    JsonObject edits;
    BufferCodeCache cache;
    long hits, misses;
    boolean tablesLoaded;

    JadxSession(Path directory, JsonObject input) throws Exception {
        this.directory = directory;
        inventory = input.getAsJsonArray("inventory");
        List<File> files = new ArrayList<>();
        for (JsonElement item : input.getAsJsonArray("inputs")) {
            Path path = Path.of(item.getAsString());
            files.add(path.toFile());
            loadedInputs.add(directory.getParent().getParent().relativize(path).toString());
        }
        Path saved = directory.resolve("edits.json");
        edits = Files.exists(saved) ? WorkerJson.parse(Files.readString(saved)) : WorkerJson.obj("renames", new JsonArray(), "comments", new JsonArray());
        int threads = Integer.parseInt(System.getenv().getOrDefault("REFORGE_JADX_THREADS", "2"));
        if (threads < 1) throw new IllegalArgumentException("REFORGE_JADX_THREADS must be positive");
        args.setThreadsCount(threads);
        args.setInputFiles(files);
        args.setCodeData(JadxEdits.codeData(edits));
        decompiler = new JadxDecompiler(args);
        long started = System.nanoTime();
        Path log = directory.resolve("worker.log");
        long logStart = Files.exists(log) ? Files.size(log) : 0;
        try {
            decompiler.load();
            root = decompiler.getRoot();
            for (ClassNode cls : root.getClasses()) classes.put(cls.getRawName(), cls);
            loadErrors = decompiler.getErrorsCount();
            // Some input plugins log failures without incrementing the node error counter.
            if (Files.exists(log)) {
                try (var stream = Files.newInputStream(log)) {
                    stream.skipNBytes(logStart);
                    for (String line : WorkerJson.lines(new String(stream.readAllBytes(), StandardCharsets.UTF_8)))
                        if (line.startsWith("ERROR") || line.contains("Invalid DEX magic")) loadFailures.add(line);
                }
            }
            resetCache();
            loadSeconds = (System.nanoTime() - started) / 1e9;
            int index = 0;
            for (ResourceFile resource : decompiler.getResources()) {
                String name = resource.getOriginalName();
                resources.put(resources.containsKey(name) ? index + ":" + name : name, resource);
                index++;
            }
        } catch (Exception | Error failure) {
            decompiler.close();
            throw failure;
        }
    }

    JsonObject status() throws IOException {
        List<String> limitations = new ArrayList<>(loadFailures);
        limitations.addAll(resourceErrors);
        boolean hasDex = false;
        for (JsonElement row : inventory) {
            JsonObject item = row.getAsJsonObject();
            if (item.has("limitation") && !item.get("limitation").isJsonNull() && !item.get("limitation").getAsString().isEmpty())
                limitations.add(item.get("limitation").getAsString());
            hasDex |= item.has("has_dex") && item.get("has_dex").getAsBoolean();
        }
        if (loadErrors != 0) limitations.add("JADX reported " + loadErrors + " loading/usage-analysis errors; see worker.log");
        if (hasDex && classes.isEmpty()) limitations.add("Registered bytecode yielded no classes");
        if (classes.isEmpty() && resources.isEmpty()) limitations.add("No classes or Android resources loaded");
        return WorkerJson.obj("inputs", inventory, "loaded_inputs", loadedInputs, "class_count", classes.size(),
                "package_count", classes.values().stream().map(ClassNode::getPackage).distinct().count(),
                "resource_count", resources.size(), "resources", resources.keySet().stream().limit(30).toList(),
                "resource_listing", "First 30 names; get_all_resource_file_names provides the full inventory",
                "limitations", limitations, "load_seconds", loadSeconds, "pid", ProcessHandle.current().pid(),
                "load_count", 1, "cache", cacheStats());
    }

    void resetCache() throws IOException {
        // ponytail: edits invalidate all classes; use usage-based invalidation if frequent edits become expensive.
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

    JsonObject cacheStats() throws IOException {
        long bytes = 0;
        Path path = directory.resolve("cache");
        if (Files.exists(path)) try (var files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) bytes += Files.size(file);
        }
        return WorkerJson.obj("implementation", "JADX BufferCodeCache + DiskCodeCache", "buffer_capacity", 20,
                "cached_classes", classes.values().stream().filter(c -> !c.isInner() && cache.contains(c.getRawName())).count(),
                "disk_bytes", bytes, "source_request_hits", hits, "source_request_misses", misses);
    }

    @Override public void close() { decompiler.close(); }
}
