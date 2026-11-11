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

    /** 이미 파싱된 AST에서 추출한다. SourceScanner가 파일당 한 번 파싱한 결과를 공유할 때 사용. */
    public List<RepositoryInfo> parse(CompilationUnit cu, String filePath) {
        return extract(cu, filePath);
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

                // 이 메서드가 함께 로딩하는 연관 경로
                Set<String> fetchPaths = new LinkedHashSet<>();
                method.getAnnotationByName("EntityGraph").ifPresent(ann ->
                        fetchPaths.addAll(annotationStrings(ann, "attributePaths")));

                method.getAnnotationByName("Query").ifPresent(ann -> {
                    extractQueryString(ann).ifPresent(query -> {
                        if (isNativeQuery(ann)) info.addNativeQuery(query, methodLine);
                        else {
                            info.addJpqlQuery(query, methodLine);
                            fetchPaths.addAll(joinFetchPaths(query));
                        }
                    });
                });
                info.addMethod(method.getNameAsString(), methodLine, new ArrayList<>(fetchPaths));
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

    /** @Ann(attr = {"a", "b"}) 또는 @Ann(attr = "a") 의 문자열 값들. */
    private List<String> annotationStrings(AnnotationExpr ann, String attr) {
        List<String> out = new ArrayList<>();
        if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (!p.getNameAsString().equals(attr)) continue;
                p.getValue().findAll(com.github.javaparser.ast.expr.StringLiteralExpr.class)
                        .forEach(s -> out.add(s.getValue().trim()));
                if (out.isEmpty() && p.getValue().isStringLiteralExpr())
                    out.add(p.getValue().asStringLiteralExpr().getValue().trim());
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern FROM_ALIAS = java.util.regex.Pattern.compile(
            "(?i)\\bfrom\\s+\\w+(?:\\s+as)?\\s+(\\w+)");
    private static final java.util.regex.Pattern JOIN_FETCH = java.util.regex.Pattern.compile(
            "(?i)\\bjoin\\s+fetch\\s+(\\w+)\\.(\\w+)(?:\\s+(?:as\\s+)?(?!join\\b|left\\b|inner\\b|where\\b|order\\b|group\\b)(\\w+))?");

    /**
     * JPQL 의 join fetch 를 루트 엔티티 기준 경로로 바꾼다.
     *   select r from Report r join fetch r.post p join fetch p.author  →  ["post", "post.author"]
     */
    static List<String> joinFetchPaths(String jpql) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher from = FROM_ALIAS.matcher(jpql);
        if (!from.find()) return out;
        Map<String, String> pathOfAlias = new HashMap<>();
        pathOfAlias.put(from.group(1), "");
        java.util.regex.Matcher m = JOIN_FETCH.matcher(jpql);
        while (m.find()) {
            String base = pathOfAlias.get(m.group(1));
            if (base == null) continue;
            String path = base.isEmpty() ? m.group(2) : base + "." + m.group(2);
            out.add(path);
            if (m.group(3) != null) pathOfAlias.put(m.group(3), path);
        }
        return out;
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