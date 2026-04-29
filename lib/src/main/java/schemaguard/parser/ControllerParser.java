package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import schemaguard.model.ControllerInfo;

import java.io.File;
import java.util.*;

/**
 * @Controller / @RestController 클래스를 파싱하여 ControllerInfo를 추출한다.
 *
 * 추출 항목:
 *   - @RequestMapping 클래스 레벨 prefix path
 *   - @GetMapping / @PostMapping / @PutMapping / @DeleteMapping / @PatchMapping 메서드
 *   - 각 Controller 메서드 안에서 호출하는 Service 메서드
 */
public class ControllerParser {

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "RequestMapping"
    );

    private static final Map<String, String> ANNOTATION_TO_HTTP = Map.of(
            "GetMapping",    "GET",
            "PostMapping",   "POST",
            "PutMapping",    "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping",  "PATCH",
            "RequestMapping","GET"   // RequestMapping 기본값
    );

    public List<ControllerInfo> parse(File javaFile) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        return extract(cu);
    }

    public List<ControllerInfo> parseSource(String sourceCode) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        return extract(cu);
    }

    private List<ControllerInfo> extract(CompilationUnit cu) {
        List<ControllerInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (!isController(clazz)) return;

            ControllerInfo info = new ControllerInfo(clazz.getNameAsString());

            // 클래스 레벨 @RequestMapping prefix 추출
            String classPrefix = extractClassPrefix(clazz);

            // 필드명 → 타입명 맵  ex. "userService" → "UserService"
            Map<String, String> fieldTypeMap = buildFieldTypeMap(clazz);

            for (MethodDeclaration method : clazz.getMethods()) {
                for (AnnotationExpr ann : method.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    if (!MAPPING_ANNOTATIONS.contains(annName)) continue;

                    String httpMethod = ANNOTATION_TO_HTTP.getOrDefault(annName, "GET");
                    String methodPath = extractPath(ann);
                    String fullPath   = normalizePath(classPrefix + methodPath);

                    // 이 Controller 메서드가 호출하는 첫 번째 Service 메서드 탐색
                    String calleeServiceClass  = null;
                    String calleeServiceMethod = null;

                    for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                        if (call.getScope().isPresent()) {
                            String scopeName = call.getScope().get().toString();
                            String calleeType = fieldTypeMap.get(scopeName);
                            if (calleeType != null) {
                                calleeServiceClass  = calleeType;
                                calleeServiceMethod = call.getNameAsString();
                                break; // 첫 번째 호출만 등록
                            }
                        }
                    }

                    info.addEndpoint(httpMethod, fullPath,
                            method.getNameAsString(),
                            calleeServiceClass, calleeServiceMethod);
                }
            }

            result.add(info);
        });

        return result;
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private boolean isController(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Controller")
                            || a.getNameAsString().equals("RestController"));
    }

    private String extractClassPrefix(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotationByName("RequestMapping")
                .map(this::extractPath)
                .orElse("");
    }

    private String extractPath(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return stripQuotes(single.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            // value 또는 path 속성
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value")
                        || pair.getNameAsString().equals("path")) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    private Map<String, String> buildFieldTypeMap(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> map = new HashMap<>();
        for (FieldDeclaration field : clazz.getFields()) {
            String typeName = field.getElementType().toString();
            field.getVariables().forEach(var ->
                    map.put(var.getNameAsString(), typeName));
        }
        return map;
    }

    private String normalizePath(String path) {
        // 중복 슬래시 제거, 앞에 / 보장
        String normalized = path.replaceAll("/+", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        return normalized;
    }

    private String stripQuotes(String s) {
        return s.replaceAll("^\"|\"$", "");
    }
}