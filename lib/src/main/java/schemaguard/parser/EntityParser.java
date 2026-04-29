package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import schemaguard.model.EntityMapping;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @Entity 클래스를 파싱하여 Java 필드 ↔ DB 컬럼 매핑 정보(EntityMapping)를 추출한다.
 *
 * 처리 우선순위:
 *   1. @Table(name="...") 있으면 해당 값을 테이블명으로 사용
 *   2. 없으면 클래스명을 snake_case 로 변환 (ex. PostLike → post_like)
 *   3. @Column(name="...") 있으면 해당 값을 컬럼명으로 사용
 *   4. 없으면 필드명을 snake_case 로 변환
 */
public class EntityParser {

    public List<EntityMapping> parse(File javaFile) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        return extractMappings(cu);
    }

    public List<EntityMapping> parseSource(String sourceCode) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        return extractMappings(cu);
    }

    private List<EntityMapping> extractMappings(CompilationUnit cu) {
        List<EntityMapping> result = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            // @Entity 붙은 클래스만 처리
            if (!hasAnnotation(clazz, "Entity")) return;

            String className = clazz.getNameAsString();

            // 테이블명 결정
            String tableName = getAnnotationValue(clazz, "Table", "name")
                    .orElse(toSnakeCase(className));

            // 필드별 컬럼 매핑 추출
            for (FieldDeclaration field : clazz.getFields()) {

                // @Transient 필드는 DB 컬럼이 없으므로 스킵
                if (hasAnnotation(field, "Transient")) continue;

                String fieldName = field.getVariable(0).getNameAsString();

                // @Id 필드 처리 (@Column 없이 id 만 있는 경우도 많음)
                String columnName = getAnnotationValue(field, "Column", "name")
                        .orElse(toSnakeCase(fieldName));

                // @JoinColumn 처리 (@ManyToOne 등 연관관계 필드)
                if (hasAnnotation(field, "JoinColumn")) {
                    columnName = getAnnotationValue(field, "JoinColumn", "name")
                            .orElse(toSnakeCase(fieldName));
                }

                result.add(new EntityMapping(className, fieldName, tableName, columnName));
            }
        });

        return result;
    }

    // ── 어노테이션 헬퍼 ──────────────────────────────────────────────────────

    private boolean hasAnnotation(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node,
                                  String annotationName) {
        return node.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(annotationName));
    }

    /**
     * @SomeAnnotation(targetAttr="value") 에서 value 를 추출한다.
     * @SomeAnnotation("value") 형태도 "value" 속성으로 처리한다.
     */
    private Optional<String> getAnnotationValue(
            com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node,
            String annotationName, String attributeName) {

        for (AnnotationExpr ann : node.getAnnotations()) {
            if (!ann.getNameAsString().equals(annotationName)) continue;

            if (ann instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals(attributeName)) {
                        return Optional.of(stripQuotes(pair.getValue().toString()));
                    }
                }
            } else if (ann instanceof SingleMemberAnnotationExpr single) {
                // @Table("users") 같은 단축 형태 — 첫 번째 속성에 매핑
                if (attributeName.equals("name") || attributeName.equals("value")) {
                    return Optional.of(stripQuotes(single.getMemberValue().toString()));
                }
            }
        }
        return Optional.empty();
    }

    // ── 문자열 유틸 ──────────────────────────────────────────────────────────

    /** CamelCase → snake_case 변환 */
    public static String toSnakeCase(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    private String stripQuotes(String s) {
        return s.replaceAll("^\"|\"$", "");
    }
}