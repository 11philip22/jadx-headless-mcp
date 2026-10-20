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
    static final String NO_INPUTS = "No Android inputs registered; use load_apk with a workspace-relative path.";
    record Inputs(List<Path> paths, Inventory inventory) {}
    record InventoryStatus(List<String> limitations, boolean hasDex) {}

    static final class Inventory {
        private final JsonArray json;
        Inventory(JsonArray json) { this.json = json.deepCopy(); }
        JsonArray json() { return json.deepCopy(); }
        Inventory add(Path workspace, String relative) throws IOException {
            Path path = validate(workspace, relative);
            for (JsonElement row : json)
                if (resolve(workspace, WorkerJson.string(row.getAsJsonObject(), "path")).equals(path)) return this;
            if (!relative.toLowerCase(Locale.ROOT).endsWith(".dex")) try (ZipFile archive = new ZipFile(path.toFile())) {
                // JADX exposes an empty/unrecognized archive as a raw resource, even beside valid inputs.
                if (archive.stream().noneMatch(entry -> !entry.isDirectory()))
                    throw new IllegalArgumentException("Android input archive is empty: " + relative);
            }
            JsonArray candidate = json();
            candidate.add(WorkerJson.obj("path", workspace.toRealPath().relativize(path).toString()));
            return new Inventory(candidate);
        }
        Inventory remove(Path workspace, String relative) throws IOException {
            Path path = resolve(workspace, relative);
            Set<Path> registered = new HashSet<>();
            for (JsonElement row : json) registered.add(resolve(workspace, WorkerJson.string(row.getAsJsonObject(), "path")));
            if (!registered.contains(path)) throw new IllegalArgumentException("Android input is not registered: " + relative);
            JsonArray candidate = new JsonArray();
            for (JsonElement row : json) {
                JsonObject item = row.getAsJsonObject();
                Path entry = resolve(workspace, WorkerJson.string(item, "path"));
                String containerName = WorkerJson.string(item, "container", null);
                Path container = containerName == null ? null : resolve(workspace, containerName);
                if (entry.equals(path) && container != null && !container.equals(path) && registered.contains(container))
                    throw new IllegalArgumentException("Unload the supplying container instead: " + containerName);
                if (!entry.equals(path) && !path.equals(container)) candidate.add(row.deepCopy());
            }
            return new Inventory(candidate);
        }
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
    private static Path resolve(Path workspace, String relative) throws IOException {
        Path name = Path.of(relative), root = workspace.toRealPath();
        Path path = root.resolve(name).normalize();
        if (name.getRoot() != null || !path.startsWith(root))
            throw new IllegalArgumentException("Android input must be a workspace-relative path: " + relative);
        return path;
    }

    static Path validate(Path workspace, String relative) throws IOException {
        Path path = resolve(workspace, relative);
        if (!Files.isRegularFile(path) || !Files.isReadable(path) || !path.toRealPath().startsWith(workspace.toRealPath()))
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

    static Inventory readInventory(Path workspace) throws IOException {
        String text;
        try { text = Files.readString(workspace.resolve("analysis/jadx/inputs.json")); }
        catch (NoSuchFileException absent) { return new Inventory(new JsonArray()); }
        JsonElement value = JsonParser.parseString(text);
        if (!value.isJsonArray()) throw new IllegalArgumentException("Android input inventory must be an array");
        return new Inventory(value.getAsJsonArray());
    }

    static Inputs read(Path workspace) throws Exception { return select(workspace, readInventory(workspace)); }

    static Inputs select(Path workspace, Inventory inventory) throws Exception {
        Map<String, Path> paths = new LinkedHashMap<>();
        for (JsonElement row : inventory.json) {
            String relative = WorkerJson.string(row.getAsJsonObject(), "path");
            paths.put(relative, validate(workspace, relative));
        }
        List<String> inputs = new ArrayList<>();
        for (JsonElement row : inventory.json) {
            JsonObject item = row.getAsJsonObject();
            Path path = paths.get(WorkerJson.string(item, "path"));
            Path container = paths.get(WorkerJson.string(item, "container", null));
            String member = WorkerJson.string(item, "member", null);
            if (container != null && member != null && included(container, member, path)) continue;
            if (!inputs.contains(path.toString())) inputs.add(path.toString());
        }
        return new Inputs(inputs.stream().map(Path::of).toList(), inventory);
    }

    static void persist(Path workspace, Inventory inventory) throws IOException {
        Path directory = workspace.resolve("analysis/jadx");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "inputs-", ".tmp");
        try {
            Files.writeString(temporary, WorkerJson.JSON.toJson(inventory.json) + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve("inputs.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
