package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import jadx.core.dex.nodes.*;
import jadx.core.dex.attributes.AType;

/** Metadata searches and references, with source work delegated to the decompiler. */
final class SearchService implements JadxServices.Search {
    private final JadxSession session;
    private final JadxNodes nodes;
    private final JadxServices.Decompiler decompiler;
    private final JadxEditStore edits;
    SearchService(JadxSession session, JadxNodes nodes, JadxServices.Decompiler decompiler, JadxEditStore edits) {
        this.session = session;
        this.nodes = nodes;
        this.decompiler = decompiler;
        this.edits = edits;
    }

    @Override public Methods methodsByName(String name) {
        String term = WorkerJson.fold(name);
        return new Methods(session.classes().values().stream().flatMap(c -> c.getMethods().stream())
                .filter(m -> WorkerJson.fold(m.getAlias()).contains(term) || WorkerJson.fold(m.getName()).contains(term))
                .map(JadxNodes::describe).toList());
    }

    @Override public SearchResult search(SearchRequest request) {
        Set<String> scopes = request.scopes();
        String term = request.term(), pkg = request.packageName();
        List<ClassNode> selected = session.classes().values().stream().filter(c -> pkg.isEmpty() || JadxNodes.inPackage(c.getPackage(), pkg)).toList();
        List<Identity> matches = new ArrayList<>();
        List<SearchFailure> failures = new ArrayList<>();
        for (ClassNode cls : selected) {
            List<String> texts = new ArrayList<>();
            if (scopes.contains("class")) texts.addAll(List.of(cls.getFullName(), cls.getRawName()));
            if (scopes.contains("method")) cls.getMethods().forEach(m -> texts.add(m.getAlias()));
            if (scopes.contains("field")) cls.getFields().forEach(f -> texts.add(f.getAlias()));
            if (scopes.contains("comment")) for (JadxEditStore.SavedEntry entry : edits.comments()) {
                if (entry.node().className().equals(cls.getRawName())) texts.add(entry.comment());
            }
            if (scopes.contains("code")) try {
                texts.add(decompiler.source(cls).getCodeStr());
                List<String> errors = decompiler.sourceErrors(cls);
                if (!errors.isEmpty()) failures.add(new SourceErrors(cls.getRawName(), errors));
            } catch (Exception ex) { failures.add(new SourceFailure(cls.getRawName(), ex.getMessage())); }
            if (texts.stream().anyMatch(t -> WorkerJson.fold(t).contains(term))) matches.add(JadxNodes.describe(cls));
        }
        return new SearchResult(request.page().select(matches), pkg, scopes, selected.size(),
                failures, scopes.contains("code") ? "Enclosing class source includes inner classes" : null);
    }

    @Override public References classReferences(String name, PageRequest page) { return xrefs(List.of(nodes.cls(name)), page); }
    @Override public References fieldReferences(String className, String fieldName, PageRequest page) {
        return xrefs(List.of(nodes.field(className, fieldName)), page);
    }
    @Override public References methodReferences(String className, String methodName, PageRequest page) {
        List<MethodNode> methods = nodes.methods(className, methodName, null);
        if (methods.isEmpty()) throw new IllegalArgumentException("Method unavailable");
        return xrefs(methods, page);
    }

    private References xrefs(List<? extends ICodeNode> targets, PageRequest page) {
        if (session.hasUsageErrors())
            throw new IllegalStateException("Usage analysis reported errors; reference results are unavailable. See worker.log");
        Map<String, Identity> uses = new TreeMap<>();
        List<ICodeNode> expanded = new ArrayList<>(targets);
        for (ICodeNode node : targets) {
            if (node instanceof ClassNode cls) {
                expanded.addAll(cls.getMethods().stream().filter(m -> m.getMethodInfo().isConstructor()).toList());
                for (MethodNode user : cls.getUseInMth()) { Identity row = JadxNodes.describe(user); uses.put(row.originalId(), row); }
            }
            if (node instanceof MethodNode method) {
                var related = method.get(AType.METHOD_OVERRIDE);
                if (related != null) expanded.addAll(related.getRelatedMthNodes());
            }
        }
        for (ICodeNode node : expanded) for (ICodeNode user : node.getUseIn()) {
            Identity row = JadxNodes.describe(user);
            uses.put(row.originalId(), row);
        }
        return new References(page.select(new ArrayList<>(uses.values())),
                expanded.stream().map(JadxNodes::describe).toList(), "JADX bytecode usage data");
    }

}
