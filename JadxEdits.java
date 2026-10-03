import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import jadx.api.*;
import jadx.api.data.*;
import jadx.api.data.impl.*;
import jadx.api.metadata.annotations.*;
import jadx.core.deobf.NameMapper;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.*;

/** Verified source edits; persistence and rollback form one transaction. */
final class JadxEdits {
    private final JadxSession session;
    private final JadxSources sources;

    JadxEdits(JadxSession session, JadxSources sources) {
        this.session = session;
        this.sources = sources;
    }

    static JadxNodeRef javaRef(JsonObject ref) {
        return new JadxNodeRef(IJavaNodeRef.RefType.valueOf(WorkerJson.string(ref, "type")), WorkerJson.string(ref, "class"), WorkerJson.string(ref, "id", null));
    }

    static JadxCodeRef codeRef(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject ref = value.getAsJsonObject();
        return new JadxCodeRef(CodeRefType.valueOf(WorkerJson.string(ref, "type")), WorkerJson.integer(ref, "index", 0));
    }

    static JadxCodeData codeData(JsonObject edits) {
        JadxCodeData data = new JadxCodeData();
        List<ICodeRename> renames = new ArrayList<>();
        for (JsonElement value : edits.getAsJsonArray("renames")) {
            JsonObject e = value.getAsJsonObject();
            renames.add(new JadxCodeRename(javaRef(e.getAsJsonObject("node")), codeRef(e.get("code")), WorkerJson.string(e, "new_name")));
        }
        List<ICodeComment> comments = new ArrayList<>();
        for (JsonElement value : edits.getAsJsonArray("comments")) {
            JsonObject e = value.getAsJsonObject();
            comments.add(new JadxCodeComment(javaRef(e.getAsJsonObject("node")), codeRef(e.get("code")), WorkerJson.string(e, "comment"), CommentStyle.valueOf(WorkerJson.string(e, "style"))));
        }
        data.setRenames(renames);
        data.setComments(comments);
        return data;
    }

    void applyEdits(JsonObject updated, JsonObject previous) throws IOException {
        session.resetCache();
        JsonArray entries = new JsonArray();
        entries.addAll(previous.getAsJsonArray("renames"));
        entries.addAll(updated.getAsJsonArray("renames"));
        for (JsonElement entry : entries) {
            JsonObject e = entry.getAsJsonObject(), ref = e.getAsJsonObject("node");
            String kind = WorkerJson.string(ref, "type");
            if (kind.equals("PKG")) {
                PackageNode node = session.root.resolvePackage(WorkerJson.string(ref, "class"));
                if (node != null) node.removeAlias();
            } else if (e.get("code") == null || e.get("code").isJsonNull()) {
                ClassNode cls = sources.cls(WorkerJson.string(ref, "class"));
                switch (kind) {
                    case "CLASS" -> cls.removeAlias();
                    case "METHOD" -> {
                        MethodNode method = cls.searchMethodByShortId(WorkerJson.string(ref, "id"));
                        var related = method.get(AType.METHOD_OVERRIDE);
                        Collection<MethodNode> members = related == null ? List.of(method) : related.getRelatedMthNodes();
                        members.forEach(m -> m.getMethodInfo().removeAlias());
                    }
                    case "FIELD" -> cls.searchFieldByShortId(WorkerJson.string(ref, "id")).getFieldInfo().removeAlias();
                    default -> throw new IllegalArgumentException("Unknown edit node: " + kind);
                }
            }
        }
        session.args.setCodeData(codeData(updated));
        session.decompiler.reloadCodeData();
    }

    static boolean sameTarget(JsonObject a, JsonObject b) {
        return Objects.equals(a.get("node"), b.get("node")) && Objects.equals(a.get("code"), b.get("code"));
    }

