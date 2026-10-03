import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import jadx.api.*;
import jadx.api.data.*;
import jadx.api.data.impl.*;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.annotations.*;
import jadx.api.plugins.JadxPlugin;
import jadx.core.deobf.NameMapper;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.*;
import jadx.core.xmlgen.ResContainer;
import jadx.gui.cache.code.disk.*;

/** Job-owned, headless JADX 1.5.6 worker. Only JSON values cross the process boundary. */
public final class JadxWorker implements AutoCloseable {
    static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final Pattern NEWLINE = Pattern.compile("\\r\\n|[\\n\\r\\u000b\\f\\u001c-\\u001e\\u0085\\u2028\\u2029]");
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    final Path directory;
    final JadxArgs args = new JadxArgs();
    final JadxDecompiler decompiler;
    final RootNode root;
    final JsonArray inventory;
    final List<String> loadedInputs = new ArrayList<>();
    final Map<String, ClassNode> classes = new LinkedHashMap<>();
    final Map<String, Object> resources = new LinkedHashMap<>();
    final List<String> loadFailures = new ArrayList<>(), resourceErrors = new ArrayList<>();
    final int loadErrors;
    final double loadSeconds;
    JsonObject edits;
    BufferCodeCache cache;
    long hits, misses;
    boolean tablesLoaded;

    JadxWorker(Path directory, JsonObject input) throws Exception {
        this.directory = directory;
        inventory = input.getAsJsonArray("inventory");
        List<File> files = new ArrayList<>();
        for (JsonElement item : input.getAsJsonArray("inputs")) {
            Path path = Path.of(item.getAsString());
            files.add(path.toFile());
            loadedInputs.add(directory.getParent().getParent().relativize(path).toString());
        }
        Path saved = directory.resolve("edits.json");
        edits = Files.exists(saved) ? parse(Files.readString(saved)) : obj("renames", new JsonArray(), "comments", new JsonArray());
        int threads = Integer.parseInt(System.getenv().getOrDefault("REFORGE_JADX_THREADS", "2"));
        if (threads < 1) throw new IllegalArgumentException("REFORGE_JADX_THREADS must be positive");
        args.setThreadsCount(threads);
        args.setInputFiles(files);
        args.setCodeData(codeData(edits));
        decompiler = new JadxDecompiler(args);
        long started = System.nanoTime();
        Path log = directory.resolve("worker.log");
        long logStart = Files.exists(log) ? Files.size(log) : 0;
        try {
            decompiler.load();
            root = decompiler.getRoot();
            for (ClassNode cls : root.getClasses()) classes.put(cls.getRawName(), cls);
            loadErrors = decompiler.getErrorsCount();
            // Some input plugins log failures without incrementing the node error counter.
            if (Files.exists(log)) {
                try (var stream = Files.newInputStream(log)) {
                    stream.skipNBytes(logStart);
                    for (String line : lines(new String(stream.readAllBytes(), StandardCharsets.UTF_8)))
                        if (line.startsWith("ERROR") || line.contains("Invalid DEX magic")) loadFailures.add(line);
                }
            }
            resetCache();
            loadSeconds = (System.nanoTime() - started) / 1e9;
            int index = 0;
            for (ResourceFile resource : decompiler.getResources()) {
                String name = resource.getOriginalName();
                resources.put(resources.containsKey(name) ? index + ":" + name : name, resource);
                index++;
            }
        } catch (Exception | Error failure) {
            decompiler.close();
            throw failure;
        }
    }

    static JsonObject obj(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    static JsonObject extend(JsonObject result, Object... pairs) {
        obj(pairs).entrySet().forEach(e -> result.add(e.getKey(), e.getValue()));
        return result;
    }

    static JsonObject parse(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement result = JSON.fromJson(reader, JsonElement.class);
            if (result == null || !result.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Expected one JSON object");
            return result.getAsJsonObject();
        }
    }

    static String string(JsonObject args, String name) { return string(args, name, null, true); }
    static String string(JsonObject args, String name, String fallback) { return string(args, name, fallback, false); }
    static String string(JsonObject args, String name, String fallback, boolean required) {
        JsonElement value = args.get(name);
        if (value == null) {
            if (required) throw new IllegalArgumentException("Missing argument: " + name);
            return fallback;
        }
        if (value.isJsonNull() && !required && fallback == null) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(name + " must be a string");
        return value.getAsString();
    }

    static int integer(JsonObject args, String name, int fallback) {
        JsonElement value = args.get(name);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(name + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(name + " must be a 32-bit integer", ex); }
    }

    static boolean bool(JsonObject args, String name, boolean fallback) {
        JsonElement value = args.get(name);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(name + " must be a boolean");
        return value.getAsBoolean();
    }

    static JsonObject page(List<?> items, int offset, int count) {
        if (offset < 0 || count < 0) throw new IllegalArgumentException("offset and count must be nonnegative; count=0 returns all");
        int start = Math.min(offset, items.size());
        int end = count == 0 ? items.size() : (int) Math.min((long) offset + count, items.size());
        return obj("items", items.subList(start, end), "total", items.size(), "offset", offset, "count", count);
    }

    static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(Arrays.asList(NEWLINE.split(text, -1)));
        if (result.getLast().isEmpty()) result.removeLast();
        return result;
    }

    static String strip(String text) {
        return text.replaceAll("^[\\s\\p{Z}\\u0085\\u001c-\\u001f]+|[\\s\\p{Z}\\u0085\\u001c-\\u001f]+$", "");
    }

