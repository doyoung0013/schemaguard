package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import schemaguard.model.ServiceInfo;

import java.io.File;
import java.util.*;

/**
 * @Service 클래스를 파싱하여 ServiceInfo를 추출한다.
 *
 * 추출 항목:
 *   - 각 메서드 안에서 호출하는 Repository(또는 Service) 메서드
 *
 * 동작 방식:
 *   1. 클래스 필드에서 의존성 주입된 타입을 수집 (ex. UserRepository userRepository)
 *   2. 각 메서드 바디에서 MethodCallExpr 을 탐색
 *   3. 호출 대상의 scope 가 위에서 수집한 필드명과 일치하면 호출 관계로 등록
 */
public class ServiceParser {

    public List<ServiceInfo> parse(File javaFile) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        return extract(cu);
    }

    public List<ServiceInfo> parseSource(String sourceCode) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        return extract(cu);
    }

    private List<ServiceInfo> extract(CompilationUnit cu) {
        List<ServiceInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (!isService(clazz)) return;

            ServiceInfo info = new ServiceInfo(clazz.getNameAsString());

            // 필드명 → 필드 타입 맵  ex. "userRepository" → "UserRepository"
            Map<String, String> fieldTypeMap = buildFieldTypeMap(clazz);

            for (MethodDeclaration method : clazz.getMethods()) {
                String callerMethod = method.getNameAsString();

                // 메서드 바디 안의 모든 메서드 호출 탐색
                method.findAll(MethodCallExpr.class).forEach(call -> {
                    call.getScope().ifPresent(scope -> {
                        String scopeName = scope.toString();
                        String calleeType = fieldTypeMap.get(scopeName);

                        if (calleeType != null) {
                            info.addMethodCall(callerMethod, calleeType, call.getNameAsString());
                        }
                    });
                });
            }

            result.add(info);
        });

        return result;
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private boolean isService(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Service"));
    }

    /**
     * 클래스 필드를 순회하여 { 필드변수명 → 타입명 } 맵을 구성한다.
     * ex. private UserRepository userRepository → "userRepository" → "UserRepository"
     */
    private Map<String, String> buildFieldTypeMap(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> map = new HashMap<>();
        for (FieldDeclaration field : clazz.getFields()) {
            String typeName = field.getElementType().toString();
            field.getVariables().forEach(var ->
                    map.put(var.getNameAsString(), typeName));
        }
        return map;
    }
}