package reforge.jadx;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import jadx.api.ICodeInfo;
import jadx.api.data.CommentStyle;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.core.dex.nodes.*;
import static reforge.jadx.JadxModels.*;

/** Stable-target edits, regeneration verification, and rollback form one transaction. */
final class EditService implements JadxServices.Edits {
    private final JadxSession session;
    private final JadxNodes nodes;
    private final JadxServices.Decompiler decompiler;
    private final JadxEditStore store;

    EditService(JadxSession session, JadxNodes nodes, JadxServices.Decompiler decompiler, JadxEditStore store) {
        this.session = session;
        this.nodes = nodes;
        this.decompiler = decompiler;
        this.store = store;
    }

    private void applyEdits(JadxEditStore.Snapshot updated, JadxEditStore.Snapshot previous) throws IOException {
        decompiler.invalidateCache();
        for (JadxEditStore.SavedEntry entry : previous.renames()) nodes.removeAlias(entry.target());
        for (JadxEditStore.SavedEntry entry : updated.renames()) nodes.removeAlias(entry.target());
        session.applyCodeData(JadxEditStore.codeData(updated));
    }

    private <T> EditResult<T> edit(EditEntry entry, Supplier<T> verify) throws IOException {
        JadxEditStore.Snapshot previous = store.committed(), updated = store.candidate(entry);
        T evidence;
        try {
            applyEdits(updated, previous);
            evidence = verify.get();
            store.persist(updated);
        } catch (Exception ex) {
            try { applyEdits(previous, updated); }
            catch (Exception rollback) { ex.addSuppressed(rollback); }
            throw ex;
        }
        store.publish(updated);
        return new EditResult<>(entry, evidence);
    }

    private String verifyName(NodeRef ref, String name) {
        ICodeInfo code = decompiler.source(nodes.cls(ref.className()));
        for (var entry : code.getCodeMetadata().getAsMap().entrySet())
            if (entry.getValue() instanceof NodeDeclareRef declaration && declaration.getNode() instanceof ICodeNode node && JadxNodes.nodeRef(node).equals(ref)) {
                String alias = node instanceof ClassNode c ? c.getShortName() : node instanceof MethodNode m ? m.getAlias() : ((FieldNode) node).getAlias();
                if (alias.equals(name)) {
                    String text = code.getCodeStr();
                    int line = (int) text.substring(0, entry.getKey()).chars().filter(c -> c == '\n').count();
                    return WorkerJson.lines(text).get(line);
                }
            }
        throw new IllegalStateException("Rename did not render on the target declaration; rolled back");
    }

    @Override public EditResult<String> renameClass(String newName, String className) throws IOException { return renameNode(nodes.cls(className), newName); }
    @Override public EditResult<String> renameMethod(String newName, MethodRequest request) throws IOException { return renameNode(nodes.method(request), newName); }
    @Override public EditResult<String> renameField(String newName, String className, String fieldName) throws IOException { return renameNode(nodes.field(className, fieldName), newName); }

    private EditResult<String> renameNode(ICodeNode node, String name) throws IOException {
        if (node instanceof MethodNode m && (m.getMethodInfo().isConstructor() || m.getMethodInfo().isClassInit()))
            throw new IllegalArgumentException("Constructors and initializers cannot be renamed; rename the class");
        NodeRef ref = JadxNodes.nodeRef(node);
        return edit(new Rename(new Target(ref, null), name), () -> verifyName(ref, name));
    }

    @Override public EditResult<List<String>> renamePackage(String oldName, String newName) throws IOException {
        validName(newName, true);
        List<PackageNode> packages = session.packages().stream()
                .filter(p -> p.getFullName().equals(oldName) || p.getAliasPkgInfo().getFullName().equals(oldName)).toList();
        if (packages.size() != 1) throw new IllegalArgumentException("Package unavailable or ambiguous: " + oldName);
        String original = packages.getFirst().getFullName();
        List<String> names = session.classes().values().stream().filter(c -> JadxNodes.inPackage(c.getClassInfo().getPackage(), original)).map(ClassNode::getRawName).toList();
        if (names.isEmpty()) throw new IllegalArgumentException("Package contains no source classes to verify");
        return edit(new Rename(new Target(new NodeRef("PKG", original, ""), null), "." + newName), () -> {
            for (String name : names) if (!JadxNodes.inPackage(nodes.cls(name).getPackage(), newName))
                throw new IllegalStateException("Package rename did not apply to all classes");
            ClassNode cls = nodes.cls(names.getFirst());
            String code = decompiler.source(cls).getCodeStr();
            if (!code.contains("package " + cls.getPackage() + ";")) throw new IllegalStateException("Package rename did not render");
            return List.of(WorkerJson.lines(code).getFirst());
        });
    }

