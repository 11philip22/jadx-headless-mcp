package reforge.jadx;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Workspace input validation and container-aware selection, independent of transport. */
final class JadxInputs {
    record Inputs(List<Path> paths, Inventory inventory) {}
    record InventoryStatus(List<String> limitations, boolean hasDex) {}

    static final class Inventory {
        private final JsonArray json;
        Inventory(JsonArray json) { this.json = json.deepCopy(); }
        JsonArray json() { return json.deepCopy(); }
        InventoryStatus status() {
            List<String> result = new ArrayList<>();
            boolean hasDex = false;
            for (JsonElement row : json) {
                JsonObject item = row.getAsJsonObject();
                if (item.has("limitation") && !item.get("limitation").isJsonNull() && !item.get("limitation").getAsString().isEmpty())
                    result.add(item.get("limitation").getAsString());
                hasDex |= item.has("has_dex") && item.get("has_dex").getAsBoolean();
            }
            return new InventoryStatus(List.copyOf(result), hasDex);
        }
    }
    static Path validate(Path workspace, String relative) throws IOException {
        Path name = Path.of(relative), root = workspace.toRealPath();
        Path path = root.resolve(name).normalize();
        if (name.isAbsolute() || !path.startsWith(root) || !Files.isRegularFile(path)
            || !path.toRealPath().startsWith(root))
            throw new IllegalArgumentException("Android input must be an existing workspace file: " + relative);
        String lower = relative.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".apk") && !lower.endsWith(".apkm") && !lower.endsWith(".xapk") && !lower.endsWith(".dex"))
            throw new IllegalArgumentException("Unsupported Android input: " + relative);
        return path;
    }

    static byte[] hash(InputStream stream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        int count;
        while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        return digest.digest();
    }

    static boolean included(Path container, String member, Path recovered) throws Exception {
        try (ZipFile zip = new ZipFile(container.toFile())) {
            boolean supported = false;
            String lower = container.toString().toLowerCase(Locale.ROOT);
            try {
                String metadata = lower.endsWith(".apkm") ? "info.json" : lower.endsWith(".xapk") ? "manifest.json" : null;
                ZipEntry entry = metadata == null ? null : zip.getEntry(metadata);
                if (entry != null) try (InputStream stream = zip.getInputStream(entry)) {
                    JsonObject info = WorkerJson.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    if (lower.endsWith(".apkm")) supported = info.has("apkm_version") && info.get("apkm_version").getAsInt() != -1;
                    else if (info.has("xapk_version") && info.get("xapk_version").getAsInt() == 2 && info.has("split_apks"))
                        supported = info.getAsJsonArray("split_apks").asList().stream().anyMatch(apk -> member.equals(WorkerJson.string(apk.getAsJsonObject(), "file")));
                }
            } catch (RuntimeException | IOException ex) {
                // Unrecognized metadata requires the recovered APK explicitly.
            }
            ZipEntry original = zip.getEntry(member);
            if (!supported || original == null) return false;
            try (InputStream source = zip.getInputStream(original); InputStream file = Files.newInputStream(recovered)) {
                return Arrays.equals(hash(source), hash(file));
            }
        }
    }

    static Inputs read(Path workspace) throws Exception {
        JsonElement value = JsonParser.parseString(Files.readString(workspace.resolve("analysis/jadx/inputs.json")));
        if (!value.isJsonArray()) throw new IllegalArgumentException("Android input inventory must be an array");
        JsonArray inventory = value.getAsJsonArray();
        Map<String, Path> paths = new LinkedHashMap<>();
        for (JsonElement row : inventory) {
            String relative = WorkerJson.string(row.getAsJsonObject(), "path");
            paths.put(relative, validate(workspace, relative));
        }
        List<String> inputs = new ArrayList<>();
        for (JsonElement row : inventory) {
            JsonObject item = row.getAsJsonObject();
            Path path = paths.get(WorkerJson.string(item, "path"));
            Path container = paths.get(WorkerJson.string(item, "container", null));
            String member = WorkerJson.string(item, "member", null);
            if (container != null && member != null && included(container, member, path)) continue;
            if (!inputs.contains(path.toString())) inputs.add(path.toString());
        }
        if (inputs.isEmpty()) throw new IllegalArgumentException("No Android inputs registered in analysis/jadx/inputs.json");
        return new Inputs(inputs.stream().map(Path::of).toList(), new Inventory(inventory));
    }
}
