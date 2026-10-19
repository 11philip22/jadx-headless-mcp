package reforge.jadx;

import java.util.*;
import static reforge.jadx.JadxModels.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import jadx.api.*;
import jadx.core.xmlgen.ResContainer;

/** Resource decoding and inventory operations, preserving split provenance. */
final class ResourceService implements JadxServices.Resources {
    private final JadxSession session;
    ResourceService(JadxSession session) { this.session = session; }

    @Override public void loadTables() {
        if (session.tablesLoaded()) return;
        for (var entry : new LinkedHashMap<>(session.resources()).entrySet()) {
            if (!(entry.getValue() instanceof ResourceFile resource) || resource.getType() != ResourceType.ARSC) continue;
            try {
                ResContainer container = resource.loadContent();
                if (container == null || container.getDataType() != ResContainer.DataType.RES_TABLE)
                    throw new IllegalStateException("JADX failed to decode resource table; see worker.log");
                Deque<ResContainer> pending = new ArrayDeque<>(container.getSubFiles());
                while (!pending.isEmpty()) {
                    ResContainer child = pending.removeLast();
                    String name = child.getName();
                    session.addResource(entry.getKey(), name, child);
                    pending.addAll(child.getSubFiles());
                }
            } catch (Exception ex) { session.resourceError(entry.getKey() + ": " + ex.getMessage()); }
        }
        session.finishResourceTables();
    }

    @Override public ResourceContent resourceFile(String name) throws IOException {
    @Override public     loadTables();
        Object resource = session.resources().get(name);
        if (resource == null) throw new IllegalArgumentException("Resource unavailable: " + name);
        ResContainer content = resource instanceof ResourceFile f ? f.loadContent() : (ResContainer) resource;
        if (content == null) throw new IllegalStateException("JADX could not decode resource");
        byte[] raw;
        switch (content.getDataType()) {
            case TEXT, RES_TABLE -> {
                String text = content.getText().getCodeStr();
                if (text.startsWith("Error decode ")) throw new IllegalStateException("JADX resource decoding failed; see worker.log");
                return new ResourceContent(name, "text", text);
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
            return new ResourceContent(name, "text", text);
        } catch (CharacterCodingException ex) { return new ResourceContent(name, "base64", Base64.getEncoder().encodeToString(raw)); }
    }

    @Override public List<String> names() { return List.copyOf(session.resources().keySet()); }
    @Override public ResourceNames allNames(PageRequest page) {
    @Override public     loadTables();
    @Override public     return new ResourceNames(page.select(names().stream().sorted().toList()), session.resourceErrors());
    }
    @Override public Page<ResourceContent> strings(PageRequest page) throws IOException {
    @Override public     loadTables();
        List<String> names = names().stream().filter(n -> n.endsWith("/strings.xml")).toList();
        if (names.isEmpty()) throw new IllegalArgumentException("No strings.xml resources available; " + String.join("; ", session.resourceErrors()));
        Page<String> selected = page.select(names);
        List<ResourceContent> items = new ArrayList<>();
    @Override public     for (String name : selected.items()) items.add(resourceFile(name));
        return selected.withItems(items);
    }
}