    @Override public VariableTarget variableTarget(VariableRename request) {
        MethodNode method = nodes.method(request.method());
        return new VariableTarget(request.newName(), request.variableName(), JadxNodes.nodeRef(method), JadxNodes.describe(method).originalId());
    }

    @Override public EditResult<Variable> renameVariable(VariableTarget request, VariableSelection selection) throws IOException {
        NodeRef ref = request.node();
        String name = request.newName(), methodId = request.methodId(), reg = selection.reg(), ssa = selection.ssa();
        List<Variable> variables = decompiler.variables(decompiler.source(nodes.cls(ref.className()))).stream()
                .filter(v -> v.methodId().equals(methodId)).toList();
        List<Variable> matches = variables.stream().filter(v -> v.name().equals(request.variableName())
                && (reg == null || v.reg() == Integer.parseInt(reg))
                && (ssa == null || v.ssa() == Integer.parseInt(ssa))).toList();
        if (matches.size() != 1) throw new Candidates("Variable unavailable or ambiguous. Candidates: ", variables);
        Variable variable = matches.getFirst();
        int index = variable.reg() << 16 | variable.ssa();
        return edit(new Rename(new Target(ref, new CodeRef("VAR", index)), name), () -> {
            boolean found = decompiler.variables(decompiler.source(nodes.cls(ref.className()))).stream().anyMatch(v -> v.methodId().equals(methodId)
                    && v.reg() == variable.reg() && v.ssa() == variable.ssa() && v.name().equals(name));
            if (!found) throw new IllegalStateException("Variable rename did not render");
            return new Variable(variable.methodId(), name, variable.reg(), variable.ssa());
        });
    }

    private List<String> commentRegions(ICodeInfo code, Target target) {
        List<String> text = WorkerJson.lines(code.getCodeStr());
        SortedMap<Integer, Target> targets = decompiler.lineTargets(code);
        List<Integer> positions = new ArrayList<>(targets.keySet());
        List<String> regions = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            int line = positions.get(i);
            if (!targets.get(line).equals(target)) continue;
            int start = target.code() != null ? line - 1 : i == 0 ? 0 : positions.get(i - 1);
            int end = target.code() != null ? i + 1 < positions.size() ? positions.get(i + 1) - 1 : text.size() : line;
            regions.add(String.join("\n", text.subList(start, end).stream().map(WorkerJson::strip).toList()));
        }
        if (regions.isEmpty()) throw new IllegalStateException("Comment target has no regenerated source metadata; rolled back");
        return regions;
    }

    private static String renderedComment(String comment, String name) {
        CommentStyle style = CommentStyle.valueOf(name);
        String rendered = style.getStart() + String.join("\n" + style.getOnNewLine(), comment.split("\\r\\n|[\\n\\r\\u000b\\f\\u0085\\u2028\\u2029]", -1)) + style.getEnd();
        return String.join("\n", WorkerJson.lines(rendered).stream().map(WorkerJson::strip).toList());
    }

    @Override public EditResult<String> addComment(CommentRequest request, String className) throws IOException {
        String comment = request.comment(), style = request.style(), method = request.method(), signature = request.signature(), field = request.field();
        Integer line = request.line();
        ClassNode cls = nodes.cls(className);
        Target target;
        if (line != null) {
            var targets = decompiler.lineTargets(decompiler.source(cls));
            target = targets.get(line);
            if (target == null) throw new IllegalArgumentException("Line has no commentable metadata. Nearest lines: "
                    + targets.keySet().stream().sorted(Comparator.comparingLong(n -> Math.abs((long) n - line))).limit(8).toList());
        } else {
            ICodeNode node = method != null && !method.isEmpty() ? nodes.method(new MethodRequest(cls.getRawName(), method, signature))
                    : field != null && !field.isEmpty() ? nodes.field(cls.getRawName(), field) : cls;
            target = new Target(JadxNodes.nodeRef(node), null);
        }
        JadxEditStore.SavedEntry previous = store.comments().stream().filter(e -> e.matches(target)).findFirst().orElse(null);
        return edit(new Comment(target, comment, style), () -> {
            ICodeInfo code = decompiler.source(nodes.cls(target.node().className()));
            List<String> regions = commentRegions(code, target);
            if (!comment.isEmpty()) {
                String expected = renderedComment(comment, style);
                if (regions.stream().noneMatch(r -> r.contains(expected))) throw new IllegalStateException("Comment did not render; rolled back");
                return expected;
            }
            if (previous != null && regions.stream().anyMatch(r -> r.contains(renderedComment(previous.comment(), previous.style()))))
                throw new IllegalStateException("Removed comment still renders; rolled back");
            return "Comment removed from regenerated source";
        });
    }

    @Override public Comments listComments(String name) {
        String original = name.isEmpty() ? null : nodes.cls(name).getRawName();
        return new Comments(store.comments().stream().filter(e -> original == null || e.node().className().equals(original)).toList());
    }
}
