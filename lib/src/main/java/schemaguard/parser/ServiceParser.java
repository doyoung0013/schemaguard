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
            boolean service = isService(clazz);
            // @Service 가 아닌 일반 클래스(DTO, 매퍼 등)는 static 메서드만 모은다.
            // 예) ReportResponse.from(report) 안의 report.getPost().getTitle() 도 연관 로딩이다.
            if (!service && !isHelperCandidate(clazz)) return;

            int classLine = clazz.getRange().map(r -> r.begin.line).orElse(-1);
            ServiceInfo info = new ServiceInfo(clazz.getNameAsString(), filePath, classLine, !service);
            Map<String, String> fieldTypeMap = buildFieldTypeMap(clazz);

            for (MethodDeclaration method : clazz.getMethods()) {
                if (!service && !method.isStatic()) continue;
                int methodLine = method.getRange().map(r -> r.begin.line).orElse(-1);
                String callerMethod = method.getNameAsString();

                // 연관 초기화: x.getPost().getTitle() 처럼 getter 결과에 다시 getId() 가 아닌 호출을 하면
                // 지연 로딩 프록시가 초기화되어 연관 엔티티의 컬럼을 전부 SELECT 한다.
                AccessCollector.getterChains(method, (attr, line) ->
                        info.addAssociationAccess(callerMethod, methodLine, attr, line));
                AccessCollector.entityParameterAccesses(method, (type, line) ->
                        info.addEntityTypeAccess(callerMethod, methodLine, type, line));
                AccessCollector.staticCalls(method, (type, name, line) -> {
                    if (!type.equals(clazz.getNameAsString()) || !name.equals(callerMethod))
                        info.addStaticCall(callerMethod, methodLine, type, name, line);
                });

                if (!service) continue;
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
            if (service || !info.getAssociationAccesses().isEmpty() || !info.getEntityTypeAccesses().isEmpty()
                    || !info.getStaticCalls().isEmpty()) {
                result.add(info);
            }
        });
        return result;
    }

    /** DTO·매퍼 후보: 인터페이스/엔티티/컨트롤러/설정이 아니고 static 메서드가 있는 클래스. */
    private boolean isHelperCandidate(ClassOrInterfaceDeclaration clazz) {
        if (clazz.isInterface()) return false;
        for (String a : List.of("Entity", "Embeddable", "MappedSuperclass", "Controller", "RestController",
                "Configuration", "Repository", "Component", "SpringBootApplication")) {
            if (clazz.getAnnotationByName(a).isPresent()) return false;
        }
        return clazz.getMethods().stream().anyMatch(MethodDeclaration::isStatic);
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