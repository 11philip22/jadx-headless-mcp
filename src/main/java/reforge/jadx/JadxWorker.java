package reforge.jadx;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jadx.api.*;
import jadx.api.plugins.JadxPlugin;
import jadx.gui.cache.code.disk.*;
import static reforge.jadx.JadxModels.*;

/** Job-owned bootstrap and serialized lifecycle; discovery never loads analysis. */
public final class JadxWorker implements AutoCloseable {
    private final Path workspace;
    private PrintStream log;
    private int loadCount;
    private Loaded loaded;
    private boolean stopping;

    private record Loaded(JadxSession session, JadxServices.Classes classes, JadxServices.Decompiler decompiler,
                          JadxServices.Search search, JadxServices.Resources resources, JadxServices.Manifests manifests, JadxServices.Edits edits)
            implements AutoCloseable {
        @Override public void close() { session.close(); }
    }

    @FunctionalInterface interface Operation<T> { T run() throws Exception; }

    JadxWorker(Path workspace) { this.workspace = workspace.toAbsolutePath().normalize(); }

    private void load() throws Exception {
        if (loaded != null) return;
        JadxInputs.Inputs input = JadxInputs.read(workspace);
        Path directory = workspace.resolve("analysis/jadx");
        Files.createDirectories(directory);
        if (log == null) {
            log = new PrintStream(new FileOutputStream(directory.resolve("worker.log").toFile(), true), true, StandardCharsets.UTF_8);
            System.setErr(log);
            System.setOut(log);
        }
        JadxEditStore store = new JadxEditStore(directory);
        JadxSession session = new JadxSession(directory, input, store);
        try {
            JadxNodes nodes = new JadxNodes(session);
            ClassService classes = new ClassService(session, nodes);
            DecompilerService decompiler = new DecompilerService(nodes, session.codeCache());
            ResourceService resources = new ResourceService(session);
            loaded = new Loaded(session, classes, decompiler, new SearchService(session, nodes, decompiler, store),
                    resources, new ManifestService(resources, classes, decompiler), new EditService(session, nodes, decompiler, store));
            loadCount++;
        } catch (Exception | Error failure) {
            try { session.close(); }
            catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    JadxServices.Classes classes() { return loaded.classes(); }
    JadxServices.Decompiler decompiler() { return loaded.decompiler(); }
    JadxServices.Search search() { return loaded.search(); }
    JadxServices.Resources resources() { return loaded.resources(); }
    JadxServices.Manifests manifests() { return loaded.manifests(); }
    JadxServices.Edits edits() { return loaded.edits(); }

    Readiness readiness(boolean reload) {
        try {
            if (reload) close();
            load();
            loaded.resources().loadTables();
            String manifestError = loaded.manifests().readinessError();
            return new Readiness(loaded.session().status(loadCount), manifestError, loadCount);
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            return new Readiness(null, "Android manifest unavailable: " + ex.getMessage(), loadCount);
        }
    }

    synchronized <T> T call(String name, Operation<T> operation, Function<Exception, T> onFailure) {
        try {
            if (stopping) throw new IllegalStateException("Worker is shutting down");
            if (!Set.of("status", "reload_inputs").contains(name)) load();
            return operation.run();
        } catch (Exception ex) { return onFailure.apply(ex); }
    }

    @Override public synchronized void close() {
        Loaded previous = loaded;
        loaded = null;
        if (previous != null) previous.close();
    }

    private synchronized void shutdown() {
        stopping = true;
        close();
    }

    public static void main(String[] argv) throws Exception {
        OutputStream output = new FileOutputStream(FileDescriptor.out);
        System.setOut(System.err);
        if (Runtime.version().feature() < 21) throw new IllegalStateException("Java 21 or newer is required");
        if (!JadxDecompiler.getVersion().equals("1.5.6")) throw new IllegalStateException("This server requires JADX 1.5.6");
        Set<String> plugins = new HashSet<>();
        for (JadxPlugin plugin : ServiceLoader.load(JadxPlugin.class)) plugins.add(plugin.getPluginInfo().getPluginId());
        if (!plugins.containsAll(Set.of("dex-input", "apkm-input", "xapk-input")))
            throw new IllegalStateException("Required JADX input plugins missing: " + plugins);
        if (!ICodeCache.class.isAssignableFrom(BufferCodeCache.class) || !ICodeCache.class.isAssignableFrom(DiskCodeCache.class))
            throw new IllegalStateException("JADX disk/buffer cache unavailable");
        if (argv.length != 2 || !argv[0].equals("--workspace"))
            throw new IllegalArgumentException("Usage: JadxWorker --workspace <job-root>");
        JadxWorker worker = new JadxWorker(Path.of(argv[1]));
        // The SDK reads stdin in blocks; disconnect must also stop JADX's cache writers.
        InputStream input = new FilterInputStream(System.in) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    int count = super.read(bytes, offset, length);
                    if (count == -1) worker.shutdown();
                    return count;
                } catch (IOException failure) {
                    worker.shutdown();
                    throw failure;
                }
            }
        };
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, output);
        var server = McpServer.sync(transport)
            .serverInfo("reforge-jadx", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .tools(new JadxTools(worker).tools).build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { worker.shutdown(); }
            finally { server.close(); }
        }));
    }
}