    JsonObject edit(String category, JsonObject entry, Supplier<Object> verify) throws IOException {
        JsonObject previous = session.edits, updated = session.edits.deepCopy();
        JsonArray entries = new JsonArray();
        for (JsonElement e : updated.getAsJsonArray(category)) if (!sameTarget(e.getAsJsonObject(), entry)) entries.add(e);
        String text = WorkerJson.string(entry, category.equals("renames") ? "new_name" : "comment");
        if (!text.isEmpty()) entries.add(entry);
        updated.add(category, entries);
        Path temporary = session.directory.resolve("edits.tmp");
        Object evidence;
        try {
            applyEdits(updated, previous);
            evidence = verify.get();
            Files.writeString(temporary, WorkerJson.JSON.toJson(updated) + "\n", StandardCharsets.UTF_8);
            // A failed atomic replace is an edit failure; never risk truncating the persisted file.
            Files.move(temporary, session.directory.resolve("edits.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            try { applyEdits(previous, updated); }
            catch (Exception rollback) { ex.addSuppressed(rollback); }
            throw ex;
        } finally { Files.deleteIfExists(temporary); }
        session.edits = updated;
        return WorkerJson.obj("updated", entry, "verified_source", evidence);
    }

    static void validName(String name, boolean pkg) {
        for (String part : pkg ? name.split("\\.", -1) : new String[]{name})
            if (!NameMapper.isValidIdentifier(part)) throw new IllegalArgumentException("new_name must be a valid Java identifier (or dotted package)");
    }

    String verifyName(JsonObject ref, String name) {
        ICodeInfo code = sources.source(sources.cls(WorkerJson.string(ref, "class")));
        for (var entry : code.getCodeMetadata().getAsMap().entrySet())
            if (entry.getValue() instanceof NodeDeclareRef declaration && declaration.getNode() instanceof ICodeNode node && JadxSources.nodeRef(node).equals(ref)) {
                String alias = node instanceof ClassNode c ? c.getShortName() : node instanceof MethodNode m ? m.getAlias() : ((FieldNode) node).getAlias();
                if (alias.equals(name)) {
                    String text = code.getCodeStr();
                    int line = (int) text.substring(0, entry.getKey()).chars().filter(c -> c == '\n').count();
                    return WorkerJson.lines(text).get(line);
                }
            }
        throw new IllegalStateException("Rename did not render on the target declaration; rolled back");
    }

    JsonObject renameNode(JsonObject a, String kind) throws IOException {
        String name = WorkerJson.string(a, "new_name");
        validName(name, false);
        ICodeNode node = switch (kind) {
            case "CLASS" -> sources.cls(WorkerJson.string(a, "class_name"));
            case "FIELD" -> sources.field(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "field_name"));
            default -> JadxSources.one(sources.methods(WorkerJson.string(a, "class_name", null), WorkerJson.string(a, "method_name"), WorkerJson.string(a, "method_signature", null)), "method");
        };
        if (node instanceof MethodNode m && (m.getMethodInfo().isConstructor() || m.getMethodInfo().isClassInit()))
            throw new IllegalArgumentException("Constructors and initializers cannot be renamed; rename the class");
        JsonObject ref = JadxSources.nodeRef(node);
        return edit("renames", WorkerJson.obj("node", ref, "new_name", name), () -> verifyName(ref, name));
    }

    JsonObject renamePackage(String oldName, String newName) throws IOException {
        validName(newName, true);
        List<PackageNode> packages = session.root.getPackages().stream()
                .filter(p -> p.getFullName().equals(oldName) || p.getAliasPkgInfo().getFullName().equals(oldName)).toList();
        if (packages.size() != 1) throw new IllegalArgumentException("Package unavailable or ambiguous: " + oldName);
        String original = packages.getFirst().getFullName();
        List<String> names = session.classes.values().stream().filter(c -> JadxSources.inPackage(c.getClassInfo().getPackage(), original)).map(ClassNode::getRawName).toList();
        if (names.isEmpty()) throw new IllegalArgumentException("Package contains no source classes to verify");
        return edit("renames", WorkerJson.obj("node", WorkerJson.obj("type", "PKG", "class", original, "id", ""), "new_name", "." + newName), () -> {
            for (String name : names) if (!JadxSources.inPackage(sources.cls(name).getPackage(), newName))
                throw new IllegalStateException("Package rename did not apply to all classes");
            ClassNode cls = sources.cls(names.getFirst());
            String code = sources.source(cls).getCodeStr();
            if (!code.contains("package " + cls.getPackage() + ";")) throw new IllegalStateException("Package rename did not render");
            return List.of(WorkerJson.lines(code).getFirst());
        });
    }

    JsonObject renameVariable(JsonObject a) throws IOException {
        String name = WorkerJson.string(a, "new_name"), variableName = WorkerJson.string(a, "variable_name");
        validName(name, false);
        MethodNode method = JadxSources.one(sources.methods(WorkerJson.string(a, "class_name"), WorkerJson.string(a, "method_name"), null), "method");
        JsonObject ref = JadxSources.nodeRef(method);
        String methodId = WorkerJson.string(JadxSources.describe(method), "original_id"), reg = WorkerJson.string(a, "reg", null), ssa = WorkerJson.string(a, "ssa", null);
        List<JsonObject> variables = JadxSources.variables(sources.source(method.getParentClass())).stream().filter(v -> WorkerJson.string(v, "method_id").equals(methodId)).toList();
        List<JsonObject> matches = variables.stream().filter(v -> WorkerJson.string(v, "name").equals(variableName)
                && (reg == null || WorkerJson.integer(v, "reg", 0) == Integer.parseInt(reg))
                && (ssa == null || WorkerJson.integer(v, "ssa", 0) == Integer.parseInt(ssa))).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Variable unavailable or ambiguous. Candidates: " + WorkerJson.JSON.toJson(variables));
        JsonObject variable = matches.getFirst();
        int index = WorkerJson.integer(variable, "reg", 0) << 16 | WorkerJson.integer(variable, "ssa", 0);
        return edit("renames", WorkerJson.obj("node", ref, "code", WorkerJson.obj("type", "VAR", "index", index), "new_name", name), () -> {
            boolean found = JadxSources.variables(sources.source(sources.cls(WorkerJson.string(ref, "class")))).stream().anyMatch(v -> WorkerJson.string(v, "method_id").equals(methodId)
                    && v.get("reg").equals(variable.get("reg")) && v.get("ssa").equals(variable.get("ssa")) && WorkerJson.string(v, "name").equals(name));
            if (!found) throw new IllegalStateException("Variable rename did not render");
            return WorkerJson.extend(variable.deepCopy(), "name", name);
        });
    }

    List<String> commentRegions(ICodeInfo code, JsonObject target) {
        List<String> text = WorkerJson.lines(code.getCodeStr());
        SortedMap<Integer, JsonObject> targets = JadxSources.lineTargets(code);
        List<Integer> positions = new ArrayList<>(targets.keySet());
        List<String> regions = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            int line = positions.get(i);
            if (!targets.get(line).equals(target)) continue;
            int start = target.has("code") ? line - 1 : i == 0 ? 0 : positions.get(i - 1);
            int end = target.has("code") ? i + 1 < positions.size() ? positions.get(i + 1) - 1 : text.size() : line;
            regions.add(String.join("\n", text.subList(start, end).stream().map(WorkerJson::strip).toList()));
        }
        if (regions.isEmpty()) throw new IllegalStateException("Comment target has no regenerated source metadata; rolled back");
        return regions;
    }

