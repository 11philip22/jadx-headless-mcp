package reforge.jadx;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

/** JSON codecs and text normalization shared by the worker boundaries. */
final class WorkerJson {
    static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final Pattern NEWLINE = Pattern.compile("\\r\\n|[\\n\\r\\u000b\\f\\u001c-\\u001e\\u0085\\u2028\\u2029]");

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
}
