import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import jadx.api.*;
import jadx.api.plugins.JadxPlugin;
import jadx.core.dex.nodes.*;
import jadx.gui.cache.code.disk.*;

/** Job-owned, headless JADX 1.5.6 worker. Only JSON values cross the process boundary. */
public final class JadxWorker implements AutoCloseable {
    private final JadxSession session;
    private final JadxSources sources;
    private final JadxResources resourceTools;
    private final JadxEdits editTools;

    JadxWorker(Path directory, JsonObject input) throws Exception {
        session = new JadxSession(directory, input);
        sources = new JadxSources(session);
        resourceTools = new JadxResources(session);
        editTools = new JadxEdits(session, sources);
    }

    @Override public void close() { session.close(); }

    // Explicit allowlists also reject misspelled arguments on the raw process interface.
    static void arguments(String operation, JsonObject a) {
        String names = switch (operation) {
            case "load" -> "inputs inventory";
            case "status", "get_package_tree", "get_android_manifest", "get_main_application_classes_names", "get_main_activity_class", "get_cache_stats", "clear_cache" -> "";
            case "get_all_classes", "get_all_resource_file_names", "get_strings", "get_main_application_classes_code" -> "offset count";
            case "get_class_source" -> "class_name with_line_numbers";
            case "get_methods_of_class", "get_fields_of_class", "get_smali_of_class", "list_comments" -> "class_name";
            case "get_method_by_name" -> "class_name method_name method_signature";
            case "search_method_by_name" -> "method_name";
            case "search_classes_by_keyword" -> "search_term package search_in offset count";
            case "get_resource_file" -> "resource_name";
            case "get_manifest_component" -> "component_type only_exported";
            case "get_xrefs_to_class" -> "class_name offset count";
            case "get_xrefs_to_method" -> "class_name method_name offset count";
            case "get_xrefs_to_field" -> "class_name field_name offset count";
            case "rename_class" -> "class_name new_name";
            case "rename_method" -> "method_name new_name class_name method_signature";
            case "rename_field" -> "class_name field_name new_name";
            case "rename_package" -> "old_package_name new_package_name";
            case "rename_variable" -> "class_name method_name variable_name new_name reg ssa";
            case "add_comment" -> "class_name comment method_name method_signature field_name line style";
            default -> throw new IllegalArgumentException("Unregistered operation: " + operation);
        };
        Set<String> allowed = Set.of(names.split(" "));
        for (String key : a.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unexpected argument: " + key);
        if (operation.equals("load")) for (String key : List.of("inputs", "inventory"))
            if (!a.has(key) || !a.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
    }

    JsonObject dispatch(String operation, JsonObject a) throws Exception {
        int offset = WorkerJson.integer(a, "offset", 0), count = WorkerJson.integer(a, "count", operation.startsWith("get_xrefs_") || operation.equals("search_classes_by_keyword") ? 20 : 0);
        return switch (operation) {
            case "status" -> session.status();
            case "get_all_classes" -> WorkerJson.page(session.classes.values().stream().map(JadxSources::describe).toList(), offset, count);
            case "get_package_tree" -> {
                Map<String, Integer> counts = new LinkedHashMap<>();
                session.classes.values().forEach(c -> counts.merge(c.getPackage(), 1, Integer::sum));
                yield WorkerJson.obj("total_classes", session.classes.size(), "total_packages", counts.size(), "packages",
                        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                .map(e -> WorkerJson.obj("name", e.getKey(), "class_count", e.getValue())).toList());
            }
            case "get_class_source" -> sources.classSource(WorkerJson.string(a, "class_name"), WorkerJson.bool(a, "with_line_numbers", false));
            case "get_methods_of_class" -> WorkerJson.obj("methods", sources.cls(WorkerJson.string(a, "class_name")).getMethods().stream().map(JadxSources::describe).toList());
            case "get_fields_of_class" -> WorkerJson.obj("fields", sources.cls(WorkerJson.string(a, "class_name")).getFields().stream().map(JadxSources::describe).toList());
            case "get_smali_of_class" -> {
                ClassNode cls = sources.cls(WorkerJson.string(a, "class_name"));
                String smali = cls.getDisassembledCode();
                if (smali.isEmpty()) throw new IllegalStateException("No bytecode disassembly available");
                yield WorkerJson.extend(JadxSources.describe(cls), "smali", smali);
            }
            case "get_method_by_name" -> {
                MethodNode method = JadxSources.one(sources.methods(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "method_name"), WorkerJson.string(a, "method_signature", null)), "method");
                sources.source(method.getParentClass());
                String text = method.getCodeStr();
                if (WorkerJson.strip(text).isEmpty()) throw new IllegalStateException("Method has no rendered source (possibly inlined or suppressed); inspect class or smali");
                yield WorkerJson.extend(JadxSources.describe(method), "code", text, "analysis_errors", sources.sourceErrors(method.getParentClass()));
            }
            case "search_method_by_name" -> {
                String term = WorkerJson.fold(WorkerJson.string(a, "method_name"));
                yield WorkerJson.obj("methods", session.classes.values().stream().flatMap(c -> c.getMethods().stream())
                        .filter(m -> WorkerJson.fold(m.getAlias()).contains(term) || WorkerJson.fold(m.getName()).contains(term)).map(JadxSources::describe).toList());
            }
            case "search_classes_by_keyword" -> sources.search(a, offset, count);
            case "get_all_resource_file_names" -> {
                resourceTools.loadTables();
                yield WorkerJson.extend(WorkerJson.page(session.resources.keySet().stream().sorted().toList(), offset, count), "limitations", session.resourceErrors);
            }
            case "get_resource_file" -> resourceTools.resourceFile(WorkerJson.string(a, "resource_name"));
            case "get_strings" -> {
                resourceTools.loadTables();
                List<String> names = session.resources.keySet().stream().filter(n -> n.endsWith("/strings.xml")).toList();
                if (names.isEmpty()) throw new IllegalArgumentException("No strings.xml resources available; " + String.join("; ", session.resourceErrors));
                JsonObject result = WorkerJson.page(names, offset, count);
                JsonArray items = new JsonArray();
                for (JsonElement name : result.getAsJsonArray("items")) items.add(resourceTools.resourceFile(name.getAsString()));
                yield WorkerJson.extend(result, "items", items);
            }
            case "get_android_manifest" -> {
                JsonArray manifests = new JsonArray();
                for (String name : resourceTools.manifestNames()) manifests.add(resourceTools.resourceFile(name));
                yield WorkerJson.obj("manifests", manifests);
            }
            case "get_manifest_component" -> resourceTools.components(WorkerJson.string(a, "component_type"), WorkerJson.bool(a, "only_exported", false));
            case "get_main_application_classes_names" -> WorkerJson.obj("classes", resourceTools.applicationClasses().stream().map(JadxSources::describe).toList());
            case "get_main_application_classes_code" -> {
                JsonObject result = WorkerJson.page(resourceTools.applicationClasses().stream().map(ClassNode::getRawName).toList(), offset, count);
                JsonArray items = new JsonArray();
                for (JsonElement name : result.getAsJsonArray("items")) items.add(sources.classSource(name.getAsString(), false));
                yield WorkerJson.extend(result, "items", items);
            }
            case "get_main_activity_class" -> resourceTools.mainActivities();
            case "get_xrefs_to_class" -> sources.xrefs(List.of(sources.cls(WorkerJson.string(a, "class_name"))), offset, count);
            case "get_xrefs_to_method" -> {
                List<MethodNode> methods = sources.methods(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "method_name"), null);
                if (methods.isEmpty()) throw new IllegalArgumentException("Method unavailable");
                yield sources.xrefs(methods, offset, count);
            }
            case "get_xrefs_to_field" -> sources.xrefs(List.of(sources.field(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "field_name"))), offset, count);
            case "get_cache_stats" -> session.cacheStats();
            case "clear_cache" -> { session.resetCache(); session.hits = session.misses = 0; yield session.cacheStats(); }
            case "rename_class" -> editTools.renameNode(a, "CLASS");
            case "rename_method" -> editTools.renameNode(a, "METHOD");
            case "rename_field" -> editTools.renameNode(a, "FIELD");
            case "rename_package" -> editTools.renamePackage(WorkerJson.string(a, "old_package_name"), WorkerJson.string(a, "new_package_name"));
            case "rename_variable" -> editTools.renameVariable(a);
            case "add_comment" -> editTools.addComment(a);
            case "list_comments" -> {
                String name = WorkerJson.string(a, "class_name", ""), original = name.isEmpty() ? null : sources.cls(name).getRawName();
                yield WorkerJson.obj("comments", session.edits.getAsJsonArray("comments").asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(e -> original == null || WorkerJson.string(e.getAsJsonObject("node"), "class").equals(original)).toList());
            }
            default -> throw new IllegalArgumentException("Unregistered operation: " + operation);
        };
    }

    static void checkApi() {
        if (!JadxDecompiler.getVersion().equals("1.5.6")) throw new IllegalStateException("This worker requires JADX 1.5.6");
        Set<String> plugins = new HashSet<>();
        for (JadxPlugin plugin : ServiceLoader.load(JadxPlugin.class)) plugins.add(plugin.getPluginInfo().getPluginId());
        if (!plugins.containsAll(Set.of("dex-input", "apkm-input", "xapk-input")))
            throw new IllegalStateException("Required JADX input plugins missing: " + plugins);
        // Resolve the GUI cache dependency as well as core, even when no job is loaded.
        if (!ICodeCache.class.isAssignableFrom(BufferCodeCache.class) || !ICodeCache.class.isAssignableFrom(DiskCodeCache.class))
            throw new IllegalStateException("JADX disk/buffer cache unavailable");
    }

    public static void main(String[] argv) throws Exception {
        PrintStream output = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err); // Redirect before any JADX or logging classes initialize.
        checkApi();
        if (Arrays.equals(argv, new String[]{"--check-api"})) {
            System.err.println("JADX 1.5.6 library APIs and input plugins verified");
            return;
        }
        if (argv.length != 1) throw new IllegalArgumentException("Usage: JadxWorker <job-analysis-directory>");
        JadxWorker worker = null;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject response;
                try {
                    JsonObject request = WorkerJson.parse(line);
                    String operation = WorkerJson.string(request, "operation");
                    if (!request.has("arguments") || !request.get("arguments").isJsonObject())
                        throw new IllegalArgumentException("arguments must be an object");
                    JsonObject arguments = request.getAsJsonObject("arguments");
                    arguments(operation, arguments);
                    JsonObject result;
                    if (operation.equals("load") && worker == null) {
                        worker = new JadxWorker(Path.of(argv[0]), arguments);
                        result = worker.session.status();
                    } else if (worker == null) throw new IllegalStateException("JADX not loaded");
                    else result = worker.dispatch(operation, arguments);
                    response = WorkerJson.obj("result", result);
                } catch (Exception ex) {
                    ex.printStackTrace(System.err);
                    response = WorkerJson.obj("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
                output.println(WorkerJson.JSON.toJson(response));
            }
        } finally { if (worker != null) worker.close(); }
    }
}
