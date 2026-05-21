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