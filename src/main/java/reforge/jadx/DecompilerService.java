package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import java.io.IOException;
import jadx.api.*;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.annotations.*;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.*;

/** On-demand source and metadata; cache operations go through the cache owner. */
final class DecompilerService implements JadxServices.Decompiler {
    private final JadxNodes nodes;
    private final JadxCodeCache cache;
    DecompilerService(JadxNodes nodes, JadxCodeCache cache) { this.nodes = nodes; this.cache = cache; }

    @Override public ICodeInfo source(ClassNode cls) {
        ClassNode top = cls.getTopParentClass();
        cache.recordRequest(top.getRawName());
        ICodeInfo code = top.decompile();
        if (WorkerJson.strip(code.getCodeStr()).isEmpty() || !code.hasMetadata()) throw new IllegalStateException("No source available for " + cls.getRawName());
        return code;
    }

    @Override public List<String> sourceErrors(ClassNode cls) {
        List<String> errors = new ArrayList<>();
        Deque<ICodeNode> pending = new ArrayDeque<>(List.of(cls.getTopParentClass()));
        while (!pending.isEmpty()) {
            ICodeNode node = pending.removeLast();
            node.getAll(AType.JADX_ERROR).forEach(e -> errors.add(e.toString()));
            if (node instanceof ClassNode c) { pending.addAll(c.getInnerClasses()); pending.addAll(c.getMethods()); }
        }
        return errors;
    }

    @Override public ClassSource classSource(String name, boolean numbered) {
        ClassNode cls = nodes.cls(name);
        ICodeInfo code = source(cls);
        String text = code.getCodeStr();
        if (numbered) {
            List<String> numberedLines = new ArrayList<>();
            for (String line : WorkerJson.lines(text)) numberedLines.add((numberedLines.size() + 1) + ": " + line);
            text = String.join("\n", numberedLines);
        }
        return new ClassSource(JadxNodes.describe(cls), cls.getTopParentClass().getRawName(), text,
                variables(code), lineTargets(code).keySet(), sourceErrors(cls));
    }

    @Override public MethodSource methodSource(MethodRequest request) {
        MethodNode method = nodes.method(request);
        source(method.getParentClass());
        String text = method.getCodeStr();
        if (WorkerJson.strip(text).isEmpty()) throw new IllegalStateException("Method has no rendered source (possibly inlined or suppressed); inspect class or smali");
        return new MethodSource(JadxNodes.describe(method), text, sourceErrors(method.getParentClass()));
    }

    @Override public CacheStats cacheStats() throws IOException { return cache.stats(); }
    @Override public CacheStats clearCache() throws IOException { cache.clear(); return cache.stats(); }
    @Override public void invalidateCache() throws IOException { cache.invalidate(); }

    @Override public List<Variable> variables(ICodeInfo code) {
        Map<String, Variable> result = new LinkedHashMap<>();
        for (ICodeAnnotation value : code.getCodeMetadata().getAsMap().values()) {
            ICodeAnnotation ann = value instanceof NodeDeclareRef declaration ? declaration.getNode() : value;
            if (ann instanceof VarNode variable) {
                String method = JadxNodes.describe(variable.getMth()).originalId();
                result.put(method + ":" + variable.getReg() + ":" + variable.getSsa(), new Variable(method, variable.getName(), variable.getReg(), variable.getSsa()));
            }
        }
        return new ArrayList<>(result.values());
    }

    @Override public SortedMap<Integer, Target> lineTargets(ICodeInfo code) {
        String text = code.getCodeStr();
        List<Integer> starts = new ArrayList<>();
        if (!text.isEmpty()) starts.add(0);
        var matcher = WorkerJson.NEWLINE.matcher(text);
        while (matcher.find()) if (matcher.end() < text.length()) starts.add(matcher.end());
        SortedMap<Integer, Target> targets = new TreeMap<>();
        var metadata = code.getCodeMetadata();
        for (var entry : metadata.getAsMap().entrySet()) {
            int found = Collections.binarySearch(starts, entry.getKey());
            int line = found >= 0 ? found + 1 : -found - 1;
            if (entry.getValue() instanceof NodeDeclareRef declaration && declaration.getNode() instanceof ICodeNode node)
                targets.put(line, new Target(JadxNodes.nodeRef(node), null));
            else if (entry.getValue() instanceof InsnCodeOffset offset && metadata.getNodeAt(entry.getKey()) instanceof MethodNode method)
                targets.putIfAbsent(line, new Target(JadxNodes.nodeRef(method), new CodeRef("INSN", offset.getOffset())));
        }
        return targets;
    }
}
