package schemaguard.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypeExpr;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import schemaguard.model.ServiceInfo.TypeSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 메서드 본문에서 "연관 엔티티 초기화"와 "static 호출"을 찾는다. 서비스, 컨트롤러, DTO/매퍼가 같이 쓴다.
 *
 *  getter 체인    x.getPost().getTitle()        → 연관 "post" 초기화 (바깥 호출이 getId() 면 제외: 프록시가 id 를 갖고 있다)
 *  엔티티 파라미터 from(User user) { user.getNickname() } → 파라미터 타입 "User" 초기화 (getId() 제외)
 *  static 호출    ReportResponse.from(r), ReportResponse::from → 호출 대상 (Type, method)
 */
final class AccessCollector {

    interface ChainSink  { void accept(String attribute, int line); }
    interface TypedChainSink { void accept(String rootType, List<String> path, TypeSource source, int line); }
    interface TypeSink   { void accept(String type, int line); }
    interface StaticSink { void accept(String type, String method, int line); }

    private AccessCollector() {}

    static void getterChains(MethodDeclaration method, ChainSink sink) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!isGetter(call) || call.getScope().isEmpty()) continue;
            call.getParentNode()
                    .filter(p -> p instanceof MethodCallExpr)
                    .map(p -> (MethodCallExpr) p)
                    .filter(outer -> outer.getScope().map(s -> s == call).orElse(false))
                    .filter(outer -> !outer.getNameAsString().equals("getId"))
                    .ifPresent(outer -> sink.accept(decapitalize(call.getNameAsString().substring(3)), line(call)));
        }
    }

    /**
     * getter 체인을 루트부터 해석한다.
     *   report.getPost().getAuthor().getNickname()
     *     루트 report 의 타입(선언 또는 symbol solver) = Report
     *     → [post] 초기화 (다음 호출 getAuthor ≠ getId), [post, author] 초기화 (다음 호출 getNickname ≠ getId)
     *   루트 자체가 엔티티 타입이고 첫 호출이 getId() 가 아니면 루트 엔티티도 초기화된 것으로 본다 (rootSink).
     * 루트 타입을 모르면 rootType = null 로 넘기고, 그래프 빌더가 첫 속성 이름으로 추정한다.
     */
    static void associationChains(MethodDeclaration method, TypedChainSink chainSink, TypeSink rootSink,
                                  TypeStats stats) {
        for (MethodCallExpr top : method.findAll(MethodCallExpr.class)) {
            boolean isScopeOfParent = top.getParentNode()
                    .filter(p -> p instanceof MethodCallExpr pm && pm.getScope().map(s -> s == top).orElse(false))
                    .isPresent();
            if (isScopeOfParent) continue;                       // 체인의 가장 바깥 호출에서만 시작

            LinkedList<MethodCallExpr> chain = new LinkedList<>();
            MethodCallExpr c = top;
            Expression root;
            while (true) {
                chain.addFirst(c);
                Expression scope = c.getScope().orElse(null);
                if (scope instanceof MethodCallExpr sc && isGetter(sc)) { c = sc; continue; }
                root = scope;
                break;
            }
            if (root == null) continue;

            boolean rootLoad = !chain.get(0).getNameAsString().equals("getId");
            boolean anyLoad = false;
            for (int i = 0; i + 1 < chain.size(); i++)
                if (!chain.get(i + 1).getNameAsString().equals("getId")) anyLoad = true;
            if (!anyLoad && !rootLoad) continue;

            String[] resolved = rootType(root, method, stats);   // {type, source}
            String rootType = resolved == null ? null : resolved[0];
            TypeSource source = resolved == null ? TypeSource.NAME : TypeSource.valueOf(resolved[1]);

            if (rootType != null && rootLoad) rootSink.accept(rootType, line(chain.get(0)));

            List<String> path = new ArrayList<>();
            for (int i = 0; i + 1 < chain.size(); i++) {         // chain[0..n-2] 는 모두 getter
                path.add(decapitalize(chain.get(i).getNameAsString().substring(3)));
                if (!chain.get(i + 1).getNameAsString().equals("getId"))
                    chainSink.accept(rootType, path, source, line(chain.get(i)));
            }
        }
    }

    /** 해석 방법별 건수 (리포트·측정용). 연관 초기화가 있는 체인 단위로 센다. */
    static final class TypeStats {
        int declared, inferred, solver, unresolved;
        @Override public String toString() {
            return "declared=" + declared + " inferred=" + inferred + " solver=" + solver + " unresolved=" + unresolved;
        }
    }

    /** 원소 타입을 바꾸지 않는 스트림/컬렉션 연산 */
    private static final java.util.Set<String> PASS_THROUGH = java.util.Set.of(
            "stream", "parallelStream", "filter", "sorted", "distinct", "peek", "limit", "skip");
    /** 람다 파라미터로 원소를 넘기는 연산 */
    private static final java.util.Set<String> ELEMENT_OPS = java.util.Set.of(
            "map", "filter", "forEach", "anyMatch", "allMatch", "noneMatch", "peek", "flatMap",
            "ifPresent", "mapToLong", "mapToInt", "removeIf", "sorted", "min", "max");
    /** Optional 등에서 원소를 꺼내는 호출 */
    private static final java.util.Set<String> UNWRAP = java.util.Set.of(
            "orElseThrow", "orElse", "orElseGet", "get", "getFirst");

    /**
     * 선언이 없는 루트의 타입을 가벼운 규칙으로 추론한다 (symbol solver 없이).
     *   posts.stream().filter(..).map(p -> p.getAuthor()…)   : List<Post> posts → Post
     *   repo.findByPostId(id).stream().map(pt -> pt.getTag()…): PostTagRepository 의 엔티티 → "repo:PostTagRepository"
     *   repo.findById(id).orElseThrow().getPost()…            : 루트가 리포지토리 호출 → "repo:XRepository"
     */
    private static String inferredType(Expression root, MethodDeclaration method) {
        if (root instanceof NameExpr n) {
            LambdaExpr lambda = implicitLambdaOf(n);
            if (lambda == null) return null;
            if (!(lambda.getParentNode().orElse(null) instanceof MethodCallExpr op)
                    || !ELEMENT_OPS.contains(op.getNameAsString()) || op.getScope().isEmpty()) return null;
            return elementType(op.getScope().get(), method);
        }
        if (root instanceof MethodCallExpr call) {
            Expression e = call;
            while (e instanceof MethodCallExpr m && UNWRAP.contains(m.getNameAsString()) && m.getScope().isPresent())
                e = m.getScope().get();
            return repositoryResult(e, method);
        }
        return null;
    }

    /** 컬렉션·스트림·Optional 식의 원소 타입 */
    private static String elementType(Expression e, MethodDeclaration method) {
        while (e instanceof MethodCallExpr m && PASS_THROUGH.contains(m.getNameAsString()) && m.getScope().isPresent())
            e = m.getScope().get();
        if (e instanceof NameExpr n) {
            String full = declaredTypeFull(n.getNameAsString(), n, method);
            return full == null ? null : firstTypeArgument(full);
        }
        return repositoryResult(e, method);
    }

    /**
     * field.method(...) 이면 "repo:<필드 타입>". 그 타입이 실제 리포지토리인지는 그래프 빌더가 리포지토리 목록으로 판단한다
     * (이름 접미사 "Repository" 에 기대지 않는다). 리포지토리 호출의 결과는 그 엔티티(들)로 본다.
     */
    private static String repositoryResult(Expression e, MethodDeclaration method) {
        if (!(e instanceof MethodCallExpr m) || m.getScope().isEmpty() || !(m.getScope().get() instanceof NameExpr r))
            return null;
        String type = declaredType(r.getNameAsString(), r, method);
        return type != null ? "repo:" + type : null;
    }

    private static LambdaExpr implicitLambdaOf(NameExpr n) {
        for (Node p = n.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof MethodDeclaration) return null;
            if (p instanceof LambdaExpr l)
                for (Parameter lp : l.getParameters())
                    if (lp.getNameAsString().equals(n.getNameAsString()))
                        return lp.getType().isUnknownType() ? l : null;
        }
        return null;
    }

    private static String firstTypeArgument(String type) {
        int lt = type.indexOf('<');
        if (lt < 0 || !type.endsWith(">")) return null;
        String inner = type.substring(lt + 1, type.length() - 1);
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) { inner = inner.substring(0, i); break; }
        }
        return simple(inner.trim());
    }

    /** 루트 식의 타입. 선언 → symbol solver 순으로 찾고, 실패하면 null. */
    private static String[] rootType(Expression root, MethodDeclaration method, TypeStats stats) {
        if (root instanceof ThisExpr) return null;
        if (root instanceof NameExpr n) {
            String declared = declaredType(n.getNameAsString(), n, method);
            if (declared != null) {
                if (stats != null) stats.declared++;
                return new String[]{declared, "DECLARED"};
            }
        }
        String inferred = inferredType(root, method);
        if (inferred != null) {
            if (stats != null) stats.inferred++;
            return new String[]{inferred, "INFERRED"};
        }
        try {
            ResolvedType t = root.calculateResolvedType();
            // 람다 파라미터는 Function<? super T, …> 에서 와서 "? super Pet" 형태(제약 타입/와일드카드)로 풀린다 → 경계 타입
            if (t.isConstraint()) t = t.asConstraintType().getBound();
            if (t.isWildcard() && t.asWildcard().isBounded()) t = t.asWildcard().getBoundedType();
            if (t.isReferenceType()) {
                String q = t.asReferenceType().getQualifiedName();
                if (stats != null) stats.solver++;
                return new String[]{q.substring(q.lastIndexOf('.') + 1), "SOLVER"};
            }
        } catch (Throwable ignored) {
            // symbol resolver 미설정, 클래스패스에 없는 타입(Spring 등), 추론 실패 → 이름 추정으로 넘긴다
        }
        if (stats != null) stats.unresolved++;
        return null;
    }

    private static String declaredType(String name, Node at, MethodDeclaration method) {
        String full = declaredTypeFull(name, at, method);
        return full == null ? null : simple(full);
    }

    /** 이름이 가리키는 변수의 선언 타입(제네릭 포함): 명시 타입 람다 파라미터 → 지역 변수(var 제외) → 메서드 파라미터 → 필드 */
    private static String declaredTypeFull(String name, Node at, MethodDeclaration method) {
        for (Node p = at.getParentNode().orElse(null); p != null && p != method; p = p.getParentNode().orElse(null)) {
            if (p instanceof LambdaExpr l) {
                for (Parameter lp : l.getParameters())
                    if (lp.getNameAsString().equals(name))
                        return lp.getType().isUnknownType() ? null : lp.getType().asString();
            }
        }
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
            if (!v.getNameAsString().equals(name)) continue;
            return v.getType().isVarType() ? null : v.getType().asString();
        }
        for (Parameter p : method.getParameters())
            if (p.getNameAsString().equals(name)) return p.getType().asString();
        ClassOrInterfaceDeclaration clazz = method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (clazz != null)
            for (FieldDeclaration f : clazz.getFields())
                for (VariableDeclarator v : f.getVariables())
                    if (v.getNameAsString().equals(name)) return v.getType().asString();
        return null;
    }

    private static String simple(String type) {
        String t = type.replaceAll("<.*>", "").trim();
        return t.substring(t.lastIndexOf('.') + 1);
    }

    static void entityParameterAccesses(MethodDeclaration method, TypeSink sink) {
        Map<String, String> paramTypes = new HashMap<>();
        for (Parameter p : method.getParameters())
            paramTypes.put(p.getNameAsString(), p.getType().asString().replaceAll("<.*>", "").trim());
        if (paramTypes.isEmpty()) return;

        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!isGetter(call) || call.getNameAsString().equals("getId")) continue;
            if (call.getScope().isEmpty() || !(call.getScope().get() instanceof NameExpr scope)) continue;
            String type = paramTypes.get(scope.getNameAsString());
            if (type != null && !shadowedByLambda(call, scope.getNameAsString())) sink.accept(type, line(call));
        }
    }

    static void staticCalls(MethodDeclaration method, StaticSink sink) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent() && call.getScope().get() instanceof NameExpr n && looksLikeType(n.getNameAsString()))
                sink.accept(n.getNameAsString(), call.getNameAsString(), line(call));
        }
        for (MethodReferenceExpr ref : method.findAll(MethodReferenceExpr.class)) {
            String type = null;
            if (ref.getScope() instanceof TypeExpr t) type = t.getType().asString();
            else if (ref.getScope() instanceof NameExpr n && looksLikeType(n.getNameAsString())) type = n.getNameAsString();
            if (type != null) sink.accept(type.replaceAll("<.*>", ""), ref.getIdentifier(), line(ref));
        }
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    static boolean isGetter(MethodCallExpr call) {
        String n = call.getNameAsString();
        return n.length() > 3 && n.startsWith("get") && Character.isUpperCase(n.charAt(3))
                && call.getArguments().isEmpty();
    }

    /** 클래스명 관례(대문자 시작, 상수 ALL_CAPS 제외)로 static 호출 대상을 고른다. */
    private static boolean looksLikeType(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase());
    }

    /** 람다 파라미터가 같은 이름으로 메서드 파라미터를 가리는 경우 (list.forEach(user -> user.getX())) 는 제외. */
    private static boolean shadowedByLambda(Node node, String name) {
        for (Node p = node.getParentNode().orElse(null); p != null && !(p instanceof MethodDeclaration);
             p = p.getParentNode().orElse(null)) {
            if (p instanceof com.github.javaparser.ast.expr.LambdaExpr l
                    && l.getParameters().stream().anyMatch(x -> x.getNameAsString().equals(name))) return true;
        }
        return false;
    }

    private static int line(Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(-1);
    }

    static String decapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
