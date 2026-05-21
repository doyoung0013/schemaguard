package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import schemaguard.model.FkMapping;
import schemaguard.model.EntityMapping;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;


/**
 * @Entity 클래스를 파싱하여 두 가지 매핑 정보를 추출한다.
 *
 * 1) EntityMapping  : 일반 필드 ↔ DB 컬럼 매핑 (@Column)
 * 2) FkMapping      : FK 연관관계 필드 ↔ DB FK 컬럼 매핑 (@ManyToOne, @OneToMany, @JoinColumn)
 */
public class EntityParser {

    /** FK 연관관계 어노테이션 집합 */
    private static final Set<String> FK_ANNOTATIONS =
            Set.of("ManyToOne", "OneToMany", "OneToOne", "ManyToMany");

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public ParseResult parse(File javaFile) throws Exception {
        return extract(StaticJavaParser.parse(javaFile));
    }

    public ParseResult parseSource(String sourceCode) throws Exception {
        return extract(StaticJavaParser.parse(sourceCode));
    }

    // ── 내부 파싱 로직 ────────────────────────────────────────────────────────

    private ParseResult extract(CompilationUnit cu) {
        List<EntityMapping> entityMappings = new ArrayList<>();
        List<FkMapping>     fkMappings     = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (!hasAnnotation(clazz, "Entity")) return;

            String className = clazz.getNameAsString();
            String tableName = getAnnotationValue(clazz, "Table", "name")
                    .orElse(toSnakeCase(className));

            for (FieldDeclaration field : clazz.getFields()) {
                if (hasAnnotation(field, "Transient")) continue;

                String fieldName = field.getVariable(0).getNameAsString();

                // ── FK 연관관계 필드 ───────────────────────────────────────────
                if (hasFkAnnotation(field)) {
                    String fkRelationType = detectFkRelationType(field);
                    String referencedEntity = field.getElementType().asString()
                            .replaceAll("<.*>", "").trim(); // List<User> → User

                    // @JoinColumn(name="author_id") 에서 FK 컬럼명 추출
                    // 없으면 필드명 + "_id" 관례 적용
                    String joinColumnName = getAnnotationValue(field, "JoinColumn", "name")
                            .orElse(toSnakeCase(fieldName) + "_id");

                    // @OneToMany 는 보통 FK 컬럼이 반대쪽 테이블에 있으므로
                    // joinColumn 이 없는 경우 컬럼 노드 연결을 생략해도 무방하지만,
                    // @JoinColumn 이 명시된 경우는 포함한다.
                    if (fkRelationType.equals("OneToMany")
                            && !hasAnnotation(field, "JoinColumn")) {
                        return; // mappedBy 쪽은 FK 컬럼을 직접 소유하지 않음
                    }

                    fkMappings.add(new FkMapping(
                            className, fieldName, tableName,
                            joinColumnName, referencedEntity, fkRelationType));
                }

                // ── 일반 필드 ──────────────────────────────────────────────────
                else {
                    String columnName = getAnnotationValue(field, "Column", "name")
                            .orElse(toSnakeCase(fieldName));
                    entityMappings.add(new EntityMapping(className, fieldName, tableName, columnName));
                }
            }
        });

        return new ParseResult(entityMappings, fkMappings);
    }

    // ── 어노테이션 헬퍼 ──────────────────────────────────────────────────────

    private boolean hasAnnotation(NodeWithAnnotations<?> node, String name) {
        return node.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(name));
    }

    private boolean hasFkAnnotation(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .anyMatch(a -> FK_ANNOTATIONS.contains(a.getNameAsString()));
    }

    private String detectFkRelationType(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .filter(FK_ANNOTATIONS::contains)
                .findFirst()
                .orElse("Unknown");
    }

    private Optional<String> getAnnotationValue(NodeWithAnnotations<?> node,
                                                 String annotationName,
                                                 String attributeName) {
        for (AnnotationExpr ann : node.getAnnotations()) {
            if (!ann.getNameAsString().equals(annotationName)) continue;

            if (ann instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals(attributeName)) {
                        return Optional.of(stripQuotes(pair.getValue().toString()));
                    }
                }
            } else if (ann instanceof SingleMemberAnnotationExpr single) {
                if (attributeName.equals("name") || attributeName.equals("value")) {
                    return Optional.of(stripQuotes(single.getMemberValue().toString()));
                }
            }
        }
        return Optional.empty();
    }

    // ── 문자열 유틸 ──────────────────────────────────────────────────────────

    public static String toSnakeCase(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    private String stripQuotes(String s) {
        return s.replaceAll("^\"|\"$", "");
    }

    // ── 반환 타입 ─────────────────────────────────────────────────────────────

    /**
     * EntityParser 파싱 결과를 담는 컨테이너.
     * 일반 매핑과 FK 매핑을 함께 반환한다.
     */
    public record ParseResult(
            List<EntityMapping> entityMappings,
            List<FkMapping>     fkMappings
    ) {}
}