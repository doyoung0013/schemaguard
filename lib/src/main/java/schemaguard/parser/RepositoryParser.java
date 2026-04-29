package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import schemaguard.model.RepositoryInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository 인터페이스를 파싱하여 RepositoryInfo를 추출한다.
 *
 * 추출 항목:
 *   - JpaRepository<EntityClass, ID> 에서 대상 Entity 클래스명
 *   - 선언된 메서드명 (findByEmail, findByPostId 등)
 *   - @Query 어노테이션의 JPQL / Native SQL 문자열
 */
public class RepositoryParser {

    public List<RepositoryInfo> parse(File javaFile) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        return extract(cu);
    }

    public List<RepositoryInfo> parseSource(String sourceCode) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        return extract(cu);
    }

    private List<RepositoryInfo> extract(CompilationUnit cu) {
        List<RepositoryInfo> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            // JpaRepository 또는 Repository 를 상속/구현하는 인터페이스만 처리
            if (!isRepository(clazz)) return;

            String entityClass = extractEntityClass(clazz).orElse("Unknown");
            RepositoryInfo info = new RepositoryInfo(clazz.getNameAsString(), entityClass);

            // 메서드 및 @Query 추출
            for (MethodDeclaration method : clazz.getMethods()) {
                info.addMethod(method.getNameAsString());

                // @Query 어노테이션 처리
                method.getAnnotationByName("Query").ifPresent(ann -> {
                    extractQueryString(ann).ifPresent(query -> {
                        if (isNativeQuery(ann)) {
                            info.addNativeQuery(query);
                        } else {
                            info.addJpqlQuery(query);
                        }
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
                .anyMatch(t -> {
                    String name = t.getNameAsString();
                    return name.contains("Repository") || name.contains("CrudRepository")
                            || name.contains("JpaRepository");
                });
    }

    /**
     * JpaRepository<User, Long> 에서 "User" 를 추출한다.
     */
    private Optional<String> extractEntityClass(ClassOrInterfaceDeclaration clazz) {
        return clazz.getExtendedTypes().stream()
                .filter(t -> t.getNameAsString().contains("Repository"))
                .findFirst()
                .flatMap(t -> {
                    if (t.getTypeArguments().isPresent() && !t.getTypeArguments().get().isEmpty()) {
                        return Optional.of(t.getTypeArguments().get().get(0).toString());
                    }
                    return Optional.empty();
                });
    }

    private Optional<String> extractQueryString(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return Optional.of(stripQuotes(single.getMemberValue().toString()));
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst()
                    .map(p -> stripQuotes(p.getValue().toString()));
        }
        return Optional.empty();
    }

    private boolean isNativeQuery(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .anyMatch(p -> p.getNameAsString().equals("nativeQuery")
                            && p.getValue().toString().equals("true"));
        }
        return false;
    }

    private String stripQuotes(String s) {
        return s.replaceAll("^\"|\"$", "").replaceAll("\\\\n", " ").trim();
    }
}