package reforge.jadx;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import jadx.api.data.*;
import jadx.api.data.impl.*;
import static reforge.jadx.JadxModels.*;

/** Owns the committed snapshot and the existing workspace JSON codec. */
final class JadxEditStore {
    private final Path directory;
    private Snapshot committed;

    JadxEditStore(Path directory) throws IOException {
        this.directory = directory;
        Path saved = directory.resolve("edits.json");
        committed = new Snapshot(Files.exists(saved) ? WorkerJson.parse(Files.readString(saved))
                : WorkerJson.obj("renames", new JsonArray(), "comments", new JsonArray()));
    }

    static final class Snapshot {
        private final JsonObject json;
        private Snapshot(JsonObject json) { this.json = json; }
        private List<SavedEntry> entries(String category) {
            return json.getAsJsonArray(category).asList().stream().map(e -> new SavedEntry(e.getAsJsonObject())).toList();
        }
        List<SavedEntry> renames() { return entries("renames"); }
    }

    static final class SavedEntry {
        private final JsonObject json;
        private SavedEntry(JsonObject json) { this.json = json; }
        NodeRef node() { return readNode(json.getAsJsonObject("node")); }
        Target target() { return new Target(node(), readCode(json.get("code"))); }
        String comment() { return WorkerJson.string(json, "comment"); }
        String style() { return WorkerJson.string(json, "style"); }
        boolean matches(Target target) { return sameTarget(json, encodeTarget(target)); }
    }

    Snapshot committed() { return committed; }
    List<SavedEntry> comments() { return committed.entries("comments"); }

    Snapshot candidate(EditEntry entry) {
        String category = entry instanceof Rename ? "renames" : "comments";
        String text = entry instanceof Rename rename ? rename.newName() : ((Comment) entry).comment();
        JsonObject updated = committed.json.deepCopy(), encoded = encode(entry);
        JsonArray entries = new JsonArray();
        for (JsonElement saved : updated.getAsJsonArray(category))
            if (!sameTarget(saved.getAsJsonObject(), encoded)) entries.add(saved);
        if (!text.isEmpty()) entries.add(encoded);
        updated.add(category, entries);
        return new Snapshot(updated);
    }

    void persist(Snapshot candidate) throws IOException {
        Path temporary = directory.resolve("edits.tmp");
        try {
            Files.writeString(temporary, WorkerJson.JSON.toJson(candidate.json) + "\n", StandardCharsets.UTF_8);
            // A failed atomic replace must never truncate the committed file.
            Files.move(temporary, directory.resolve("edits.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void publish(Snapshot candidate) { committed = candidate; }

    static JadxCodeData codeData(Snapshot snapshot) {
        JadxCodeData data = new JadxCodeData();
        List<ICodeRename> renames = new ArrayList<>();
        for (SavedEntry entry : snapshot.renames())
            renames.add(new JadxCodeRename(javaRef(entry.node()), codeRef(entry.target().code()), WorkerJson.string(entry.json, "new_name")));
        List<ICodeComment> comments = new ArrayList<>();
        for (SavedEntry entry : snapshot.entries("comments"))
            comments.add(new JadxCodeComment(javaRef(entry.node()), codeRef(entry.target().code()), entry.comment(), CommentStyle.valueOf(entry.style())));
        data.setRenames(renames);
        data.setComments(comments);
        return data;
    }

    private static JadxNodeRef javaRef(NodeRef ref) {
        return new JadxNodeRef(IJavaNodeRef.RefType.valueOf(ref.type()), ref.className(), ref.id());
    }
    private static JadxCodeRef codeRef(CodeRef ref) {
        return ref == null ? null : new JadxCodeRef(CodeRefType.valueOf(ref.type()), ref.index());
    }
    private static NodeRef readNode(JsonObject ref) {
        return new NodeRef(WorkerJson.string(ref, "type"), WorkerJson.string(ref, "class"), WorkerJson.string(ref, "id", null));
    }
    private static CodeRef readCode(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject ref = value.getAsJsonObject();
        return new CodeRef(WorkerJson.string(ref, "type"), WorkerJson.integer(ref, "index", 0));
    }
    private static boolean sameTarget(JsonObject a, JsonObject b) {
        return Objects.equals(a.get("node"), b.get("node")) && Objects.equals(a.get("code"), b.get("code"));
    }
    private static JsonObject encodeTarget(Target target) {
        NodeRef node = target.node();
        JsonObject result = WorkerJson.obj("node", WorkerJson.obj("type", node.type(), "class", node.className(), "id", node.id()));
        if (target.code() != null) result.add("code", WorkerJson.obj("type", target.code().type(), "index", target.code().index()));
        return result;
    }
    static JsonObject encode(EditEntry entry) {
        JsonObject result = encodeTarget(entry.target());
        return entry instanceof Rename rename ? WorkerJson.extend(result, "new_name", rename.newName())
                : WorkerJson.extend(result, "comment", ((Comment) entry).comment(), "style", ((Comment) entry).style());
    }
    static JsonObject encode(SavedEntry entry) { return entry.json.deepCopy(); }
}