    static String renderedComment(String comment, String name) {
        CommentStyle style = CommentStyle.valueOf(name);
        String rendered = style.getStart() + String.join("\n" + style.getOnNewLine(), comment.split("\\r\\n|[\\n\\r\\u000b\\f\\u0085\\u2028\\u2029]", -1)) + style.getEnd();
        return String.join("\n", WorkerJson.lines(rendered).stream().map(WorkerJson::strip).toList());
    }

    JsonObject addComment(JsonObject a) throws IOException {
        String comment = WorkerJson.string(a, "comment"), style = WorkerJson.string(a, "style", "LINE");
        if (!Set.of("LINE", "BLOCK", "BLOCK_CONDENSED", "JAVADOC", "JAVADOC_CONDENSED").contains(style))
            throw new IllegalArgumentException("Invalid comment style");
        String method = WorkerJson.string(a, "method_name", null), signature = WorkerJson.string(a, "method_signature", null), field = WorkerJson.string(a, "field_name", null);
        Integer line = a.has("line") && !a.get("line").isJsonNull() ? WorkerJson.integer(a, "line", 0) : null;
        if ((method != null ? 1 : 0) + (field != null ? 1 : 0) + (line != null ? 1 : 0) > 1)
            throw new IllegalArgumentException("Specify only one of method_name, field_name, or line");
        if (signature != null && !signature.isEmpty() && method == null) throw new IllegalArgumentException("method_signature requires method_name");
        if (comment.contains("*/")) throw new IllegalArgumentException("Comment must not contain a block-comment terminator");
        if (!comment.isEmpty() && WorkerJson.strip(comment).isEmpty()) throw new IllegalArgumentException("Use an empty string to delete a comment");
        ClassNode cls = sources.cls(WorkerJson.string(a, "class_name"));
        JsonObject target;
        if (line != null) {
            var targets = JadxSources.lineTargets(sources.source(cls));
            target = targets.get(line);
            if (target == null) throw new IllegalArgumentException("Line has no commentable metadata. Nearest lines: "
                    + targets.keySet().stream().sorted(Comparator.comparingLong(n -> Math.abs((long) n - line))).limit(8).toList());
        } else {
            ICodeNode node = method != null && !method.isEmpty() ? JadxSources.one(sources.methods(cls.getRawName(), method, signature), "method")
                    : field != null && !field.isEmpty() ? sources.field(cls.getRawName(), field) : cls;
            target = WorkerJson.obj("node", JadxSources.nodeRef(node));
        }
        JsonObject previous = session.edits.getAsJsonArray("comments").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(e -> sameTarget(e, target)).findFirst().orElse(null);
        return edit("comments", WorkerJson.extend(target.deepCopy(), "comment", comment, "style", style), () -> {
            ICodeInfo code = sources.source(sources.cls(WorkerJson.string(target.getAsJsonObject("node"), "class")));
            List<String> regions = commentRegions(code, target);
            if (!comment.isEmpty()) {
                String expected = renderedComment(comment, style);
                if (regions.stream().noneMatch(r -> r.contains(expected))) throw new IllegalStateException("Comment did not render; rolled back");
                return expected;
            }
            if (previous != null && regions.stream().anyMatch(r -> r.contains(renderedComment(WorkerJson.string(previous, "comment"), WorkerJson.string(previous, "style")))))
                throw new IllegalStateException("Removed comment still renders; rolled back");
            return "Comment removed from regenerated source";
        });
    }
}
