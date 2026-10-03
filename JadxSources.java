import com.google.gson.*;
import java.util.*;
import jadx.api.*;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.annotations.*;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.*;

/** Bytecode inventories, on-demand source, source metadata, and usage analysis. */
final class JadxSources {
    private final JadxSession session;

    JadxSources(JadxSession session) { this.session = session; }

    ClassNode cls(String name) {
        ClassNode node = session.classes.get(name);
        return node != null ? node : one(session.classes.values().stream().filter(c -> c.getFullName().equals(name)).toList(), "class");
    }

    static JsonObject describe(ICodeNode node) {
        if (node instanceof ClassNode cls)
            return WorkerJson.obj("class_name", cls.getFullName(), "original_id", cls.getRawName(), "kind", "CLASS");
        String id = node instanceof MethodNode m ? m.getMethodInfo().getShortId() : ((FieldNode) node).getFieldInfo().getShortId();
        String alias = node instanceof MethodNode m ? m.getAlias() : ((FieldNode) node).getAlias();
        return WorkerJson.obj("class_name", node.getDeclaringClass().getFullName(), "name", alias,
                "original_id", node.getDeclaringClass().getRawName() + "->" + id, "signature", id, "kind", node.getAnnType().name());
    }

    static <T extends ICodeNode> T one(List<T> nodes, String kind) {
        if (nodes.size() != 1) throw new IllegalArgumentException("Expected one " + kind + ", found " + nodes.size()
                + ". Candidates: " + WorkerJson.JSON.toJson(nodes.stream().map(JadxSources::describe).toList()));
        return nodes.getFirst();
    }

    List<MethodNode> methods(String className, String methodName, String signature) {
        Collection<ClassNode> scope = className == null || className.isEmpty() ? session.classes.values() : List.of(cls(className));
        List<MethodNode> matches = new ArrayList<>();
        for (ClassNode cls : scope) for (MethodNode method : cls.getMethods()) {
            String id = method.getMethodInfo().getShortId();
            if (!Arrays.asList(method.getName(), method.getAlias(), id, cls.getRawName() + "->" + id).contains(methodName)) continue;
            if (signature != null && !signature.isEmpty() && !signature.equals(id) && !signature.equals(id.substring(id.indexOf('(')))) continue;
            matches.add(method);
        }
        return matches;
    }

    FieldNode field(String className, String name) {
        return one(cls(className).getFields().stream().filter(f -> Arrays.asList(f.getAlias(), f.getName(),
                f.getFieldInfo().getShortId(), f.getParentClass().getRawName() + "->" + f.getFieldInfo().getShortId()).contains(name)).toList(), "field");
    }

    ICodeInfo source(ClassNode cls) {
        ClassNode top = cls.getTopParentClass();
        if (session.cache.contains(top.getRawName())) session.hits++; else session.misses++;
        ICodeInfo code = top.decompile();
        if (WorkerJson.strip(code.getCodeStr()).isEmpty() || !code.hasMetadata()) throw new IllegalStateException("No source available for " + cls.getRawName());
        return code;
    }

    List<String> sourceErrors(ClassNode cls) {
        List<String> errors = new ArrayList<>();
        Deque<ICodeNode> pending = new ArrayDeque<>(List.of(cls.getTopParentClass()));
        while (!pending.isEmpty()) {
            ICodeNode node = pending.removeLast();
            node.getAll(AType.JADX_ERROR).forEach(e -> errors.add(e.toString()));
            if (node instanceof ClassNode c) { pending.addAll(c.getInnerClasses()); pending.addAll(c.getMethods()); }
        }
        return errors;
    }

    JsonObject classSource(String name, boolean numbered) {
        ClassNode cls = cls(name);
        ICodeInfo code = source(cls);
        String text = code.getCodeStr();
        if (numbered) {
            List<String> numberedLines = new ArrayList<>();
            for (String line : WorkerJson.lines(text)) numberedLines.add((numberedLines.size() + 1) + ": " + line);
            text = String.join("\n", numberedLines);
        }
        return WorkerJson.extend(describe(cls), "source_class", cls.getTopParentClass().getRawName(), "code", text,
                "variables", variables(code), "commentable_lines", lineTargets(code).keySet(), "analysis_errors", sourceErrors(cls));
    }

