package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import jadx.core.dex.nodes.ClassNode;

/** Bytecode inventories and disassembly, without source generation. */
final class ClassService implements JadxServices.Classes {
    private final JadxSession session;
    private final JadxNodes nodes;
    ClassService(JadxSession session, JadxNodes nodes) { this.session = session; this.nodes = nodes; }

    @Override public Page<Identity> allClasses(PageRequest page) { return page.select(session.classes().values().stream().map(JadxNodes::describe).toList()); }
    @Override public PackageTree packageTree() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        session.classes().values().forEach(c -> counts.merge(c.getPackage(), 1, Integer::sum));
        return new PackageTree(session.classes().size(), counts.size(),
                counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .map(e -> new PackageCount(e.getKey(), e.getValue())).toList());
    }
    @Override public Methods methods(String name) { return new Methods(nodes.cls(name).getMethods().stream().map(JadxNodes::describe).toList()); }
    @Override public Fields fields(String name) { return new Fields(nodes.cls(name).getFields().stream().map(JadxNodes::describe).toList()); }
    @Override public Smali smali(String name) {
        ClassNode cls = nodes.cls(name);
        String smali = cls.getDisassembledCode();
        if (smali.isEmpty()) throw new IllegalStateException("No bytecode disassembly available");
        return new Smali(JadxNodes.describe(cls), smali);
    }
    @Override public List<Identity> inPackages(Set<String> packages) {
        return session.classes().values().stream()
                .filter(c -> packages.stream().anyMatch(p -> JadxNodes.inPackage(c.getClassInfo().getPackage(), p)))
                .map(JadxNodes::describe).toList();
    }
    @Override public boolean hasOriginalClass(String name) { return session.classes().containsKey(name); }
}
