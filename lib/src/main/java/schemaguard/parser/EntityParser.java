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
import schemaguard.model.EntityMapping;
import schemaguard.model.FkMapping;

import java.io.File;
import java.util.*;

public class EntityParser {

    private static final Set<String> FK_ANNOTATIONS =
            Set.of("ManyToOne", "OneToMany", "OneToOne", "ManyToMany");

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public ParseResult parse(File javaFile) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        // 소스 루트 기준 상대경로로 변환하기 위해 절대경로를 그대로 전달
        // DependencyGraphBuilder 에서 소스 루트 기준으로 정규화한다
        return extract(cu, javaFile.getAbsolutePath());
    }

    public ParseResult parseSource(String sourceCode) throws Exception {
        return extract(StaticJavaParser.parse(sourceCode), null);
    }

    // ── 파싱 ─────────────────────────────────────────────────────────────────

    private ParseResult extract(CompilationUnit cu, String filePath) {
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

                // 필드 선언 라인 번호
                int line = field.getRange()
                        .map(r -> r.begin.line)
                        .orElse(-1);

                if (hasFkAnnotation(field)) {
                    // FK 필드
                    String fkRelationType  = detectFkRelationType(field);
                    String referencedEntity = field.getElementType().asString()
                            .replaceAll("<.*>", "").trim();
                    String joinColumnName  = getAnnotationValue(field, "JoinColumn", "name")
                            .orElse(toSnakeCase(fieldName) + "_id");

                    if (fkRelationType.equals("OneToMany")
                            && !hasAnnotation(field, "JoinColumn")) return;

                    fkMappings.add(new FkMapping(
                            className, fieldName, tableName,
                            joinColumnName, referencedEntity, fkRelationType,
                            filePath, line));               // ← 위치 정보
                } else {
                    // 일반 필드
                    String columnName = getAnnotationValue(field, "Column", "name")
                            .orElse(toSnakeCase(fieldName));
                    entityMappings.add(new EntityMapping(
                            className, fieldName, tableName, columnName,
                            filePath, line));               // ← 위치 정보
                }
            }
        });

        return new ParseResult(entityMappings, fkMappings);
    }

    // ── 어노테이션 헬퍼 ──────────────────────────────────────────────────────

    private boolean hasAnnotation(NodeWithAnnotations<?> node, String name) {
        return node.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals(name));
    }

    private boolean hasFkAnnotation(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .anyMatch(a -> FK_ANNOTATIONS.contains(a.getNameAsString()));
    }

    private String detectFkRelationType(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .filter(FK_ANNOTATIONS::contains)
                .findFirst().orElse("Unknown");
    }

    private Optional<String> getAnnotationValue(NodeWithAnnotations<?> node,
                                                 String annName, String attrName) {
        for (AnnotationExpr ann : node.getAnnotations()) {
            if (!ann.getNameAsString().equals(annName)) continue;
            if (ann instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair p : normal.getPairs())
                    if (p.getNameAsString().equals(attrName))
                        return Optional.of(stripQuotes(p.getValue().toString()));
            } else if (ann instanceof SingleMemberAnnotationExpr single) {
                if (attrName.equals("name") || attrName.equals("value"))
                    return Optional.of(stripQuotes(single.getMemberValue().toString()));
            }
        }
        return Optional.empty();
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    public static String toSnakeCase(String camel) {
        return camel.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                    .replaceAll("([a-z])([A-Z])", "$1_$2")
                    .toLowerCase();
    }

    private String stripQuotes(String s) { return s.replaceAll("^\"|\"$", ""); }

    // ── 반환 타입 ─────────────────────────────────────────────────────────────

    public record ParseResult(
            List<EntityMapping> entityMappings,
            List<FkMapping>     fkMappings
    ) {}
}