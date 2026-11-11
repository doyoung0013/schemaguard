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

public class ServiceParser {

    public List<ServiceInfo> parse(File javaFile) throws Exception {
        return extract(StaticJavaParser.parse(javaFile), javaFile.getAbsolutePath());
    }

    /** 이미 파싱된 AST에서 추출한다. SourceScanner가 파일당 한 번 파싱한 결과를 공유할 때 사용. */
    public List<ServiceInfo> parse(CompilationUnit cu, String filePath) {
        return extract(cu, filePath);
    }

    public List<ServiceInfo> parseSource(String sourceCode) throws Exception {
        return extract(StaticJavaParser.parse(sourceCode), null);
    }

    private List<ServiceInfo> extract(CompilationUnit cu, String filePath) {
        List<ServiceInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (!isService(clazz)) return;

            int classLine = clazz.getRange().map(r -> r.begin.line).orElse(-1);
            ServiceInfo info = new ServiceInfo(clazz.getNameAsString(), filePath, classLine);
            Map<String, String> fieldTypeMap = buildFieldTypeMap(clazz);

            for (MethodDeclaration method : clazz.getMethods()) {
                int methodLine = method.getRange().map(r -> r.begin.line).orElse(-1);
                String callerMethod = method.getNameAsString();

                // 연관 초기화: x.getPost().getTitle() 처럼 getter 결과에 다시 getId() 가 아닌 호출을 하면
                // 지연 로딩 프록시가 초기화되어 연관 엔티티의 컬럼을 전부 SELECT 한다.
                method.findAll(MethodCallExpr.class).forEach(call -> {
                    String name = call.getNameAsString();
                    if (!isGetter(call) || call.getScope().isEmpty()) return;
                    call.getParentNode()
                            .filter(p -> p instanceof MethodCallExpr)
                            .map(p -> (MethodCallExpr) p)
                            .filter(outer -> outer.getScope().map(s -> s == call).orElse(false))
                            .filter(outer -> !outer.getNameAsString().equals("getId"))
                            .ifPresent(outer -> info.addAssociationAccess(callerMethod, methodLine,
                                    decapitalize(name.substring(3)),
                                    call.getRange().map(r -> r.begin.line).orElse(methodLine)));
                });

                method.findAll(MethodCallExpr.class).forEach(call ->
                        call.getScope().ifPresent(scope -> {
                            String calleeType = fieldTypeMap.get(scope.toString());
                            if (calleeType != null) {
                                // 호출 위치 라인
                                int callLine = call.getRange().map(r -> r.begin.line).orElse(methodLine);
                                info.addMethodCall(callerMethod, methodLine,
                                        calleeType, call.getNameAsString(), callLine);
                            }
                        }));
            }
            result.add(info);
        });
        return result;
    }

    private static boolean isGetter(MethodCallExpr call) {
        String n = call.getNameAsString();
        return n.length() > 3 && n.startsWith("get") && Character.isUpperCase(n.charAt(3))
                && call.getArguments().isEmpty();
    }

    private static String decapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private boolean isService(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Service"));
    }

    private Map<String, String> buildFieldTypeMap(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> map = new HashMap<>();
        for (FieldDeclaration field : clazz.getFields()) {
            String typeName = field.getElementType().toString();
            field.getVariables().forEach(var -> map.put(var.getNameAsString(), typeName));
        }
        return map;
    }
}