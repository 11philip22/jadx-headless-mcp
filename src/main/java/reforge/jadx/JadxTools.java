package reforge.jadx;

import com.google.gson.*;
import java.util.*;
import jadx.core.dex.nodes.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/** The single registry of MCP tool descriptions, schemas, and analysis handlers. */
final class JadxTools {
    @FunctionalInterface interface Handler {
        JsonObject call(JsonObject arguments, int offset, int count) throws Exception;
    }
    private final JadxWorker worker;
    final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

    JadxTools(JadxWorker worker) {
        this.worker = worker;
        add("status", "Load registered inputs if needed and check Android readiness, including the base manifest.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            (a, offset, count) -> worker.readiness(false));
        add("reload_inputs", "Reload the workspace input inventory and persisted edits in this process; return updated Android readiness.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            (a, offset, count) -> worker.readiness(true));
        add("get_all_classes",
            "List bytecode classes, including inner classes, without decompiling; count=0 returns all.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> WorkerJson.page(worker.session.classes.values().stream().map(JadxSources::describe).toList(), offset, count));
        add("get_package_tree",
            "List packages and their class counts without decompiling.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> {
                Map<String, Integer> counts = new LinkedHashMap<>();
                worker.session.classes.values().forEach(c -> counts.merge(c.getPackage(), 1, Integer::sum));
                return WorkerJson.obj("total_classes", worker.session.classes.size(), "total_packages", counts.size(), "packages",
                        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                .map(e -> WorkerJson.obj("name", e.getKey(), "class_count", e.getValue())).toList());
            });
        add("get_class_source",
            "Decompile one class (inner classes return their enclosing source). Includes variable IDs and commentable lines; reread before line edits.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "with_line_numbers": {"type": "boolean", "default": false}}, "required": ["class_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.sources.classSource(WorkerJson.string(a, "class_name"), WorkerJson.bool(a, "with_line_numbers", false)));
        add("get_methods_of_class",
            "List methods with stable original IDs and overload signatures, without decompiling.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}}, "required": ["class_name"], "additionalProperties": false}
            """, (a, offset, count) -> WorkerJson.obj("methods", worker.sources.cls(WorkerJson.string(a, "class_name")).getMethods().stream().map(JadxSources::describe).toList()));
        add("get_fields_of_class",
            "List fields with stable original IDs and types, without decompiling.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}}, "required": ["class_name"], "additionalProperties": false}
            """, (a, offset, count) -> WorkerJson.obj("fields", worker.sources.cls(WorkerJson.string(a, "class_name")).getFields().stream().map(JadxSources::describe).toList()));
        add("get_smali_of_class",
            "Retrieve a class's bytecode disassembly.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}}, "required": ["class_name"], "additionalProperties": false}
            """, (a, offset, count) -> {
                ClassNode cls = worker.sources.cls(WorkerJson.string(a, "class_name"));
                String smali = cls.getDisassembledCode();
                if (smali.isEmpty()) throw new IllegalStateException("No bytecode disassembly available");
                return WorkerJson.extend(JadxSources.describe(cls), "smali", smali);
            });
        add("get_method_by_name",
            "Retrieve method source; pass a short ID or method_signature such as (I)V for overloads.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "method_name": {"type": "string"}, "method_signature": {"type": ["string", "null"], "default": null}}, "required": ["class_name", "method_name"], "additionalProperties": false}
            """, (a, offset, count) -> {
                MethodNode method = JadxSources.one(worker.sources.methods(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "method_name"), WorkerJson.string(a, "method_signature", null)), "method");
                worker.sources.source(method.getParentClass());
                String text = method.getCodeStr();
                if (WorkerJson.strip(text).isEmpty()) throw new IllegalStateException("Method has no rendered source (possibly inlined or suppressed); inspect class or smali");
                return WorkerJson.extend(JadxSources.describe(method), "code", text, "analysis_errors", worker.sources.sourceErrors(method.getParentClass()));
            });
        add("search_method_by_name",
            "Search method names by substring across bytecode metadata.",
            """
            {"type": "object", "properties": {"method_name": {"type": "string"}}, "required": ["method_name"], "additionalProperties": false}
            """, (a, offset, count) -> {
                String term = WorkerJson.fold(WorkerJson.string(a, "method_name"));
                return WorkerJson.obj("methods", worker.session.classes.values().stream().flatMap(c -> c.getMethods().stream())
                        .filter(m -> WorkerJson.fold(m.getAlias()).contains(term) || WorkerJson.fold(m.getName()).contains(term)).map(JadxSources::describe).toList());
            });
        add("search_classes_by_keyword",
            "Search class,method,field,code,comment scopes (comma separated). Code scope progressively decompiles the selected package. Reports scope and partial failures.",
            """
            {"type": "object", "properties": {"search_term": {"type": "string"}, "package": {"type": "string", "default": ""}, "search_in": {"type": "string", "default": "code"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["search_term"], "additionalProperties": false}
            """, (a, offset, count) -> worker.sources.search(a, offset, count));
        add("get_all_resource_file_names",
            "List Android resources, including decoded resource table entries.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> {
                worker.resourceTools.loadTables();
                return WorkerJson.extend(WorkerJson.page(worker.session.resources.keySet().stream().sorted().toList(), offset, count), "limitations", worker.session.resourceErrors);
            });
        add("get_resource_file",
            "Read an exact resource name from the resource inventory; binary content is base64.",
            """
            {"type": "object", "properties": {"resource_name": {"type": "string"}}, "required": ["resource_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.resourceTools.resourceFile(WorkerJson.string(a, "resource_name")));
        add("get_strings",
            "Retrieve strings.xml resources with pagination.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> {
                worker.resourceTools.loadTables();
                List<String> names = worker.session.resources.keySet().stream().filter(n -> n.endsWith("/strings.xml")).toList();
                if (names.isEmpty()) throw new IllegalArgumentException("No strings.xml resources available; " + String.join("; ", worker.session.resourceErrors));
                JsonObject result = WorkerJson.page(names, offset, count);
                JsonArray items = new JsonArray();
                for (JsonElement name : result.getAsJsonArray("items")) items.add(worker.resourceTools.resourceFile(name.getAsString()));
                return WorkerJson.extend(result, "items", items);
            });
        add("get_android_manifest",
            "Retrieve all available Android manifests, preserving split provenance.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> {
                JsonArray manifests = new JsonArray();
                for (String name : worker.resourceTools.manifestNames()) manifests.add(worker.resourceTools.resourceFile(name));
                return WorkerJson.obj("manifests", manifests);
            });
        add("get_manifest_component",
            "List activity,activity-alias,application,service,receiver,provider manifest elements. Export defaults use target SDK and intent filters.",
            """
            {"type": "object", "properties": {"component_type": {"type": "string"}, "only_exported": {"type": "boolean", "default": false}}, "required": ["component_type"], "additionalProperties": false}
            """, (a, offset, count) -> worker.resourceTools.components(WorkerJson.string(a, "component_type"), WorkerJson.bool(a, "only_exported", false)));
        add("get_main_application_classes_names",
            "List classes under the manifest application package without decompiling.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> WorkerJson.obj("classes", worker.resourceTools.applicationClasses().stream().map(JadxSources::describe).toList()));
        add("get_main_application_classes_code",
            "Decompile a page of classes in the manifest package; count=0 explicitly requests all.",
            """
            {"type": "object", "properties": {"offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 0}}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> {
                JsonObject result = WorkerJson.page(worker.resourceTools.applicationClasses().stream().map(ClassNode::getRawName).toList(), offset, count);
                JsonArray items = new JsonArray();
                for (JsonElement name : result.getAsJsonArray("items")) items.add(worker.sources.classSource(name.getAsString(), false));
                return WorkerJson.extend(result, "items", items);
            });
        add("get_main_activity_class",
            "Resolve launcher activities, including activity aliases, from manifests.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> worker.resourceTools.mainActivities());
        add("get_xrefs_to_class",
            "Find bytecode references to a class, including constructor references.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["class_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.sources.xrefs(List.of(worker.sources.cls(WorkerJson.string(a, "class_name"))), offset, count));
        add("get_xrefs_to_method",
            "Find method usages and override-related usages. Use a full short signature as method_name to select an overload.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "method_name": {"type": "string"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["class_name", "method_name"], "additionalProperties": false}
            """, (a, offset, count) -> {
                List<MethodNode> methods = worker.sources.methods(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "method_name"), null);
                if (methods.isEmpty()) throw new IllegalArgumentException("Method unavailable");
                return worker.sources.xrefs(methods, offset, count);
            });
        add("get_xrefs_to_field",
            "Find bytecode usages of a field. field_name may be its stable short ID.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "field_name": {"type": "string"}, "offset": {"type": "integer", "default": 0}, "count": {"type": "integer", "default": 20}}, "required": ["class_name", "field_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.sources.xrefs(List.of(worker.sources.field(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "field_name"))), offset, count));
        add("get_cache_stats",
            "Report actual JADX buffer/disk cache occupancy and source-request hits/misses (not JVM memory).",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> worker.session.cacheStats());
        add("clear_cache",
            "Clear generated source and metadata caches, preserving loaded inputs and saved edits.",
            """
            {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> { worker.session.resetCache(); worker.session.hits = worker.session.misses = 0; return worker.session.cacheStats(); });
        add("rename_class",
            "Rename a class using a short Java identifier; persist and verify regenerated source.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "new_name": {"type": "string"}}, "required": ["class_name", "new_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.editTools.renameNode(a, "CLASS"));
        add("rename_method",
            "Rename one unambiguous method. Supply class_name and signature for overloads; original IDs remain valid.",
            """
            {"type": "object", "properties": {"method_name": {"type": "string"}, "new_name": {"type": "string"}, "class_name": {"type": ["string", "null"], "default": null}, "method_signature": {"type": ["string", "null"], "default": null}}, "required": ["method_name", "new_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.editTools.renameNode(a, "METHOD"));
        add("rename_field",
            "Rename one field, rejecting ambiguous names; use a short ID when necessary.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "field_name": {"type": "string"}, "new_name": {"type": "string"}}, "required": ["class_name", "field_name", "new_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.editTools.renameNode(a, "FIELD"));
        add("rename_package",
            "Rename a package through JADX code data, including its child packages.",
            """
            {"type": "object", "properties": {"old_package_name": {"type": "string"}, "new_package_name": {"type": "string"}}, "required": ["old_package_name", "new_package_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.editTools.renamePackage(WorkerJson.string(a, "old_package_name"), WorkerJson.string(a, "new_package_name")));
        add("rename_variable",
            "Rename a variable using current source metadata. Use a full short method signature for overloads and reg/ssa to disambiguate variables.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "method_name": {"type": "string"}, "variable_name": {"type": "string"}, "new_name": {"type": "string"}, "reg": {"type": ["string", "null"], "default": null}, "ssa": {"type": ["string", "null"], "default": null}}, "required": ["class_name", "method_name", "variable_name", "new_name"], "additionalProperties": false}
            """, (a, offset, count) -> worker.editTools.renameVariable(a));
        add("add_comment",
            "Add/replace a declaration or source-line comment; empty text deletes. Styles: LINE,BLOCK,BLOCK_CONDENSED,JAVADOC,JAVADOC_CONDENSED. Line targets persist as instruction identities. Read current numbered source first.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string"}, "comment": {"type": "string"}, "method_name": {"type": ["string", "null"], "default": null}, "method_signature": {"type": ["string", "null"], "default": null}, "field_name": {"type": ["string", "null"], "default": null}, "line": {"type": ["integer", "null"], "default": null}, "style": {"type": "string", "default": "LINE"}}, "required": ["class_name", "comment"], "additionalProperties": false}
            """, (a, offset, count) -> worker.editTools.addComment(a));
        add("list_comments",
            "List persisted comments with original node and instruction identities.",
            """
            {"type": "object", "properties": {"class_name": {"type": "string", "default": ""}}, "required": [], "additionalProperties": false}
            """, (a, offset, count) -> {
                String name = WorkerJson.string(a, "class_name", ""), original = name.isEmpty() ? null : worker.sources.cls(name).getRawName();
                return WorkerJson.obj("comments", worker.session.edits.getAsJsonArray("comments").asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(e -> original == null || WorkerJson.string(e.getAsJsonObject("node"), "class").equals(original)).toList());
            });
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
            return worker.call(name, arguments, handler);
        }));
    }
}
