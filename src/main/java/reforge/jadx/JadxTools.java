package reforge.jadx;

import com.google.gson.*;
import java.util.*;
import static reforge.jadx.JadxModels.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/** MCP schemas, argument decoding, handler registration, and response encoding. */
final class JadxTools {
    private static final Gson WIRE = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    private static final Gson DISPLAY = WorkerJson.JSON.newBuilder().setPrettyPrinting().create();
    @FunctionalInterface interface Handler {
        Object call(JadxArguments arguments) throws Exception;
    }
    private final JadxWorker worker;
    final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

    JadxTools(JadxWorker worker) {
        this.worker = worker;
        add("status", "Load registered inputs if needed and check Android readiness, including the base manifest.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            a -> worker.readiness(false));
        add("reload_inputs", "Reload the workspace input inventory and persisted edits in this process; return updated Android readiness.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            a -> worker.readiness(true));
        add("load_apk", "Load a workspace-relative APK, APKM, XAPK, or DEX and remember it for future runs. Repeated paths are not added twice.",
            """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """, a -> worker.loadApk(a.string("path")));
        add("unload_apk", "Unregister a workspace-relative input and reload the remainder. Containers also remove their registered members. Preserve files and saved edits.",
            """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
            """, a -> worker.unloadApk(a.string("path")));
        add("get_all_classes",
            "List bytecode classes, including inner classes, without decompiling; count=0 returns all.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, a -> worker.classes().allClasses(a.page()));
        add("get_package_tree",
            "List packages and their class counts without decompiling.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, a -> worker.classes().packageTree());
        add("get_class_source",
            "Decompile one class (inner classes return their enclosing source). Includes variable IDs and commentable lines; reread before line edits.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "with_line_numbers": {"type": "boolean", "default": false}}, "required": ["class_name"], "additionalProperties": false}
            """, a -> worker.decompiler().classSource(a.string("class_name"), a.bool("with_line_numbers", false)));
        add("get_methods_of_class",
            "List methods with stable original IDs and overload signatures, without decompiling.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}}, "required": ["class_name"], "additionalProperties": false}
            """, a -> worker.classes().methods(a.string("class_name")));
        add("get_fields_of_class",
            "List fields with stable original IDs and types, without decompiling.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}}, "required": ["class_name"], "additionalProperties": false}
            """, a -> worker.classes().fields(a.string("class_name")));
        add("get_smali_of_class",
            "Retrieve a class's bytecode disassembly.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}}, "required": ["class_name"], "additionalProperties": false}
            """, a -> worker.classes().smali(a.string("class_name")));
        add("get_method_by_name",
            "Retrieve method source; pass a short ID or method_signature such as (I)V for overloads.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "method_name": {"type": "string"}, "method_signature": {"type": ["string", "null"], "default": null}}, "required": ["class_name", "method_name"], "additionalProperties": false}
            """, a -> worker.decompiler().methodSource(a.method(false)));
        add("search_method_by_name",
            "Search method names by substring across bytecode metadata.",
            """
            {"type": "object", "properties": {"method_name": {"type": "string"}}, "required": ["method_name"], "additionalProperties": false}
            """, a -> worker.search().methodsByName(a.string("method_name")));
        add("search_classes_by_keyword",
            "Search class,method,field,code,comment scopes (comma separated). Code scope progressively decompiles the selected package. Reports scope and partial failures.",
            """
            {"type": "object", "properties": {"search_term": {"type": "string"}, "package": {"type": "string", "default": ""}, "search_in": {"type": "string", "default": "code"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["search_term"], "additionalProperties": false}
            """, a -> worker.search().search(a.search()));
        add("get_all_resource_file_names",
            "List Android resources, including decoded resource table entries.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, a -> worker.resources().allNames(a.page()));
        add("get_resource_file",
            "Read an exact resource name from the resource inventory; binary content is base64.",
            """
            {"type": "object", "properties": {"resource_name": {"type": "string"}}, "required": ["resource_name"], "additionalProperties": false}
            """, a -> worker.resources().resourceFile(a.string("resource_name")));
        add("get_strings",
            "Retrieve strings.xml resources with pagination.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, a -> worker.resources().strings(a.page()));
        add("get_android_manifest",
            "Retrieve all available Android manifests, preserving split provenance.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, a -> worker.manifests().allManifests());
        add("get_manifest_component",
            "List activity,activity-alias,application,service,receiver,provider manifest elements. Export defaults use target SDK and intent filters.",
            """
            {"type": "object", "properties": {"component_type": {"type": "string"}, "only_exported": {"type": "boolean", "default": false}}, "required": ["component_type"], "additionalProperties": false}
            """, a -> worker.manifests().components(a.string("component_type"), a.bool("only_exported", false)));
        add("get_main_application_classes_names",
            "List classes under the manifest application package without decompiling.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, a -> worker.manifests().applicationClassNames());
        add("get_main_application_classes_code",
            "Decompile a page of classes in the manifest package; count=0 explicitly requests all.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, a -> worker.manifests().applicationClassCode(a.page()));
        add("get_main_activity_class",
            "Resolve launcher activities, including activity aliases, from manifests.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, a -> worker.manifests().mainActivities());
        add("get_xrefs_to_class",
            "Find bytecode references to a class, including constructor references.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["class_name"], "additionalProperties": false}
            """, a -> worker.search().classReferences(a.string("class_name"), a.page()));
        add("get_xrefs_to_method",
            "Find method usages and override-related usages. Use a full short signature as method_name to select an overload.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "method_name": {"type": "string"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["class_name", "method_name"], "additionalProperties": false}
            """, a -> worker.search().methodReferences(a.string("class_name"), a.string("method_name"), a.page()));
        add("get_xrefs_to_field",
            "Find bytecode usages of a field. field_name may be its stable short ID.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "field_name": {"type": "string"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["class_name", "field_name"], "additionalProperties": false}
            """, a -> worker.search().fieldReferences(a.string("class_name"), a.string("field_name"), a.page()));
        add("get_cache_stats",
            "Report actual JADX buffer/disk cache occupancy and source-request hits/misses (not JVM memory).",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, a -> worker.decompiler().cacheStats());
        add("clear_cache",
            "Clear generated source and metadata caches, preserving loaded inputs and saved edits.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, a -> worker.decompiler().clearCache());
        add("rename_class",
            "Rename a class using a short Java identifier; persist and verify regenerated source.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "new_name": {"type": "string"}}, "required": ["class_name", "new_name"], "additionalProperties": false}
            """, a -> worker.edits().renameClass(a.renameName(), a.string("class_name")));
        add("rename_method",
            "Rename one unambiguous method. Supply class_name and signature for overloads; original IDs remain valid.",
            """
            {"type": "object", "properties": {"method_name": {"type": "string"}, "new_name": {"type": "string"}, "class_name": {"type": ["string", "null"], "default": null}, "method_signature": {"type": ["string", "null"], "default": null}}, "required": ["method_name", "new_name"], "additionalProperties": false}
            """, a -> worker.edits().renameMethod(a.renameName(), a.method(true)));
        add("rename_field",
            "Rename one field, rejecting ambiguous names; use a short ID when necessary.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "field_name": {"type": "string"}, "new_name": {"type": "string"}}, "required": ["class_name", "field_name", "new_name"], "additionalProperties": false}
            """, a -> worker.edits().renameField(a.renameName(), a.string("class_name"), a.string("field_name")));
        add("rename_package",
            "Rename a package through JADX code data, including its child packages.",
            """
            {"type": "object", "properties": {"old_package_name": {"type": "string"}, "new_package_name": {"type": "string"}}, "required": ["old_package_name", "new_package_name"], "additionalProperties": false}
            """, a -> worker.edits().renamePackage(a.string("old_package_name"), a.string("new_package_name")));
        add("rename_variable",
            "Rename a variable using current source metadata. Use a full short method signature for overloads and reg/ssa to disambiguate variables.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "method_name": {"type": "string"}, "variable_name": {"type": "string"}, "new_name": {"type": "string"}, "reg": {"type": ["string", "null"], "default": null}, "ssa": {"type": ["string", "null"], "default": null}}, "required": ["class_name", "method_name", "variable_name", "new_name"], "additionalProperties": false}
            """, a -> worker.edits().renameVariable(worker.edits().variableTarget(a.variableRename()), a.variableSelection()));
        add("add_comment",
            "Add/replace a declaration or source-line comment; empty text deletes. Styles: LINE,BLOCK,BLOCK_CONDENSED,JAVADOC,JAVADOC_CONDENSED. Line targets persist as instruction identities. Read current numbered source first.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "comment": {"type": "string"}, "method_name": {"type": ["string", "null"], "default": null}, "method_signature": {"type": ["string", "null"], "default": null}, "field_name": {"type": ["string", "null"], "default": null}, "line": {"type": ["integer", "null"], "default": null}, "style": {"type": "string", "default": "LINE"}}, "required": ["class_name", "comment"], "additionalProperties": false}
            """, a -> worker.edits().addComment(a.comment(), a.string("class_name")));
        add("list_comments",
            "List persisted comments with original node and instruction identities.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string", "default": ""}}, "required": [], "additionalProperties": false}
            """, a -> worker.edits().listComments(a.string("class_name", "")));
    }

    private void add(String name, String description, String schemaText, Handler handler) {
        JsonObject schema = JsonParser.parseString(schemaText).getAsJsonObject();
        var tool = McpSchema.Tool.builder(name, McpJsonDefaults.getMapper(), schemaText).description(description).build();
        tools.add(new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            JsonObject arguments = WorkerJson.JSON.toJsonTree(request.arguments() == null ? Map.of() : request.arguments()).getAsJsonObject();
            // The SDK validates against this schema; apply its declared defaults once.
            schema.getAsJsonObject("properties").entrySet().forEach(entry -> {
                JsonObject property = entry.getValue().getAsJsonObject();
                if (!arguments.has(entry.getKey()) && property.has("default"))
                    arguments.add(entry.getKey(), property.get("default").deepCopy());
            });
            return worker.call(name, () -> success(handler.call(new JadxArguments(arguments))), JadxTools::failure);
        }));
    }

    private static McpSchema.CallToolResult failure(Exception ex) {
        ex.printStackTrace(System.err);
        String type = ex instanceof Candidates ? "IllegalArgumentException" : ex.getClass().getSimpleName();
        String message = ex.getMessage();
        if (ex instanceof Candidates candidates) message += WIRE.toJson(candidates.candidates());
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(type + ": " + message).build();
    }

    static McpSchema.CallToolResult success(Object result) {
        JsonObject encoded = encode(result);
        String json = WorkerJson.JSON.toJson(encoded);
        return McpSchema.CallToolResult.builder().addTextContent(displayText(encoded, json))
                .structuredContent(McpJsonDefaults.getMapper(), json).build();
    }

    private static String displayText(JsonObject encoded, String json) {
        JsonObject metadata = encoded.deepCopy();
        Map<String, String> sources = new LinkedHashMap<>();
        extractSources(metadata, "", sources);
        if (sources.isEmpty()) return json;
        StringBuilder text = new StringBuilder("Metadata:\n").append(DISPLAY.toJson(metadata));
        sources.forEach((path, source) -> text.append("\n\nSource")
                .append(sources.size() > 1 ? " (" + path + ")" : "").append(":\n").append(source));
        return text.toString();
    }

    private static void extractSources(JsonElement value, String path, Map<String, String> sources) {
        if (value instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) extractSources(array.get(i), path + "[" + i + "]", sources);
        } else if (value instanceof JsonObject object) {
            var entries = object.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                String key = entry.getKey();
                String location = path.isEmpty() ? key : path + "." + key;
                boolean source = switch (key) {
                    case "code", "smali", "xml", "verified_source" -> true;
                    case "content" -> new JsonPrimitive("text").equals(object.get("encoding"));
                    default -> false;
                };
                if (source && extractText(entry.getValue(), location, sources)) entries.remove();
                // Only descend into result containers; inventory and edit metadata stay intact.
                else if (Set.of("items", "manifests", "components").contains(key))
                    extractSources(entry.getValue(), location, sources);
            }
        }
    }

    private static boolean extractText(JsonElement value, String path, Map<String, String> sources) {
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            sources.put(path, primitive.getAsString());
            return true;
        }
        if (value instanceof JsonArray array && !array.isEmpty()) {
            for (JsonElement item : array)
                if (!(item instanceof JsonPrimitive primitive) || !primitive.isString()) return false;
            for (int i = 0; i < array.size(); i++) sources.put(path + "[" + i + "]", array.get(i).getAsString());
            return true;
        }
        return false;
    }

    private static JsonObject encode(Object value) {
        if (value instanceof Page<?> page) {
            JsonArray items = new JsonArray();
            for (Object item : page.items()) items.add(item instanceof String ? WIRE.toJsonTree(item) : encode(item));
            return WorkerJson.obj("items", items, "total", page.total(), "offset", page.offset(), "count", page.count());
        }
        if (value instanceof Smali result) return WorkerJson.extend(encode(result.identity()), "smali", result.smali());
        if (value instanceof ClassSource result) return WorkerJson.extend(encode(result.identity()),
                "source_class", result.sourceClass(), "code", result.code(), "variables", WIRE.toJsonTree(result.variables()),
                "commentable_lines", result.commentableLines(), "analysis_errors", result.analysisErrors());
        if (value instanceof MethodSource result) return WorkerJson.extend(encode(result.identity()), "code", result.code(), "analysis_errors", result.analysisErrors());
        if (value instanceof SearchResult result) {
            JsonArray failures = new JsonArray();
            for (SearchFailure failure : result.partialFailures()) failures.add(encode(failure));
            return WorkerJson.extend(encode(result.page()), "package", result.packageName(), "search_in", result.searchIn(),
                    "scanned_classes", result.scannedClasses(), "partial_failures", failures, "source_scope", result.sourceScope());
        }
        if (value instanceof SourceErrors result) return WorkerJson.obj("class", result.className(), "errors", result.errors());
        if (value instanceof SourceFailure result) return WorkerJson.obj("class", result.className(), "error", result.error());
        if (value instanceof References result) return WorkerJson.extend(encode(result.page()), "targets", WIRE.toJsonTree(result.targets()), "analysis", result.analysis());
        if (value instanceof ResourceNames result) return WorkerJson.extend(encode(result.page()), "limitations", result.limitations());
        if (value instanceof EditResult<?> result) return WorkerJson.obj("updated", JadxEditStore.encode(result.updated()), "verified_source", WIRE.toJsonTree(result.verifiedSource()));
        if (value instanceof Comments result) return WorkerJson.obj("comments", result.comments().stream().map(JadxEditStore::encode).toList());
        if (value instanceof Readiness result) {
            if (result.status() == null && result.manifestError() == null)
                return WorkerJson.obj("inputs", List.of(), "loaded_inputs", List.of(),
                        "class_count", 0, "package_count", 0, "resource_count", 0, "resources", List.of(),
                        "limitations", List.of(JadxInputs.NO_INPUTS), "pid", ProcessHandle.current().pid(), "load_count", result.loadCount());
            if (result.status() == null) return WorkerJson.obj("class_count", 0, "package_count", 0, "resource_count", 0,
                    "resources", List.of(), "limitations", List.of(result.manifestError()), "manifest_error", result.manifestError(),
                    "pid", ProcessHandle.current().pid(), "load_count", result.loadCount());
            Status status = result.status();
            JsonObject encoded = WorkerJson.obj("inputs", status.inputs().json(), "loaded_inputs", status.loadedInputs(),
                    "class_count", status.classCount(), "package_count", status.packageCount(), "resource_count", status.resourceCount(),
                    "resources", status.resources(), "resource_listing", status.resourceListing(), "limitations", status.limitations(),
                    "load_seconds", status.loadSeconds(), "pid", status.pid(), "load_count", status.loadCount(), "cache", WIRE.toJsonTree(status.cache()));
            if (result.manifestError() != null) {
                encoded.addProperty("manifest_error", result.manifestError());
                encoded.getAsJsonArray("limitations").add(result.manifestError());
            }
            return encoded;
        }
        return WIRE.toJsonTree(value).getAsJsonObject();
    }
}
