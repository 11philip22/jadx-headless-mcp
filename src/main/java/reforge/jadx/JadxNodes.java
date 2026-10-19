package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import jadx.core.dex.nodes.*;
import jadx.core.dex.attributes.AType;

/** Shared resolution and stable identities, independent of aliases and source caches. */
final class JadxNodes {
    private final JadxSession session;
    JadxNodes(JadxSession session) { this.session = session; }

    ClassNode cls(String name) {
        ClassNode node = session.classes().get(name);
        return node != null ? node : one(session.classes().values().stream().filter(c -> c.getFullName().equals(name)).toList(), "class");
    }

    static Identity describe(ICodeNode node) {
        if (node instanceof ClassNode cls)
            return new ClassIdentity(cls.getFullName(), cls.getRawName(), "CLASS");
        String id = node instanceof MethodNode m ? m.getMethodInfo().getShortId() : ((FieldNode) node).getFieldInfo().getShortId();
        String alias = node instanceof MethodNode m ? m.getAlias() : ((FieldNode) node).getAlias();
        return new MemberIdentity(node.getDeclaringClass().getFullName(), alias,
                node.getDeclaringClass().getRawName() + "->" + id, id, node.getAnnType().name());
    }

    static <T extends ICodeNode> T one(List<T> nodes, String kind) {
        if (nodes.size() != 1) throw new Candidates("Expected one " + kind + ", found " + nodes.size()
                + ". Candidates: ", nodes.stream().map(JadxNodes::describe).toList());
        return nodes.getFirst();
    }

    List<MethodNode> methods(String className, String methodName, String signature) {
        Collection<ClassNode> scope = className == null || className.isEmpty() ? session.classes().values() : List.of(cls(className));
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

    static NodeRef nodeRef(ICodeNode node) {
        if (node instanceof ClassNode cls) return new NodeRef("CLASS", cls.getRawName(), null);
        String id = node instanceof MethodNode m ? m.getMethodInfo().getShortId() : ((FieldNode) node).getFieldInfo().getShortId();
        return new NodeRef(node.getAnnType().name(), node.getDeclaringClass().getRawName(), id);
    }

    MethodNode method(MethodRequest request) {
        return one(methods(request.className(), request.methodName(), request.signature()), "method");
    }

    static boolean inPackage(String name, String pkg) { return name.equals(pkg) || name.startsWith(pkg + "."); }

    void removeAlias(Target target) {
        NodeRef ref = target.node();
        String kind = ref.type();
        if (kind.equals("PKG")) {
            PackageNode node = session.resolvePackage(ref.className());
            if (node != null) node.removeAlias();
        } else if (target.code() == null) {
            ClassNode cls = cls(ref.className());
            switch (kind) {
                case "CLASS" -> cls.removeAlias();
                case "METHOD" -> {
                    MethodNode method = cls.searchMethodByShortId(ref.id());
                    var related = method.get(AType.METHOD_OVERRIDE);
                    Collection<MethodNode> members = related == null ? List.of(method) : related.getRelatedMthNodes();
                    members.forEach(m -> m.getMethodInfo().removeAlias());
                }
                case "FIELD" -> cls.searchFieldByShortId(ref.id()).getFieldInfo().removeAlias();
                default -> throw new IllegalArgumentException("Unknown edit node: " + kind);
            }
        }
    }
}