    static String fold(String text) {
        // Default Unicode case folding: expand mappings per code point (no final-sigma context).
        StringBuilder result = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String ch = Character.toString(cp);
            if (cp == 0x131) result.append(ch); // Dotless i must not fold to ASCII i.
            else if (cp == 0x1e9e) result.append("ss");
            else if ((cp >= 0x13a0 && cp <= 0x13fd) || (cp >= 0xab70 && cp <= 0xabbf))
                result.append(ch.toUpperCase(Locale.ROOT)); // Cherokee folds to uppercase.
            else result.append(ch.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));
        });
        return result.toString();
    }

    JsonObject status() throws IOException {
        List<String> limitations = new ArrayList<>(loadFailures);
        limitations.addAll(resourceErrors);
        boolean hasDex = false;
        for (JsonElement row : inventory) {
            JsonObject item = row.getAsJsonObject();
            if (item.has("limitation") && !item.get("limitation").isJsonNull() && !item.get("limitation").getAsString().isEmpty())
                limitations.add(item.get("limitation").getAsString());
            hasDex |= item.has("has_dex") && item.get("has_dex").getAsBoolean();
        }
        if (loadErrors != 0) limitations.add("JADX reported " + loadErrors + " loading/usage-analysis errors; see worker.log");
        if (hasDex && classes.isEmpty()) limitations.add("Registered bytecode yielded no classes");
        if (classes.isEmpty() && resources.isEmpty()) limitations.add("No classes or Android resources loaded");
        return obj("inputs", inventory, "loaded_inputs", loadedInputs, "class_count", classes.size(),
                "package_count", classes.values().stream().map(ClassNode::getPackage).distinct().count(),
                "resource_count", resources.size(), "resources", resources.keySet().stream().limit(30).toList(),
                "resource_listing", "First 30 names; get_all_resource_file_names provides the full inventory",
                "limitations", limitations, "load_seconds", loadSeconds, "pid", ProcessHandle.current().pid(),
                "load_count", 1, "cache", cacheStats());
    }

    ClassNode cls(String name) {
        ClassNode node = classes.get(name);
        return node != null ? node : one(classes.values().stream().filter(c -> c.getFullName().equals(name)).toList(), "class");
    }

    static JsonObject describe(ICodeNode node) {
        if (node instanceof ClassNode cls)
            return obj("class_name", cls.getFullName(), "original_id", cls.getRawName(), "kind", "CLASS");
        String id = node instanceof MethodNode m ? m.getMethodInfo().getShortId() : ((FieldNode) node).getFieldInfo().getShortId();
        String alias = node instanceof MethodNode m ? m.getAlias() : ((FieldNode) node).getAlias();
        return obj("class_name", node.getDeclaringClass().getFullName(), "name", alias,
                "original_id", node.getDeclaringClass().getRawName() + "->" + id, "signature", id, "kind", node.getAnnType().name());
    }

    static <T extends ICodeNode> T one(List<T> nodes, String kind) {
        if (nodes.size() != 1) throw new IllegalArgumentException("Expected one " + kind + ", found " + nodes.size()
                + ". Candidates: " + JSON.toJson(nodes.stream().map(JadxWorker::describe).toList()));
        return nodes.getFirst();
    }

    List<MethodNode> methods(String className, String methodName, String signature) {
        Collection<ClassNode> scope = className == null || className.isEmpty() ? classes.values() : List.of(cls(className));
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

    ICodeInfo source(ClassNode cls) {
        ClassNode top = cls.getTopParentClass();
        if (cache.contains(top.getRawName())) hits++; else misses++;
        ICodeInfo code = top.decompile();
        if (strip(code.getCodeStr()).isEmpty() || !code.hasMetadata()) throw new IllegalStateException("No source available for " + cls.getRawName());
        return code;
    }

    List<String> sourceErrors(ClassNode cls) {
        List<String> errors = new ArrayList<>();
        Deque<ICodeNode> pending = new ArrayDeque<>(List.of(cls.getTopParentClass()));
        while (!pending.isEmpty()) {
            ICodeNode node = pending.removeLast();
            node.getAll(AType.JADX_ERROR).forEach(e -> errors.add(e.toString()));
            if (node instanceof ClassNode c) { pending.addAll(c.getInnerClasses()); pending.addAll(c.getMethods()); }
        }
        return errors;
    }

    JsonObject classSource(String name, boolean numbered) {
        ClassNode cls = cls(name);
        ICodeInfo code = source(cls);
        String text = code.getCodeStr();
        if (numbered) {
            List<String> numberedLines = new ArrayList<>();
            for (String line : lines(text)) numberedLines.add((numberedLines.size() + 1) + ": " + line);
            text = String.join("\n", numberedLines);
        }
        return extend(describe(cls), "source_class", cls.getTopParentClass().getRawName(), "code", text,
                "variables", variables(code), "commentable_lines", lineTargets(code).keySet(), "analysis_errors", sourceErrors(cls));
    }

    JsonObject search(JsonObject a, int offset, int count) {
        Set<String> scopes = new TreeSet<>();
        for (String scope : string(a, "search_in", "code").split(",", -1)) scopes.add(strip(scope));
        if (!Set.of("class", "method", "field", "code", "comment").containsAll(scopes))
            throw new IllegalArgumentException("search_in must contain class,method,field,code,comment");
        page(List.of(), offset, count);
        String term = fold(string(a, "search_term")), pkg = string(a, "package", "");
        List<ClassNode> selected = classes.values().stream().filter(c -> pkg.isEmpty() || inPackage(c.getPackage(), pkg)).toList();
        List<JsonObject> matches = new ArrayList<>(), failures = new ArrayList<>();
        for (ClassNode cls : selected) {
            List<String> texts = new ArrayList<>();
            if (scopes.contains("class")) texts.addAll(List.of(cls.getFullName(), cls.getRawName()));
            if (scopes.contains("method")) cls.getMethods().forEach(m -> texts.add(m.getAlias()));
            if (scopes.contains("field")) cls.getFields().forEach(f -> texts.add(f.getAlias()));
            if (scopes.contains("comment")) for (JsonElement entry : edits.getAsJsonArray("comments")) {
                JsonObject e = entry.getAsJsonObject();
                if (string(e.getAsJsonObject("node"), "class").equals(cls.getRawName())) texts.add(string(e, "comment"));
            }
            if (scopes.contains("code")) try {
                texts.add(source(cls).getCodeStr());
                List<String> errors = sourceErrors(cls);
                if (!errors.isEmpty()) failures.add(obj("class", cls.getRawName(), "errors", errors));
            } catch (Exception ex) { failures.add(obj("class", cls.getRawName(), "error", ex.getMessage())); }
            if (texts.stream().anyMatch(t -> fold(t).contains(term))) matches.add(describe(cls));
        }
        return extend(page(matches, offset, count), "package", pkg, "search_in", scopes, "scanned_classes", selected.size(),
                "partial_failures", failures, "source_scope", scopes.contains("code") ? "Enclosing class source includes inner classes" : null);
    }

    static boolean inPackage(String name, String pkg) { return name.equals(pkg) || name.startsWith(pkg + "."); }

    void loadTables() {
        if (tablesLoaded) return;
        for (var entry : new LinkedHashMap<>(resources).entrySet()) {
            if (!(entry.getValue() instanceof ResourceFile resource) || resource.getType() != ResourceType.ARSC) continue;
            try {
                ResContainer container = resource.loadContent();
                if (container == null || container.getDataType() != ResContainer.DataType.RES_TABLE)
                    throw new IllegalStateException("JADX failed to decode resource table; see worker.log");
                Deque<ResContainer> pending = new ArrayDeque<>(container.getSubFiles());
                while (!pending.isEmpty()) {
                    ResContainer child = pending.removeLast();
                    String name = child.getName();
                    resources.put(resources.containsKey(name) ? entry.getKey() + ":" + name : name, child);
                    pending.addAll(child.getSubFiles());
                }
            } catch (Exception ex) { resourceErrors.add(entry.getKey() + ": " + ex.getMessage()); }
        }
        tablesLoaded = true;
    }

    JsonObject resourceFile(String name) throws IOException {
        loadTables();
        Object resource = resources.get(name);
        if (resource == null) throw new IllegalArgumentException("Resource unavailable: " + name);
        ResContainer content = resource instanceof ResourceFile f ? f.loadContent() : (ResContainer) resource;
        if (content == null) throw new IllegalStateException("JADX could not decode resource");
        byte[] raw;
        switch (content.getDataType()) {
            case TEXT, RES_TABLE -> {
                String text = content.getText().getCodeStr();
                if (text.startsWith("Error decode ")) throw new IllegalStateException("JADX resource decoding failed; see worker.log");
                return obj("name", name, "encoding", "text", "content", text);
            }
            case DECODED_DATA -> raw = content.getDecodedData();
            case RES_LINK -> {
                var entry = content.getResLink().getZipEntry();
                if (entry == null) throw new IllegalStateException("Resource has no archive entry");
                try (var stream = entry.getInputStream()) { raw = stream.readAllBytes(); }
            }
            default -> throw new IllegalStateException("Unsupported resource type: " + content.getDataType());
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
            return obj("name", name, "encoding", "text", "content", text);
        } catch (CharacterCodingException ex) { return obj("name", name, "encoding", "base64", "content", Base64.getEncoder().encodeToString(raw)); }
    }

    List<String> manifestNames() {
        List<String> names = resources.keySet().stream().filter(n -> n.endsWith("AndroidManifest.xml")).toList();
        if (names.isEmpty()) throw new IllegalArgumentException("No AndroidManifest.xml available");
        return names;
    }

    Map<String, Element> manifests() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Map<String, Element> result = new LinkedHashMap<>();
        for (String name : manifestNames()) {
            String text = string(resourceFile(name), "content");
            result.put(name, factory.newDocumentBuilder().parse(new InputSource(new StringReader(text))).getDocumentElement());
        }
        return result;
    }

    static List<Element> elements(Element parent, String tag, boolean direct) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = direct ? parent.getChildNodes() : parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element e && e.getTagName().equals(tag)) result.add(e);
        return result;
    }

    JsonObject components(String type, boolean onlyExported) throws Exception {
        if (!Set.of("activity", "activity-alias", "application", "service", "receiver", "provider").contains(type))
            throw new IllegalArgumentException("Unsupported manifest component type");
        List<JsonObject> result = new ArrayList<>();
        var factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        for (var manifest : manifests().entrySet()) {
            List<Element> sdk = elements(manifest.getValue(), "uses-sdk", true);
            String target = sdk.isEmpty() ? "" : sdk.getFirst().getAttributeNS(ANDROID, "targetSdkVersion");
            for (Element e : elements(manifest.getValue(), type, false)) {
                boolean explicit = e.hasAttributeNS(ANDROID, "exported");
                boolean exported = explicit ? e.getAttributeNS(ANDROID, "exported").equals("true")
                        : type.equals("provider") ? Integer.parseInt(target.isEmpty() ? "1" : target) < 17 : !elements(e, "intent-filter", true).isEmpty();
                if (!onlyExported || exported) {
                    StringWriter xml = new StringWriter();
                    transformer.transform(new DOMSource(e), new StreamResult(xml));
                    result.add(obj("manifest", manifest.getKey(), "exported", exported, "exported_explicit", explicit, "xml", xml.toString()));
                }
            }
        }
        return obj("components", result);
    }

    List<ClassNode> applicationClasses() throws Exception {
        Set<String> packages = new HashSet<>();
        for (Element manifest : manifests().values()) if (!manifest.getAttribute("package").isEmpty()) packages.add(manifest.getAttribute("package"));
        return classes.values().stream().filter(c -> packages.stream().anyMatch(p -> inPackage(c.getClassInfo().getPackage(), p))).toList();
    }

    JsonObject mainActivities() throws Exception {
        List<JsonObject> result = new ArrayList<>();
        for (var manifest : manifests().entrySet()) {
            String pkg = manifest.getValue().getAttribute("package");
            List<Element> activities = elements(manifest.getValue(), "activity", false);
            activities.addAll(elements(manifest.getValue(), "activity-alias", false));
            for (Element activity : activities) for (Element intent : elements(activity, "intent-filter", true)) {
                boolean main = elements(intent, "action", true).stream().anyMatch(e -> e.getAttributeNS(ANDROID, "name").equals("android.intent.action.MAIN"));
                boolean launcher = elements(intent, "category", true).stream().anyMatch(e -> e.getAttributeNS(ANDROID, "name").equals("android.intent.category.LAUNCHER"));
                if (main && launcher) {
                    String name = activity.getAttributeNS(ANDROID, "targetActivity");
                    if (name.isEmpty()) name = activity.getAttributeNS(ANDROID, "name");
                    name = name.startsWith(".") ? pkg + name : !name.contains(".") ? pkg + "." + name : name;
                    result.add(obj("manifest", manifest.getKey(), "original_id", name, "available", classes.containsKey(name)));
                }
            }
        }
        return obj("activities", result);
    }

    JsonObject xrefs(List<? extends ICodeNode> targets, int offset, int count) {
        if (loadErrors != 0 || !loadFailures.isEmpty())
            throw new IllegalStateException("Usage analysis reported errors; reference results are unavailable. See worker.log");
        Map<String, JsonObject> uses = new TreeMap<>();
        List<ICodeNode> expanded = new ArrayList<>(targets);
        for (ICodeNode node : targets) {
            if (node instanceof ClassNode cls) {
                expanded.addAll(cls.getMethods().stream().filter(m -> m.getMethodInfo().isConstructor()).toList());
                for (MethodNode user : cls.getUseInMth()) { JsonObject row = describe(user); uses.put(string(row, "original_id"), row); }
            }
            if (node instanceof MethodNode method) {
                var related = method.get(AType.METHOD_OVERRIDE);
                if (related != null) expanded.addAll(related.getRelatedMthNodes());
            }
        }
        for (ICodeNode node : expanded) for (ICodeNode user : node.getUseIn()) {
            JsonObject row = describe(user);
            uses.put(string(row, "original_id"), row);
        }
        return extend(page(new ArrayList<>(uses.values()), offset, count),
                "targets", expanded.stream().map(JadxWorker::describe).toList(), "analysis", "JADX bytecode usage data");
    }

    void resetCache() throws IOException {
        // ponytail: edits invalidate all classes; use usage-based invalidation if frequent edits become expensive.
        if (cache != null) {
            cache.close(); // Wait for the disk writer before removing cached data.
            List<ClassNode> unloaded = new ArrayList<>();
            for (ClassNode cls : classes.values()) if (!cls.isInner() && cls.getState() != ProcessState.NOT_LOADED) {
                cls.unloadCode();
                unloaded.add(cls);
            }
            // Deep unload replaces method nodes; restore metadata-only passes before the next source request.
            unloaded.forEach(root::runPreDecompileStageForClass);
        }
        Path path = directory.resolve("cache");
        if (Files.exists(path)) try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(child);
        }
        cache = new BufferCodeCache(new DiskCodeCache(root, path));
        args.setCodeCache(cache);
    }

    JsonObject cacheStats() throws IOException {
        long bytes = 0;
        Path path = directory.resolve("cache");
        if (Files.exists(path)) try (var files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) bytes += Files.size(file);
        }
        return obj("implementation", "JADX BufferCodeCache + DiskCodeCache", "buffer_capacity", 20,
                "cached_classes", classes.values().stream().filter(c -> !c.isInner() && cache.contains(c.getRawName())).count(),
                "disk_bytes", bytes, "source_request_hits", hits, "source_request_misses", misses);
    }

    static JsonObject nodeRef(ICodeNode node) {
        if (node instanceof ClassNode cls) return obj("type", "CLASS", "class", cls.getRawName(), "id", null);
        String id = node instanceof MethodNode m ? m.getMethodInfo().getShortId() : ((FieldNode) node).getFieldInfo().getShortId();
        return obj("type", node.getAnnType().name(), "class", node.getDeclaringClass().getRawName(), "id", id);
    }

    static JadxNodeRef javaRef(JsonObject ref) {
        return new JadxNodeRef(IJavaNodeRef.RefType.valueOf(string(ref, "type")), string(ref, "class"), string(ref, "id", null));
    }

    static JadxCodeRef codeRef(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject ref = value.getAsJsonObject();
        return new JadxCodeRef(CodeRefType.valueOf(string(ref, "type")), integer(ref, "index", 0));
    }

    static JadxCodeData codeData(JsonObject edits) {
        JadxCodeData data = new JadxCodeData();
        List<ICodeRename> renames = new ArrayList<>();
        for (JsonElement value : edits.getAsJsonArray("renames")) {
            JsonObject e = value.getAsJsonObject();
            renames.add(new JadxCodeRename(javaRef(e.getAsJsonObject("node")), codeRef(e.get("code")), string(e, "new_name")));
        }
        List<ICodeComment> comments = new ArrayList<>();
        for (JsonElement value : edits.getAsJsonArray("comments")) {
            JsonObject e = value.getAsJsonObject();
            comments.add(new JadxCodeComment(javaRef(e.getAsJsonObject("node")), codeRef(e.get("code")), string(e, "comment"), CommentStyle.valueOf(string(e, "style"))));
        }
        data.setRenames(renames);
        data.setComments(comments);
        return data;
    }

    void applyEdits(JsonObject updated, JsonObject previous) throws IOException {
        resetCache();
        JsonArray entries = new JsonArray();
        entries.addAll(previous.getAsJsonArray("renames"));
        entries.addAll(updated.getAsJsonArray("renames"));
        for (JsonElement entry : entries) {
            JsonObject e = entry.getAsJsonObject(), ref = e.getAsJsonObject("node");
            String kind = string(ref, "type");
            if (kind.equals("PKG")) {
                PackageNode node = root.resolvePackage(string(ref, "class"));
                if (node != null) node.removeAlias();
            } else if (e.get("code") == null || e.get("code").isJsonNull()) {
                ClassNode cls = cls(string(ref, "class"));
                switch (kind) {
                    case "CLASS" -> cls.removeAlias();
                    case "METHOD" -> {
                        MethodNode method = cls.searchMethodByShortId(string(ref, "id"));
                        var related = method.get(AType.METHOD_OVERRIDE);
                        Collection<MethodNode> members = related == null ? List.of(method) : related.getRelatedMthNodes();
                        members.forEach(m -> m.getMethodInfo().removeAlias());
                    }
                    case "FIELD" -> cls.searchFieldByShortId(string(ref, "id")).getFieldInfo().removeAlias();
                    default -> throw new IllegalArgumentException("Unknown edit node: " + kind);
                }
            }
        }
        args.setCodeData(codeData(updated));
        decompiler.reloadCodeData();
    }

    static boolean sameTarget(JsonObject a, JsonObject b) {
        return Objects.equals(a.get("node"), b.get("node")) && Objects.equals(a.get("code"), b.get("code"));
    }

    JsonObject edit(String category, JsonObject entry, Supplier<Object> verify) throws IOException {
        JsonObject previous = edits, updated = edits.deepCopy();
        JsonArray entries = new JsonArray();
        for (JsonElement e : updated.getAsJsonArray(category)) if (!sameTarget(e.getAsJsonObject(), entry)) entries.add(e);
        String text = string(entry, category.equals("renames") ? "new_name" : "comment");
        if (!text.isEmpty()) entries.add(entry);
        updated.add(category, entries);
        Path temporary = directory.resolve("edits.tmp");
        Object evidence;
        try {
            applyEdits(updated, previous);
            evidence = verify.get();
            Files.writeString(temporary, JSON.toJson(updated) + "\n", StandardCharsets.UTF_8);
            // A failed atomic replace is an edit failure; never risk truncating the persisted file.
            Files.move(temporary, directory.resolve("edits.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            try { applyEdits(previous, updated); }
            catch (Exception rollback) { ex.addSuppressed(rollback); }
            throw ex;
        } finally { Files.deleteIfExists(temporary); }
        edits = updated;
        return obj("updated", entry, "verified_source", evidence);
    }

    static void validName(String name, boolean pkg) {
        for (String part : pkg ? name.split("\\.", -1) : new String[]{name})
            if (!NameMapper.isValidIdentifier(part)) throw new IllegalArgumentException("new_name must be a valid Java identifier (or dotted package)");
    }

    String verifyName(JsonObject ref, String name) {
        ICodeInfo code = source(cls(string(ref, "class")));
        for (var entry : code.getCodeMetadata().getAsMap().entrySet())
            if (entry.getValue() instanceof NodeDeclareRef declaration && declaration.getNode() instanceof ICodeNode node && nodeRef(node).equals(ref)) {
                String alias = node instanceof ClassNode c ? c.getShortName() : node instanceof MethodNode m ? m.getAlias() : ((FieldNode) node).getAlias();
                if (alias.equals(name)) {
                    String text = code.getCodeStr();
                    int line = (int) text.substring(0, entry.getKey()).chars().filter(c -> c == '\n').count();
                    return lines(text).get(line);
                }
            }
        throw new IllegalStateException("Rename did not render on the target declaration; rolled back");
    }

    JsonObject renameNode(JsonObject a, String kind) throws IOException {
        String name = string(a, "new_name");
        validName(name, false);
        ICodeNode node = switch (kind) {
            case "CLASS" -> cls(string(a, "class_name"));
            case "FIELD" -> field(string(a, "class_name"), string(a, "field_name"));
            default -> one(methods(string(a, "class_name", null), string(a, "method_name"), string(a, "method_signature", null)), "method");
        };
        if (node instanceof MethodNode m && (m.getMethodInfo().isConstructor() || m.getMethodInfo().isClassInit()))
            throw new IllegalArgumentException("Constructors and initializers cannot be renamed; rename the class");
        JsonObject ref = nodeRef(node);
        return edit("renames", obj("node", ref, "new_name", name), () -> verifyName(ref, name));
    }

    JsonObject renamePackage(String oldName, String newName) throws IOException {
        validName(newName, true);
        List<PackageNode> packages = root.getPackages().stream()
                .filter(p -> p.getFullName().equals(oldName) || p.getAliasPkgInfo().getFullName().equals(oldName)).toList();
        if (packages.size() != 1) throw new IllegalArgumentException("Package unavailable or ambiguous: " + oldName);
        String original = packages.getFirst().getFullName();
        List<String> names = classes.values().stream().filter(c -> inPackage(c.getClassInfo().getPackage(), original)).map(ClassNode::getRawName).toList();
        if (names.isEmpty()) throw new IllegalArgumentException("Package contains no source classes to verify");
        return edit("renames", obj("node", obj("type", "PKG", "class", original, "id", ""), "new_name", "." + newName), () -> {
            for (String name : names) if (!inPackage(cls(name).getPackage(), newName))
                throw new IllegalStateException("Package rename did not apply to all classes");
            ClassNode cls = cls(names.getFirst());
            String code = source(cls).getCodeStr();
            if (!code.contains("package " + cls.getPackage() + ";")) throw new IllegalStateException("Package rename did not render");
            return List.of(lines(code).getFirst());
        });
    }

    List<JsonObject> variables(ICodeInfo code) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (ICodeAnnotation value : code.getCodeMetadata().getAsMap().values()) {
            ICodeAnnotation ann = value instanceof NodeDeclareRef declaration ? declaration.getNode() : value;
            if (ann instanceof VarNode variable) {
                String method = string(describe(variable.getMth()), "original_id");
                result.put(method + ":" + variable.getReg() + ":" + variable.getSsa(), obj("method_id", method,
                        "name", variable.getName(), "reg", variable.getReg(), "ssa", variable.getSsa()));
            }
        }
        return new ArrayList<>(result.values());
    }

    JsonObject renameVariable(JsonObject a) throws IOException {
        String name = string(a, "new_name"), variableName = string(a, "variable_name");
        validName(name, false);
        MethodNode method = one(methods(string(a, "class_name"), string(a, "method_name"), null), "method");
        JsonObject ref = nodeRef(method);
        String methodId = string(describe(method), "original_id"), reg = string(a, "reg", null), ssa = string(a, "ssa", null);
        List<JsonObject> variables = variables(source(method.getParentClass())).stream().filter(v -> string(v, "method_id").equals(methodId)).toList();
        List<JsonObject> matches = variables.stream().filter(v -> string(v, "name").equals(variableName)
                && (reg == null || integer(v, "reg", 0) == Integer.parseInt(reg))
                && (ssa == null || integer(v, "ssa", 0) == Integer.parseInt(ssa))).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Variable unavailable or ambiguous. Candidates: " + JSON.toJson(variables));
        JsonObject variable = matches.getFirst();
        int index = integer(variable, "reg", 0) << 16 | integer(variable, "ssa", 0);
        return edit("renames", obj("node", ref, "code", obj("type", "VAR", "index", index), "new_name", name), () -> {
            boolean found = variables(source(cls(string(ref, "class")))).stream().anyMatch(v -> string(v, "method_id").equals(methodId)
                    && v.get("reg").equals(variable.get("reg")) && v.get("ssa").equals(variable.get("ssa")) && string(v, "name").equals(name));
            if (!found) throw new IllegalStateException("Variable rename did not render");
            return extend(variable.deepCopy(), "name", name);
        });
    }

    SortedMap<Integer, JsonObject> lineTargets(ICodeInfo code) {
        String text = code.getCodeStr();
        List<Integer> starts = new ArrayList<>();
        if (!text.isEmpty()) starts.add(0);
        var matcher = NEWLINE.matcher(text);
        while (matcher.find()) if (matcher.end() < text.length()) starts.add(matcher.end());
        SortedMap<Integer, JsonObject> targets = new TreeMap<>();
        var metadata = code.getCodeMetadata();
        for (var entry : metadata.getAsMap().entrySet()) {
            int found = Collections.binarySearch(starts, entry.getKey());
            int line = found >= 0 ? found + 1 : -found - 1;
            if (entry.getValue() instanceof NodeDeclareRef declaration && declaration.getNode() instanceof ICodeNode node)
                targets.put(line, obj("node", nodeRef(node)));
            else if (entry.getValue() instanceof InsnCodeOffset offset && metadata.getNodeAt(entry.getKey()) instanceof MethodNode method)
                targets.putIfAbsent(line, obj("node", nodeRef(method), "code", obj("type", "INSN", "index", offset.getOffset())));
        }
        return targets;
    }

    List<String> commentRegions(ICodeInfo code, JsonObject target) {
        List<String> text = lines(code.getCodeStr());
        SortedMap<Integer, JsonObject> targets = lineTargets(code);
        List<Integer> positions = new ArrayList<>(targets.keySet());
        List<String> regions = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            int line = positions.get(i);
            if (!targets.get(line).equals(target)) continue;
            int start = target.has("code") ? line - 1 : i == 0 ? 0 : positions.get(i - 1);
            int end = target.has("code") ? i + 1 < positions.size() ? positions.get(i + 1) - 1 : text.size() : line;
            regions.add(String.join("\n", text.subList(start, end).stream().map(JadxWorker::strip).toList()));
        }
        if (regions.isEmpty()) throw new IllegalStateException("Comment target has no regenerated source metadata; rolled back");
        return regions;
    }

    static String renderedComment(String comment, String name) {
        CommentStyle style = CommentStyle.valueOf(name);
        String rendered = style.getStart() + String.join("\n" + style.getOnNewLine(), comment.split("\\r\\n|[\\n\\r\\u000b\\f\\u0085\\u2028\\u2029]", -1)) + style.getEnd();
        return String.join("\n", lines(rendered).stream().map(JadxWorker::strip).toList());
    }

    JsonObject addComment(JsonObject a) throws IOException {
        String comment = string(a, "comment"), style = string(a, "style", "LINE");
        if (!Set.of("LINE", "BLOCK", "BLOCK_CONDENSED", "JAVADOC", "JAVADOC_CONDENSED").contains(style))
            throw new IllegalArgumentException("Invalid comment style");
        String method = string(a, "method_name", null), signature = string(a, "method_signature", null), field = string(a, "field_name", null);
        Integer line = a.has("line") && !a.get("line").isJsonNull() ? integer(a, "line", 0) : null;
        if ((method != null ? 1 : 0) + (field != null ? 1 : 0) + (line != null ? 1 : 0) > 1)
            throw new IllegalArgumentException("Specify only one of method_name, field_name, or line");
        if (signature != null && !signature.isEmpty() && method == null) throw new IllegalArgumentException("method_signature requires method_name");
        if (comment.contains("*/")) throw new IllegalArgumentException("Comment must not contain a block-comment terminator");
        if (!comment.isEmpty() && strip(comment).isEmpty()) throw new IllegalArgumentException("Use an empty string to delete a comment");
        ClassNode cls = cls(string(a, "class_name"));
        JsonObject target;
        if (line != null) {
            var targets = lineTargets(source(cls));
            target = targets.get(line);
            if (target == null) throw new IllegalArgumentException("Line has no commentable metadata. Nearest lines: "
                    + targets.keySet().stream().sorted(Comparator.comparingLong(n -> Math.abs((long) n - line))).limit(8).toList());
        } else {
            ICodeNode node = method != null && !method.isEmpty() ? one(methods(cls.getRawName(), method, signature), "method")
                    : field != null && !field.isEmpty() ? field(cls.getRawName(), field) : cls;
            target = obj("node", nodeRef(node));
        }
        JsonObject previous = edits.getAsJsonArray("comments").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(e -> sameTarget(e, target)).findFirst().orElse(null);
        return edit("comments", extend(target.deepCopy(), "comment", comment, "style", style), () -> {
            ICodeInfo code = source(cls(string(target.getAsJsonObject("node"), "class")));
            List<String> regions = commentRegions(code, target);
            if (!comment.isEmpty()) {
                String expected = renderedComment(comment, style);
                if (regions.stream().noneMatch(r -> r.contains(expected))) throw new IllegalStateException("Comment did not render; rolled back");
                return expected;
            }
            if (previous != null && regions.stream().anyMatch(r -> r.contains(renderedComment(string(previous, "comment"), string(previous, "style")))))
                throw new IllegalStateException("Removed comment still renders; rolled back");
            return "Comment removed from regenerated source";
        });
    }

    // Explicit allowlists also reject misspelled arguments on the raw process interface.
    static void arguments(String operation, JsonObject a) {
        String names = switch (operation) {
            case "load" -> "inputs inventory";
            case "status", "get_package_tree", "get_android_manifest", "get_main_application_classes_names", "get_main_activity_class", "get_cache_stats", "clear_cache" -> "";
            case "get_all_classes", "get_all_resource_file_names", "get_strings", "get_main_application_classes_code" -> "offset count";
            case "get_class_source" -> "class_name with_line_numbers";
            case "get_methods_of_class", "get_fields_of_class", "get_smali_of_class", "list_comments" -> "class_name";
            case "get_method_by_name" -> "class_name method_name method_signature";
            case "search_method_by_name" -> "method_name";
            case "search_classes_by_keyword" -> "search_term package search_in offset count";
            case "get_resource_file" -> "resource_name";
            case "get_manifest_component" -> "component_type only_exported";
            case "get_xrefs_to_class" -> "class_name offset count";
            case "get_xrefs_to_method" -> "class_name method_name offset count";
            case "get_xrefs_to_field" -> "class_name field_name offset count";
            case "rename_class" -> "class_name new_name";
            case "rename_method" -> "method_name new_name class_name method_signature";
            case "rename_field" -> "class_name field_name new_name";
            case "rename_package" -> "old_package_name new_package_name";
            case "rename_variable" -> "class_name method_name variable_name new_name reg ssa";
            case "add_comment" -> "class_name comment method_name method_signature field_name line style";
            default -> throw new IllegalArgumentException("Unregistered operation: " + operation);
        };
        Set<String> allowed = Set.of(names.split(" "));
        for (String key : a.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unexpected argument: " + key);
        if (operation.equals("load")) for (String key : List.of("inputs", "inventory"))
            if (!a.has(key) || !a.get(key).isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
    }

    JsonObject dispatch(String operation, JsonObject a) throws Exception {
        int offset = integer(a, "offset", 0), count = integer(a, "count", operation.startsWith("get_xrefs_") || operation.equals("search_classes_by_keyword") ? 20 : 0);
        return switch (operation) {
            case "status" -> status();
            case "get_all_classes" -> page(classes.values().stream().map(JadxWorker::describe).toList(), offset, count);
            case "get_package_tree" -> {
                Map<String, Integer> counts = new LinkedHashMap<>();
                classes.values().forEach(c -> counts.merge(c.getPackage(), 1, Integer::sum));
                yield obj("total_classes", classes.size(), "total_packages", counts.size(), "packages",
                        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                .map(e -> obj("name", e.getKey(), "class_count", e.getValue())).toList());
            }
            case "get_class_source" -> classSource(string(a, "class_name"), bool(a, "with_line_numbers", false));
            case "get_methods_of_class" -> obj("methods", cls(string(a, "class_name")).getMethods().stream().map(JadxWorker::describe).toList());
            case "get_fields_of_class" -> obj("fields", cls(string(a, "class_name")).getFields().stream().map(JadxWorker::describe).toList());
            case "get_smali_of_class" -> {
                ClassNode cls = cls(string(a, "class_name"));
                String smali = cls.getDisassembledCode();
                if (smali.isEmpty()) throw new IllegalStateException("No bytecode disassembly available");
                yield extend(describe(cls), "smali", smali);
            }
            case "get_method_by_name" -> {
                MethodNode method = one(methods(string(a, "class_name"), string(a, "method_name"), string(a, "method_signature", null)), "method");
                source(method.getParentClass());
                String text = method.getCodeStr();
                if (strip(text).isEmpty()) throw new IllegalStateException("Method has no rendered source (possibly inlined or suppressed); inspect class or smali");
                yield extend(describe(method), "code", text, "analysis_errors", sourceErrors(method.getParentClass()));
            }
            case "search_method_by_name" -> {
                String term = fold(string(a, "method_name"));
                yield obj("methods", classes.values().stream().flatMap(c -> c.getMethods().stream())
                        .filter(m -> fold(m.getAlias()).contains(term) || fold(m.getName()).contains(term)).map(JadxWorker::describe).toList());
            }
            case "search_classes_by_keyword" -> search(a, offset, count);
            case "get_all_resource_file_names" -> {
                loadTables();
                yield extend(page(resources.keySet().stream().sorted().toList(), offset, count), "limitations", resourceErrors);
            }
            case "get_resource_file" -> resourceFile(string(a, "resource_name"));
            case "get_strings" -> {
                loadTables();
                List<String> names = resources.keySet().stream().filter(n -> n.endsWith("/strings.xml")).toList();
                if (names.isEmpty()) throw new IllegalArgumentException("No strings.xml resources available; " + String.join("; ", resourceErrors));
                JsonObject result = page(names, offset, count);
                JsonArray items = new JsonArray();
                for (JsonElement name : result.getAsJsonArray("items")) items.add(resourceFile(name.getAsString()));
                yield extend(result, "items", items);
            }
            case "get_android_manifest" -> {
                JsonArray manifests = new JsonArray();
                for (String name : manifestNames()) manifests.add(resourceFile(name));
                yield obj("manifests", manifests);
            }
            case "get_manifest_component" -> components(string(a, "component_type"), bool(a, "only_exported", false));
            case "get_main_application_classes_names" -> obj("classes", applicationClasses().stream().map(JadxWorker::describe).toList());
            case "get_main_application_classes_code" -> {
                JsonObject result = page(applicationClasses().stream().map(ClassNode::getRawName).toList(), offset, count);
                JsonArray items = new JsonArray();
                for (JsonElement name : result.getAsJsonArray("items")) items.add(classSource(name.getAsString(), false));
                yield extend(result, "items", items);
            }
            case "get_main_activity_class" -> mainActivities();
            case "get_xrefs_to_class" -> xrefs(List.of(cls(string(a, "class_name"))), offset, count);
            case "get_xrefs_to_method" -> {
                List<MethodNode> methods = methods(string(a, "class_name"), string(a, "method_name"), null);
                if (methods.isEmpty()) throw new IllegalArgumentException("Method unavailable");
                yield xrefs(methods, offset, count);
            }
            case "get_xrefs_to_field" -> xrefs(List.of(field(string(a, "class_name"), string(a, "field_name"))), offset, count);
            case "get_cache_stats" -> cacheStats();
            case "clear_cache" -> { resetCache(); hits = misses = 0; yield cacheStats(); }
            case "rename_class" -> renameNode(a, "CLASS");
            case "rename_method" -> renameNode(a, "METHOD");
            case "rename_field" -> renameNode(a, "FIELD");
            case "rename_package" -> renamePackage(string(a, "old_package_name"), string(a, "new_package_name"));
            case "rename_variable" -> renameVariable(a);
            case "add_comment" -> addComment(a);
            case "list_comments" -> {
                String name = string(a, "class_name", ""), original = name.isEmpty() ? null : cls(name).getRawName();
                yield obj("comments", edits.getAsJsonArray("comments").asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(e -> original == null || string(e.getAsJsonObject("node"), "class").equals(original)).toList());
            }
            default -> throw new IllegalArgumentException("Unregistered operation: " + operation);
        };
    }

    static void checkApi() {
        if (!JadxDecompiler.getVersion().equals("1.5.6")) throw new IllegalStateException("This worker requires JADX 1.5.6");
        Set<String> plugins = new HashSet<>();
        for (JadxPlugin plugin : ServiceLoader.load(JadxPlugin.class)) plugins.add(plugin.getPluginInfo().getPluginId());
        if (!plugins.containsAll(Set.of("dex-input", "apkm-input", "xapk-input")))
            throw new IllegalStateException("Required JADX input plugins missing: " + plugins);
        // Resolve the GUI cache dependency as well as core, even when no job is loaded.
        if (!ICodeCache.class.isAssignableFrom(BufferCodeCache.class) || !ICodeCache.class.isAssignableFrom(DiskCodeCache.class))
            throw new IllegalStateException("JADX disk/buffer cache unavailable");
    }

    @Override public void close() { decompiler.close(); }

    public static void main(String[] argv) throws Exception {
        PrintStream output = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err); // Redirect before any JADX or logging classes initialize.
        checkApi();
        if (Arrays.equals(argv, new String[]{"--check-api"})) {
            System.err.println("JADX 1.5.6 library APIs and input plugins verified");
            return;
        }
        if (argv.length != 1) throw new IllegalArgumentException("Usage: JadxWorker <job-analysis-directory>");
        JadxWorker worker = null;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject response;
                try {
                    JsonObject request = parse(line);
                    String operation = string(request, "operation");
                    if (!request.has("arguments") || !request.get("arguments").isJsonObject())
                        throw new IllegalArgumentException("arguments must be an object");
                    JsonObject arguments = request.getAsJsonObject("arguments");
                    arguments(operation, arguments);
                    JsonObject result;
                    if (operation.equals("load") && worker == null) {
                        worker = new JadxWorker(Path.of(argv[0]), arguments);
                        result = worker.status();
                    } else if (worker == null) throw new IllegalStateException("JADX not loaded");
                    else result = worker.dispatch(operation, arguments);
                    response = obj("result", result);
                } catch (Exception ex) {
                    ex.printStackTrace(System.err);
                    response = obj("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
                output.println(JSON.toJson(response));
            }
        } finally { if (worker != null) worker.close(); }
    }
}
