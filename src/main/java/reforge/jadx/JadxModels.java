package reforge.jadx;

import java.util.*;
import jadx.core.deobf.NameMapper;

/** Transport-independent requests, identities, and results. */
final class JadxModels {
    interface Identity {
        String className();
        String originalId();
    }
    record ClassIdentity(String className, String originalId, String kind) implements Identity {}
    record MemberIdentity(String className, String name, String originalId, String signature, String kind) implements Identity {}
    record NodeRef(String type, String className, String id) {}
    record CodeRef(String type, int index) {}
    record Target(NodeRef node, CodeRef code) {}
    record Variable(String methodId, String name, int reg, int ssa) {}
    record MethodRequest(String className, String methodName, String signature) {}
    record VariableRename(String newName, String variableName, MethodRequest method) {}
    record VariableTarget(String newName, String variableName, NodeRef node, String methodId) {}
    record VariableSelection(String reg, String ssa) {}
    record CommentRequest(String comment, String style, String method, String signature, String field, Integer line) {
        CommentRequest {
            if ((method != null ? 1 : 0) + (field != null ? 1 : 0) + (line != null ? 1 : 0) > 1)
                throw new IllegalArgumentException("Specify only one of method_name, field_name, or line");
            if (signature != null && !signature.isEmpty() && method == null) throw new IllegalArgumentException("method_signature requires method_name");
            if (comment.contains("*/")) throw new IllegalArgumentException("Comment must not contain a block-comment terminator");
            if (!comment.isEmpty() && WorkerJson.strip(comment).isEmpty()) throw new IllegalArgumentException("Use an empty string to delete a comment");
        }
    }
    record SearchRequest(Set<String> scopes, PageRequest page, String term, String packageName) {}
    record PageRequest(int offset, int count) {
        void validate() {
            if (offset < 0 || count < 0) throw new IllegalArgumentException("offset and count must be nonnegative; count=0 returns all");
        }
        <T> Page<T> select(List<T> items) {
            validate();
            int start = Math.min(offset, items.size());
            int end = count == 0 ? items.size() : (int) Math.min((long) offset + count, items.size());
            return new Page<>(List.copyOf(items.subList(start, end)), items.size(), offset, count);
        }
    }
    record Page<T>(List<T> items, int total, int offset, int count) {
        <R> Page<R> withItems(List<R> replacement) { return new Page<>(replacement, total, offset, count); }
    }
    record PackageCount(String name, int classCount) {}
    record PackageTree(int totalClasses, int totalPackages, List<PackageCount> packages) {}
    record Classes(List<Identity> classes) {}
    record Methods(List<Identity> methods) {}
    record Fields(List<Identity> fields) {}
    record Smali(Identity identity, String smali) {}
    record ClassSource(Identity identity, String code) {}
    record MethodSource(Identity identity, String code, List<String> analysisErrors) {}
    interface SearchFailure {}
    record SourceErrors(String className, List<String> errors) implements SearchFailure {}
    record SourceFailure(String className, String error) implements SearchFailure {}
    record SearchResult(Page<Identity> page, String packageName, Set<String> searchIn, int scannedClasses,
                        List<SearchFailure> partialFailures, String sourceScope) {}
    record References(Page<Identity> page, List<Identity> targets, String analysis) {}
    record ResourceContent(String name, String encoding, String content) {}
    record ResourceNames(Page<String> page, List<String> limitations) {}
    record Manifests(List<ResourceContent> manifests) {}
    record Component(String manifest, boolean exported, boolean exportedExplicit, String xml) {}
    record Components(List<Component> components) {}
    record Activity(String manifest, String originalId, boolean available) {}
    record Activities(List<Activity> activities) {}
    record CacheStats(String implementation, int bufferCapacity, long cachedClasses, long diskBytes,
                      long sourceRequestHits, long sourceRequestMisses) {}
    record Status(JadxInputs.Inventory inputs, List<String> loadedInputs, int classCount, long packageCount,
                  int resourceCount, List<String> resources, String resourceListing, List<String> limitations,
                  double loadSeconds, long pid, int loadCount, CacheStats cache) {}
    record Readiness(Status status, String manifestError, int loadCount) {}
    sealed interface EditEntry permits Rename, Comment { Target target(); }
    record Rename(Target target, String newName) implements EditEntry {}
    record Comment(Target target, String comment, String style) implements EditEntry {}
    record EditResult<T>(EditEntry updated, T verifiedSource) {}
    record Comments(List<JadxEditStore.SavedEntry> comments) {}

    static String validName(String name, boolean pkg) {
        for (String part : pkg ? name.split("\\.", -1) : new String[]{name})
            if (!NameMapper.isValidIdentifier(part)) throw new IllegalArgumentException("new_name must be a valid Java identifier (or dotted package)");
        return name;
    }

    static String validStyle(String style) {
        if (!Set.of("LINE", "BLOCK", "BLOCK_CONDENSED", "JAVADOC", "JAVADOC_CONDENSED").contains(style))
            throw new IllegalArgumentException("Invalid comment style");
        return style;
    }

    static Set<String> searchScopes(String text) {
        Set<String> scopes = new TreeSet<>();
        for (String scope : text.split(",", -1)) scopes.add(WorkerJson.strip(scope));
        if (!Set.of("class", "method", "field", "code", "comment").containsAll(scopes))
            throw new IllegalArgumentException("search_in must contain class,method,field,code,comment");
        return Collections.unmodifiableSet(scopes);
    }

    static final class Candidates extends IllegalArgumentException {
        private final List<?> candidates;
        Candidates(String message, List<?> candidates) { super(message); this.candidates = candidates; }
        List<?> candidates() { return candidates; }
    }
}
