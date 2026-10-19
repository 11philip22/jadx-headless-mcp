package reforge.jadx;

import com.google.gson.*;
import java.util.Set;
import static reforge.jadx.JadxModels.*;

/** Decode in call order, including validations that precede later arguments. */
final class JadxArguments {
    private final JsonObject values;
    private final PageRequest page;

    JadxArguments(JsonObject values) {
        this.values = values;
        page = new PageRequest(WorkerJson.integer(values, "offset", 0), WorkerJson.integer(values, "count", 0));
    }

    String string(String name) { return WorkerJson.string(values, name); }
    String string(String name, String fallback) { return WorkerJson.string(values, name, fallback); }
    boolean bool(String name, boolean fallback) { return WorkerJson.bool(values, name, fallback); }
    PageRequest page() { return page; }
    MethodRequest method(boolean optionalClass) {
        return new MethodRequest(optionalClass ? string("class_name", null) : string("class_name"),
                string("method_name"), string("method_signature", null));
    }
    String renameName() { return validName(string("new_name"), false); }
    VariableRename variableRename() {
        String name = string("new_name"), variable = string("variable_name");
        validName(name, false);
        return new VariableRename(name, variable, new MethodRequest(string("class_name"), string("method_name"), null));
    }
    VariableSelection variableSelection() { return new VariableSelection(string("reg", null), string("ssa", null)); }
    CommentRequest comment() {
        String comment = string("comment"), style = validStyle(string("style", "LINE"));
        String method = string("method_name", null), signature = string("method_signature", null), field = string("field_name", null);
        Integer line = values.has("line") && !values.get("line").isJsonNull() ? WorkerJson.integer(values, "line", 0) : null;
        return new CommentRequest(comment, style, method, signature, field, line);
    }
    SearchRequest search() {
        Set<String> scopes = searchScopes(string("search_in", "code"));
        page.validate();
        return new SearchRequest(scopes, page, WorkerJson.fold(string("search_term")), string("package", ""));
    }
}
