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

    /** 이미 파싱된 AST에서 추출한다. JavaSourceScanner가 파일당 한 번 파싱한 결과를 공유할 때 사용. */
    public List<ControllerInfo> parse(CompilationUnit cu, String filePath) {
        return extract(cu, filePath);
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

                    // 메서드 안의 서비스 호출을 전부 모은다 (같은 서비스 메서드는 한 번만).
                    // 이전에는 첫 호출만 연결해서, 두 번째 이후 서비스가 깨져도 API가 누락되었다.
                    Map<String, ControllerInfo.ServiceCall> calls = new LinkedHashMap<>();
                    for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                        if (call.getScope().isEmpty()) continue;
                        String type = ftm.get(call.getScope().get().toString());
                        if (type == null) continue;
                        int line = call.getRange().map(r -> r.begin.line).orElse(methodLine);
                        calls.putIfAbsent(type + "." + call.getNameAsString(),
                                new ControllerInfo.ServiceCall(type, call.getNameAsString(), line));
                    }
                    // DTO 변환 등 static 호출 (UserResponse.from(user), Type::method). 서비스 호출과 따로 둔다.
                    Map<String, ControllerInfo.ServiceCall> statics = new LinkedHashMap<>();
                    AccessCollector.staticCalls(method, (type, name, line) ->
                            statics.putIfAbsent(type + "." + name, new ControllerInfo.ServiceCall(type, name, line)));
                    info.addEndpoint(httpMethod, fullPath, method.getNameAsString(), methodLine,
                            new ArrayList<>(calls.values()), new ArrayList<>(statics.values()));
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