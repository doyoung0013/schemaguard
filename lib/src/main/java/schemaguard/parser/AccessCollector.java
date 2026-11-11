package schemaguard.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypeExpr;

import java.util.HashMap;
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
