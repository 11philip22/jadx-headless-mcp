package reforge.jadx;

import java.io.IOException;
import java.util.*;
import jadx.api.ICodeInfo;
import jadx.core.dex.nodes.ClassNode;
import static reforge.jadx.JadxModels.*;

/** Explicit Java contracts for tool operations and the allowed service calls. */
final class JadxServices {
    interface Classes {
        Page<Identity> allClasses(PageRequest page);
        PackageTree packageTree();
        Methods methods(String name);
        Fields fields(String name);
        Smali smali(String name);
        List<Identity> inPackages(Set<String> packages);
        boolean hasOriginalClass(String name);
    }
    interface Decompiler {
        ClassSource classSource(String name, boolean numbered);
        MethodSource methodSource(MethodRequest request);
        ICodeInfo source(ClassNode cls);
        List<String> sourceErrors(ClassNode cls);
        List<Variable> variables(ICodeInfo code);
        SortedMap<Integer, Target> lineTargets(ICodeInfo code);
        CacheStats cacheStats() throws IOException;
        CacheStats clearCache() throws IOException;
        void invalidateCache() throws IOException;
    }
    interface Search {
        Methods methodsByName(String name);
        SearchResult search(SearchRequest request);
        References classReferences(String name, PageRequest page);
        References fieldReferences(String className, String fieldName, PageRequest page);
        References methodReferences(String className, String methodName, PageRequest page);
    }
    interface Resources {
        void loadTables();
        List<String> names();
        ResourceNames allNames(PageRequest page);
        ResourceContent resourceFile(String name) throws IOException;
        Page<ResourceContent> strings(PageRequest page) throws IOException;
    }
    interface Manifests {
        String readinessError();
        JadxModels.Manifests allManifests() throws IOException;
        Components components(String type, boolean onlyExported) throws Exception;
        JadxModels.Classes applicationClassNames() throws Exception;
        Page<ClassSource> applicationClassCode(PageRequest page) throws Exception;
        Activities mainActivities() throws Exception;
    }
    interface Edits {
        EditResult<String> renameClass(String newName, String className) throws IOException;
        EditResult<String> renameMethod(String newName, MethodRequest request) throws IOException;
        EditResult<String> renameField(String newName, String className, String fieldName) throws IOException;
        EditResult<List<String>> renamePackage(String oldName, String newName) throws IOException;
        VariableTarget variableTarget(VariableRename request);
        EditResult<Variable> renameVariable(VariableTarget request, VariableSelection selection) throws IOException;
        EditResult<String> addComment(CommentRequest request, String className) throws IOException;
        Comments listComments(String name);
    }
}
