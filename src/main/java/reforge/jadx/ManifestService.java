package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import java.io.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Manifest parsing, readiness, components, and application-class discovery. */
final class ManifestService implements JadxServices.Manifests {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final JadxServices.Resources resources;
    private final JadxServices.Classes classes;
    private final JadxServices.Decompiler decompiler;
    ManifestService(JadxServices.Resources resources, JadxServices.Classes classes, JadxServices.Decompiler decompiler) {
        this.resources = resources;
        this.classes = classes;
        this.decompiler = decompiler;
    }

    @Override public String readinessError() {
        try {
            boolean base = manifests().values().stream().anyMatch(manifest ->
                manifest.getTagName().equals("manifest") && manifest.getAttribute("split").isEmpty()
                && !manifest.getAttribute("package").isBlank());
            if (!base) throw new IllegalArgumentException("No readable base AndroidManifest.xml with a package ID");
            return null;
        } catch (Exception ex) { return "Android manifest unavailable: " + ex.getMessage(); }
    }

    @Override public Manifests allManifests() throws IOException {
        List<ResourceContent> result = new ArrayList<>();
        for (String name : manifestNames()) result.add(resources.resourceFile(name));
        return new Manifests(result);
    }
    @Override public Classes applicationClassNames() throws Exception { return new Classes(applicationClasses()); }
    @Override public Page<ClassSource> applicationClassCode(PageRequest page) throws Exception {
        Page<String> selected = page.select(applicationClasses().stream().map(Identity::originalId).toList());
        List<ClassSource> items = new ArrayList<>();
        for (String name : selected.items()) items.add(decompiler.classSource(name, false));
        return selected.withItems(items);
    }

    private List<String> manifestNames() {
        List<String> names = resources.names().stream().filter(n -> n.endsWith("AndroidManifest.xml")).toList();
        if (names.isEmpty()) throw new IllegalArgumentException("No AndroidManifest.xml available");
        return names;
    }

    private Map<String, Element> manifests() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Map<String, Element> result = new LinkedHashMap<>();
        for (String name : manifestNames()) {
            String text = resources.resourceFile(name).content();
            result.put(name, factory.newDocumentBuilder().parse(new InputSource(new StringReader(text))).getDocumentElement());
        }
        return result;
    }

    private static List<Element> elements(Element parent, String tag, boolean direct) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = direct ? parent.getChildNodes() : parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element e && e.getTagName().equals(tag)) result.add(e);
        return result;
    }

    @Override public Components components(String type, boolean onlyExported) throws Exception {
        if (!Set.of("activity", "activity-alias", "application", "service", "receiver", "provider").contains(type))
            throw new IllegalArgumentException("Unsupported manifest component type");
        List<Component> result = new ArrayList<>();
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
                    result.add(new Component(manifest.getKey(), exported, explicit, xml.toString()));
                }
            }
        }
        return new Components(result);
    }

    private List<Identity> applicationClasses() throws Exception {
        Set<String> packages = new HashSet<>();
        for (Element manifest : manifests().values()) if (!manifest.getAttribute("package").isEmpty()) packages.add(manifest.getAttribute("package"));
        return classes.inPackages(packages);
    }

    @Override public Activities mainActivities() throws Exception {
        List<Activity> result = new ArrayList<>();
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
                    result.add(new Activity(manifest.getKey(), name, classes.hasOriginalClass(name)));
                }
            }
        }
        return new Activities(result);
    }
}
