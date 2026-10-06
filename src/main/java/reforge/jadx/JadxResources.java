package reforge.jadx;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import jadx.api.*;
import jadx.core.dex.nodes.*;
import jadx.core.xmlgen.ResContainer;

/** Android resources and manifest discovery for the loaded job. */
final class JadxResources {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final JadxSession session;

    JadxResources(JadxSession session) { this.session = session; }

    void loadTables() {
        if (session.tablesLoaded) return;
        for (var entry : new LinkedHashMap<>(session.resources).entrySet()) {
            if (!(entry.getValue() instanceof ResourceFile resource) || resource.getType() != ResourceType.ARSC) continue;
            try {
                ResContainer container = resource.loadContent();
                if (container == null || container.getDataType() != ResContainer.DataType.RES_TABLE)
                    throw new IllegalStateException("JADX failed to decode resource table; see worker.log");
                Deque<ResContainer> pending = new ArrayDeque<>(container.getSubFiles());
                while (!pending.isEmpty()) {
                    ResContainer child = pending.removeLast();
                    String name = child.getName();
                    session.resources.put(session.resources.containsKey(name) ? entry.getKey() + ":" + name : name, child);
                    pending.addAll(child.getSubFiles());
                }
            } catch (Exception ex) { session.resourceErrors.add(entry.getKey() + ": " + ex.getMessage()); }
        }
        session.tablesLoaded = true;
    }

    JsonObject resourceFile(String name) throws IOException {
        loadTables();
        Object resource = session.resources.get(name);
        if (resource == null) throw new IllegalArgumentException("Resource unavailable: " + name);
        ResContainer content = resource instanceof ResourceFile f ? f.loadContent() : (ResContainer) resource;
        if (content == null) throw new IllegalStateException("JADX could not decode resource");
        byte[] raw;
        switch (content.getDataType()) {
            case TEXT, RES_TABLE -> {
                String text = content.getText().getCodeStr();
                if (text.startsWith("Error decode ")) throw new IllegalStateException("JADX resource decoding failed; see worker.log");
                return WorkerJson.obj("name", name, "encoding", "text", "content", text);
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
            return WorkerJson.obj("name", name, "encoding", "text", "content", text);
        } catch (CharacterCodingException ex) { return WorkerJson.obj("name", name, "encoding", "base64", "content", Base64.getEncoder().encodeToString(raw)); }
    }

    List<String> manifestNames() {
        List<String> names = session.resources.keySet().stream().filter(n -> n.endsWith("AndroidManifest.xml")).toList();
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
            String text = WorkerJson.string(resourceFile(name), "content");
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
                    result.add(WorkerJson.obj("manifest", manifest.getKey(), "exported", exported, "exported_explicit", explicit, "xml", xml.toString()));
                }
            }
        }
        return WorkerJson.obj("components", result);
    }

    List<ClassNode> applicationClasses() throws Exception {
        Set<String> packages = new HashSet<>();
        for (Element manifest : manifests().values()) if (!manifest.getAttribute("package").isEmpty()) packages.add(manifest.getAttribute("package"));
        return session.classes.values().stream().filter(c -> packages.stream().anyMatch(p -> JadxSources.inPackage(c.getClassInfo().getPackage(), p))).toList();
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
                    result.add(WorkerJson.obj("manifest", manifest.getKey(), "original_id", name, "available", session.classes.containsKey(name)));
                }
            }
        }
        return WorkerJson.obj("activities", result);
    }
}
