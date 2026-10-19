package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import jadx.api.*;
import jadx.api.data.ICodeData;
import jadx.core.dex.nodes.*;

/** Owns the native decompiler and loaded analysis state. */
final class JadxSession implements AutoCloseable {
    private final JadxArgs args = new JadxArgs();
    private final JadxDecompiler decompiler;
    private final RootNode root;
    private final JadxInputs.Inventory inventory;
    private final List<String> loadedInputs = new ArrayList<>();
    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, Object> resources = new LinkedHashMap<>();
    private final List<String> loadFailures = new ArrayList<>(), resourceErrors = new ArrayList<>();
    private final int loadErrors;
    private final double loadSeconds;
    private final JadxCodeCache codeCache;
    private boolean tablesLoaded;

    JadxSession(Path directory, JadxInputs.Inputs input, JadxEditStore edits) throws Exception {
        inventory = input.inventory();
        List<File> files = new ArrayList<>();
        for (Path path : input.paths()) {
            files.add(path.toFile());
            loadedInputs.add(directory.getParent().getParent().relativize(path).toString());
        }
        int threads = Integer.parseInt(System.getenv().getOrDefault("REFORGE_JADX_THREADS", "2"));
        if (threads < 1) throw new IllegalArgumentException("REFORGE_JADX_THREADS must be positive");
        args.setThreadsCount(threads);
        args.setInputFiles(files);
        args.setCodeData(JadxEditStore.codeData(edits.committed()));
        decompiler = new JadxDecompiler(args);
        long started = System.nanoTime();
        Path log = directory.resolve("worker.log");
        try {
            long logStart = Files.exists(log) ? Files.size(log) : 0;
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
            codeCache = new JadxCodeCache(directory, args, root, classes());
            codeCache.invalidate();
            loadSeconds = (System.nanoTime() - started) / 1e9;
            int index = 0;
            for (ResourceFile resource : decompiler.getResources()) {
                String name = resource.getOriginalName();
                resources.put(resources.containsKey(name) ? index + ":" + name : name, resource);
                index++;
            }
        } catch (Exception | Error failure) {
            try { decompiler.close(); }
            catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    Map<String, ClassNode> classes() { return Collections.unmodifiableMap(classes); }
    Map<String, Object> resources() { return Collections.unmodifiableMap(resources); }
    List<String> resourceErrors() { return List.copyOf(resourceErrors); }
    List<PackageNode> packages() { return List.copyOf(root.getPackages()); }
    PackageNode resolvePackage(String name) { return root.resolvePackage(name); }
    boolean hasUsageErrors() { return loadErrors != 0 || !loadFailures.isEmpty(); }
    boolean tablesLoaded() { return tablesLoaded; }
    void finishResourceTables() { tablesLoaded = true; }
    void addResource(String table, String name, Object resource) { resources.put(resources.containsKey(name) ? table + ":" + name : name, resource); }
    void resourceError(String error) { resourceErrors.add(error); }
    JadxCodeCache codeCache() { return codeCache; }
    void applyCodeData(ICodeData data) { args.setCodeData(data); decompiler.reloadCodeData(); }

    Status status(int loadCount) throws IOException {
        List<String> limitations = new ArrayList<>(loadFailures);
        limitations.addAll(resourceErrors);
        JadxInputs.InventoryStatus inputStatus = inventory.status();
        limitations.addAll(inputStatus.limitations());
        if (loadErrors != 0) limitations.add("JADX reported " + loadErrors + " loading/usage-analysis errors; see worker.log");
        if (inputStatus.hasDex() && classes.isEmpty()) limitations.add("Registered bytecode yielded no classes");
        if (classes.isEmpty() && resources.isEmpty()) limitations.add("No classes or Android resources loaded");
        return new Status(inventory, List.copyOf(loadedInputs), classes.size(),
                classes.values().stream().map(ClassNode::getPackage).distinct().count(),
                resources.size(), resources.keySet().stream().limit(30).toList(),
                "First 30 names; get_all_resource_file_names provides the full inventory",
                List.copyOf(limitations), loadSeconds, ProcessHandle.current().pid(), loadCount, codeCache.stats());
    }

    @Override public void close() { decompiler.close(); }
}
