package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import schemaguard.model.RepositoryInfo;

import java.io.File;
import java.util.*;

public class RepositoryParser {

    public List<RepositoryInfo> parse(File javaFile) throws Exception {
        return extract(StaticJavaParser.parse(javaFile), javaFile.getAbsolutePath());
    }

    public List<RepositoryInfo> parseSource(String sourceCode) throws Exception {
        return extract(StaticJavaParser.parse(sourceCode), null);
    }

    private List<RepositoryInfo> extract(CompilationUnit cu, String filePath) {
        List<RepositoryInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (!isRepository(clazz)) return;

            String entityClass = extractEntityClass(clazz).orElse("Unknown");

            // 인터페이스 자체 선언 라인
            int classLine = clazz.getRange().map(r -> r.begin.line).orElse(-1);
            RepositoryInfo info = new RepositoryInfo(
                    clazz.getNameAsString(), entityClass, filePath, classLine);

            for (MethodDeclaration method : clazz.getMethods()) {
                int methodLine = method.getRange().map(r -> r.begin.line).orElse(-1);
                info.addMethod(method.getNameAsString(), methodLine);

                method.getAnnotationByName("Query").ifPresent(ann -> {
                    extractQueryString(ann).ifPresent(query -> {
                        if (isNativeQuery(ann)) info.addNativeQuery(query, methodLine);
                        else                   info.addJpqlQuery(query, methodLine);
                    });
                });
            }
            result.add(info);
        });
        return result;
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private boolean isRepository(ClassOrInterfaceDeclaration clazz) {
        return clazz.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().contains("Repository"));
    }

    private Optional<String> extractEntityClass(ClassOrInterfaceDeclaration clazz) {
        return clazz.getExtendedTypes().stream()
                .filter(t -> t.getNameAsString().contains("Repository"))
                .findFirst()
                .flatMap(t -> t.getTypeArguments()
                        .filter(args -> !args.isEmpty())
                        .map(args -> args.get(0).toString()));
    }

    private Optional<String> extractQueryString(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s)
            return Optional.of(stripQuotes(s.getMemberValue().toString()));
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst()
                    .map(p -> stripQuotes(p.getValue().toString()));
        return Optional.empty();
    }

    private boolean isNativeQuery(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream()
                    .anyMatch(p -> p.getNameAsString().equals("nativeQuery")
                            && p.getValue().toString().equals("true"));
        return false;
    }

    private String stripQuotes(String s) {
        return s.replaceAll("^\"|\"$", "").replaceAll("\\\\n", " ").trim();
    }
}