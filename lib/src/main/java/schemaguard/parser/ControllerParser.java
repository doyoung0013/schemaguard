package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import schemaguard.model.ControllerInfo;

import java.io.File;
import java.util.*;

public class ControllerParser {

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "RequestMapping");

    private static final Map<String, String> ANNOTATION_TO_HTTP = Map.of(
            "GetMapping",    "GET",
            "PostMapping",   "POST",
            "PutMapping",    "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping",  "PATCH",
            "RequestMapping","GET");

    public List<ControllerInfo> parse(File javaFile) throws Exception {
        return extract(StaticJavaParser.parse(javaFile), javaFile.getAbsolutePath());
    }

    public List<ControllerInfo> parseSource(String sourceCode) throws Exception {
        return extract(StaticJavaParser.parse(sourceCode), null);
    }

    private List<ControllerInfo> extract(CompilationUnit cu, String filePath) {
        List<ControllerInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (!isController(clazz)) return;

            int classLine = clazz.getRange().map(r -> r.begin.line).orElse(-1);
            ControllerInfo info = new ControllerInfo(clazz.getNameAsString(), filePath, classLine);
            String prefix = extractClassPrefix(clazz);
            Map<String, String> ftm = buildFieldTypeMap(clazz);

            for (MethodDeclaration method : clazz.getMethods()) {
                int methodLine = method.getRange().map(r -> r.begin.line).orElse(-1);

                for (AnnotationExpr ann : method.getAnnotations()) {
                    if (!MAPPING_ANNOTATIONS.contains(ann.getNameAsString())) continue;

                    String httpMethod = ANNOTATION_TO_HTTP.getOrDefault(ann.getNameAsString(), "GET");
                    String fullPath   = normalizePath(prefix + extractPath(ann));

                    String svcClass = null, svcMethod = null;
                    int    svcLine  = -1;
                    for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                        if (call.getScope().isPresent()) {
                            String type = ftm.get(call.getScope().get().toString());
                            if (type != null) {
                                svcClass  = type;
                                svcMethod = call.getNameAsString();
                                svcLine   = call.getRange().map(r -> r.begin.line).orElse(methodLine);
                                break;
                            }
                        }
                    }
                    info.addEndpoint(httpMethod, fullPath, method.getNameAsString(), methodLine,
                            svcClass, svcMethod, svcLine);
                }
            }
            result.add(info);
        });
        return result;
    }

    private boolean isController(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Controller")
                            || a.getNameAsString().equals("RestController"));
    }

    private String extractClassPrefix(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotationByName("RequestMapping").map(this::extractPath).orElse("");
    }

    private String extractPath(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s) return stripQuotes(s.getMemberValue().toString());
        if (ann instanceof NormalAnnotationExpr n)
            for (MemberValuePair p : n.getPairs())
                if (p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
                    return stripQuotes(p.getValue().toString());
        return "";
    }

    private Map<String, String> buildFieldTypeMap(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> map = new HashMap<>();
        for (FieldDeclaration f : clazz.getFields()) {
            String typeName = f.getElementType().toString();
            f.getVariables().forEach(var -> map.put(var.getNameAsString(), typeName));
        }
        return map;
    }

    private String normalizePath(String path) {
        String n = path.replaceAll("/+", "/");
        return n.startsWith("/") ? n : "/" + n;
    }

    private String stripQuotes(String s) { return s.replaceAll("^\"|\"$", ""); }
}