    JsonObject search(JsonObject a, int offset, int count) {
        Set<String> scopes = new TreeSet<>();
        for (String scope : WorkerJson.string(a, "search_in", "code").split(",", -1)) scopes.add(WorkerJson.strip(scope));
        if (!Set.of("class", "method", "field", "code", "comment").containsAll(scopes))
            throw new IllegalArgumentException("search_in must contain class,method,field,code,comment");
        WorkerJson.page(List.of(), offset, count);
        String term = WorkerJson.fold(WorkerJson.string(a, "search_term")), pkg = WorkerJson.string(a, "package", "");
        List<ClassNode> selected = session.classes.values().stream().filter(c -> pkg.isEmpty() || inPackage(c.getPackage(), pkg)).toList();
        List<JsonObject> matches = new ArrayList<>(), failures = new ArrayList<>();
        for (ClassNode cls : selected) {
            List<String> texts = new ArrayList<>();
            if (scopes.contains("class")) texts.addAll(List.of(cls.getFullName(), cls.getRawName()));
            if (scopes.contains("method")) cls.getMethods().forEach(m -> texts.add(m.getAlias()));
            if (scopes.contains("field")) cls.getFields().forEach(f -> texts.add(f.getAlias()));
            if (scopes.contains("comment")) for (JsonElement entry : session.edits.getAsJsonArray("comments")) {
                JsonObject e = entry.getAsJsonObject();
                if (WorkerJson.string(e.getAsJsonObject("node"), "class").equals(cls.getRawName())) texts.add(WorkerJson.string(e, "comment"));
            }
            if (scopes.contains("code")) try {
                texts.add(source(cls).getCodeStr());
                List<String> errors = sourceErrors(cls);
                if (!errors.isEmpty()) failures.add(WorkerJson.obj("class", cls.getRawName(), "errors", errors));
            } catch (Exception ex) { failures.add(WorkerJson.obj("class", cls.getRawName(), "error", ex.getMessage())); }
            if (texts.stream().anyMatch(t -> WorkerJson.fold(t).contains(term))) matches.add(describe(cls));
        }
        return WorkerJson.extend(WorkerJson.page(matches, offset, count), "package", pkg, "search_in", scopes, "scanned_classes", selected.size(),
                "partial_failures", failures, "source_scope", scopes.contains("code") ? "Enclosing class source includes inner classes" : null);
    }

    static boolean inPackage(String name, String pkg) { return name.equals(pkg) || name.startsWith(pkg + "."); }

    JsonObject xrefs(List<? extends ICodeNode> targets, int offset, int count) {
        if (session.loadErrors != 0 || !session.loadFailures.isEmpty())
            throw new IllegalStateException("Usage analysis reported errors; reference results are unavailable. See worker.log");
        Map<String, JsonObject> uses = new TreeMap<>();
        List<ICodeNode> expanded = new ArrayList<>(targets);
        for (ICodeNode node : targets) {
            if (node instanceof ClassNode cls) {
                expanded.addAll(cls.getMethods().stream().filter(m -> m.getMethodInfo().isConstructor()).toList());
                for (MethodNode user : cls.getUseInMth()) { JsonObject row = describe(user); uses.put(WorkerJson.string(row, "original_id"), row); }
            }
            if (node instanceof MethodNode method) {
                var related = method.get(AType.METHOD_OVERRIDE);
                if (related != null) expanded.addAll(related.getRelatedMthNodes());
            }
        }
        for (ICodeNode node : expanded) for (ICodeNode user : node.getUseIn()) {
            JsonObject row = describe(user);
            uses.put(WorkerJson.string(row, "original_id"), row);
        }
        return WorkerJson.extend(WorkerJson.page(new ArrayList<>(uses.values()), offset, count),
                "targets", expanded.stream().map(JadxSources::describe).toList(), "analysis", "JADX bytecode usage data");
    }

    static JsonObject nodeRef(ICodeNode node) {
        if (node instanceof ClassNode cls) return WorkerJson.obj("type", "CLASS", "class", cls.getRawName(), "id", null);
        String id = node instanceof MethodNode m ? m.getMethodInfo().getShortId() : ((FieldNode) node).getFieldInfo().getShortId();
        return WorkerJson.obj("type", node.getAnnType().name(), "class", node.getDeclaringClass().getRawName(), "id", id);
    }

    static List<JsonObject> variables(ICodeInfo code) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (ICodeAnnotation value : code.getCodeMetadata().getAsMap().values()) {
            ICodeAnnotation ann = value instanceof NodeDeclareRef declaration ? declaration.getNode() : value;
            if (ann instanceof VarNode variable) {
                String method = WorkerJson.string(describe(variable.getMth()), "original_id");
                result.put(method + ":" + variable.getReg() + ":" + variable.getSsa(), WorkerJson.obj("method_id", method,
                        "name", variable.getName(), "reg", variable.getReg(), "ssa", variable.getSsa()));
            }
        }
        return new ArrayList<>(result.values());
    }

    static SortedMap<Integer, JsonObject> lineTargets(ICodeInfo code) {
        String text = code.getCodeStr();
        List<Integer> starts = new ArrayList<>();
        if (!text.isEmpty()) starts.add(0);
        var matcher = WorkerJson.NEWLINE.matcher(text);
        while (matcher.find()) if (matcher.end() < text.length()) starts.add(matcher.end());
        SortedMap<Integer, JsonObject> targets = new TreeMap<>();
        var metadata = code.getCodeMetadata();
        for (var entry : metadata.getAsMap().entrySet()) {
            int found = Collections.binarySearch(starts, entry.getKey());
            int line = found >= 0 ? found + 1 : -found - 1;
            if (entry.getValue() instanceof NodeDeclareRef declaration && declaration.getNode() instanceof ICodeNode node)
                targets.put(line, WorkerJson.obj("node", nodeRef(node)));
            else if (entry.getValue() instanceof InsnCodeOffset offset && metadata.getNodeAt(entry.getKey()) instanceof MethodNode method)
                targets.putIfAbsent(line, WorkerJson.obj("node", nodeRef(method), "code", WorkerJson.obj("type", "INSN", "index", offset.getOffset())));
        }
        return targets;
    }
}
