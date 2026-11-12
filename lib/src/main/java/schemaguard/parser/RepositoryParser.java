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

    /** 이미 파싱된 AST에서 추출한다. JavaSourceScanner가 파일당 한 번 파싱한 결과를 공유할 때 사용. */
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
                String[] query = {null};
                boolean[] nativeQuery = {false};
                method.getAnnotationByName("EntityGraph").ifPresent(ann ->
                        fetchPaths.addAll(annotationStrings(ann, "attributePaths")));

                method.getAnnotationByName("Query").ifPresent(ann -> {
                    extractQueryString(ann).ifPresent(q -> {
                        query[0] = q;
                        nativeQuery[0] = isNativeQuery(ann);
                        if (nativeQuery[0]) info.addNativeQuery(q, methodLine);
                        else {
                            info.addJpqlQuery(q, methodLine);
                            fetchPaths.addAll(joinFetchPaths(q));
                        }
                    });
                });
                info.addMethod(new RepositoryInfo.MethodEntry(method.getNameAsString(), methodLine,
                        new ArrayList<>(fetchPaths), pessimisticLockMode(method), query[0], nativeQuery[0]));
            }
            result.add(info);
        });
        return result;
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /**
     * @Lock(LockModeType.PESSIMISTIC_WRITE), @Lock(value = PESSIMISTIC_READ) (static import) 등의 모드 이름.
     * 비관적 락(PESSIMISTIC_*)이 아니면 null. OPTIMISTIC 계열은 SELECT 에 잠금을 걸지 않는다.
     */
    static String pessimisticLockMode(MethodDeclaration method) {
        return method.getAnnotationByName("Lock").map(ann -> {
            String v = null;
            if (ann instanceof SingleMemberAnnotationExpr s) v = s.getMemberValue().toString();
            else if (ann instanceof NormalAnnotationExpr n)
                v = n.getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                        .findFirst().map(p -> p.getValue().toString()).orElse(null);
            if (v == null) return null;
            String mode = v.substring(v.lastIndexOf('.') + 1).trim();
            return mode.startsWith("PESSIMISTIC_") ? mode : null;
        }).orElse(null);
    }

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
            return Optional.of(queryText(s.getMemberValue()));
        if (ann instanceof NormalAnnotationExpr n)
            return n.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst()
                    .map(p -> queryText(p.getValue()));
        return Optional.empty();
    }

    /**
     * 쿼리 문자열 값. "a " + "b" 처럼 이어 붙인 문자열과 텍스트 블록("""...""")도 받는다.
     * 문자열 리터럴이 아닌 부분(상수 참조 등)이 섞이면 예전처럼 소스 표현을 그대로 쓴다.
     */
    static String queryText(com.github.javaparser.ast.expr.Expression value) {
        if (value.isStringLiteralExpr()) return value.asStringLiteralExpr().asString().replaceAll("\\s+", " ").trim();
        if (value.isTextBlockLiteralExpr()) return value.asTextBlockLiteralExpr().asString().replaceAll("\\s+", " ").trim();
        if (value instanceof com.github.javaparser.ast.expr.BinaryExpr b
                && b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.PLUS) {
            List<String> parts = new ArrayList<>();
            boolean allLiterals = b.findAll(com.github.javaparser.ast.expr.Expression.class, e -> e.isNameExpr() || e.isFieldAccessExpr()
                    || e.isMethodCallExpr()).isEmpty();
            if (allLiterals) {
                b.walk(com.github.javaparser.ast.Node.TreeTraversal.PREORDER, node -> {
                    if (node instanceof com.github.javaparser.ast.expr.StringLiteralExpr sl) parts.add(sl.asString());
                    else if (node instanceof com.github.javaparser.ast.expr.TextBlockLiteralExpr tb) parts.add(tb.asString());
                });
                return String.join("", parts).replaceAll("\\s+", " ").trim();
            }
        }
        return stripQuotes(value.toString());
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

    private static String stripQuotes(String s) {
        return s.replaceAll("^\"|\"$", "").replaceAll("\\\\n", " ").trim();
    }
}