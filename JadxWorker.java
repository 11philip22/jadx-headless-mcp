import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jadx.api.*;
import jadx.api.plugins.JadxPlugin;
import jadx.gui.cache.code.disk.*;

/** Job-owned JADX MCP server. Discovery is independent of analysis loading. */
public final class JadxWorker implements AutoCloseable {
    static final String INSTRUCTIONS = """
        Analyze the Android inputs registered in analysis/jadx/inputs.json in this workspace.
        Inputs load on first analysis access. Use status for readiness and reload_inputs after
        recovering inputs; reload retains this MCP connection and restores persisted edits.
        Start with packages, class/member metadata, and scoped searches. Source is generated
        on demand; code searches progressively decompile their selected package. Empty usage
        results and decompilation failures do not establish that code is unreachable.
        Respect pagination: count=0 requests all results. Prefer bounded pages for large apps.
        Original node IDs remain valid after renames. Use signatures for overloads and current
        numbered source/variable IDs before line comments or variable renames. Edits are verified,
        rolled back on failure, and persisted under analysis/jadx for the next process.
        Resources and manifests retain split provenance; binary resources are base64 encoded.
        Cache statistics describe source caching, not total JVM memory. Clearing the cache keeps
        loaded inputs and persisted edits. Calls share one serialized analysis session per workspace.
        """;

    private final Path workspace;
    private PrintStream log;
    private int loadCount;
    JadxSession session;
    JadxSources sources;
    JadxResources resourceTools;
    JadxEdits editTools;

    JadxWorker(Path workspace) { this.workspace = workspace.toAbsolutePath().normalize(); }

    private void load() throws Exception {
        if (session != null) return;
        JsonObject input = JadxInputs.read(workspace);
        Path directory = workspace.resolve("analysis/jadx");
        Files.createDirectories(directory);
        if (log == null) {
            log = new PrintStream(new FileOutputStream(directory.resolve("worker.log").toFile(), true), true, StandardCharsets.UTF_8);
            System.setErr(log);
            System.setOut(log);
        }
        session = new JadxSession(directory, input);
        loadCount++;
        sources = new JadxSources(session);
        resourceTools = new JadxResources(session);
        editTools = new JadxEdits(session, sources);
    }

    JsonObject readiness(boolean reload) {
        try {
            if (reload) close();
            load();
            resourceTools.loadTables();
            String manifestError = null;
            try {
                boolean base = resourceTools.manifests().values().stream().anyMatch(manifest ->
                    manifest.getTagName().equals("manifest") && manifest.getAttribute("split").isEmpty()
                    && !manifest.getAttribute("package").isBlank());
                if (!base) throw new IllegalArgumentException("No readable base AndroidManifest.xml with a package ID");
            } catch (Exception ex) { manifestError = "Android manifest unavailable: " + ex.getMessage(); }
            JsonObject result = WorkerJson.extend(session.status(), "load_count", loadCount);
            if (manifestError != null) {
                result.addProperty("manifest_error", manifestError);
                result.getAsJsonArray("limitations").add(manifestError);
            }
            return result;
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            String error = "Android manifest unavailable: " + ex.getMessage();
            return WorkerJson.obj("class_count", 0, "package_count", 0, "resource_count", 0,
                "resources", List.of(), "limitations", List.of(error), "manifest_error", error,
                "pid", ProcessHandle.current().pid(), "load_count", loadCount);
        }
    }

    synchronized McpSchema.CallToolResult call(String name, JsonObject arguments, JadxTools.Handler handler) {
        try {
            if (!Set.of("status", "reload_inputs").contains(name)) load();
            int offset = WorkerJson.integer(arguments, "offset", 0);
            int count = WorkerJson.integer(arguments, "count", 0);
            JsonObject result = handler.call(arguments, offset, count);
            String json = WorkerJson.JSON.toJson(result);
            return McpSchema.CallToolResult.builder().addTextContent(json)
                .structuredContent(McpJsonDefaults.getMapper(), json).build();
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            return McpSchema.CallToolResult.builder().isError(true)
                .addTextContent(ex.getClass().getSimpleName() + ": " + ex.getMessage()).build();
        }
    }

    @Override public synchronized void close() {
        if (session != null) session.close();
        session = null;
        sources = null;
        resourceTools = null;
        editTools = null;
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
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in, output);
        var server = McpServer.sync(transport)
            .serverInfo("reforge-jadx", "1.0.0")
            .instructions(INSTRUCTIONS)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .tools(new JadxTools(worker).tools).build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { worker.close(); server.close(); }));
        // SDK stdio threads own the connection; EOF closes its transport.
    }
}
