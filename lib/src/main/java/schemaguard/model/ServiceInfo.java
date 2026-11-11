package schemaguard.model;

import java.util.ArrayList;
import java.util.List;

public class ServiceInfo {

    private final String className;
    private final String filePath;
    private final int    classLine;
    /** true 면 @Service 가 아닌 클래스(DTO·매퍼 등)의 static 메서드 모음. 노드 id 는 "helper:" */
    private final boolean helper;

    private final List<MethodCall> methodCalls = new ArrayList<>();
    private final List<StaticCall> staticCalls = new ArrayList<>();
    private final List<EntityTypeAccess> entityTypeAccesses = new ArrayList<>();
    private final List<AssociationAccess> associationAccesses = new ArrayList<>();

    public ServiceInfo(String className) {
        this(className, null, -1);
    }

    public ServiceInfo(String className, String filePath, int classLine) {
        this(className, filePath, classLine, false);
    }

    public ServiceInfo(String className, String filePath, int classLine, boolean helper) {
        this.helper    = helper;
        this.className = className;
        this.filePath  = filePath;
        this.classLine = classLine;
    }

    /**
     * @param callerMethod  호출하는 Service 메서드명
     * @param callerLine    Service 메서드 선언 라인
     * @param calleeClass   호출 대상 클래스 (Repository / Service)
     * @param calleeMethod  호출 대상 메서드명
     * @param calleeLine    호출 표현식 라인
     */
    public void addMethodCall(String callerMethod, int callerLine,
                              String calleeClass,  String calleeMethod, int calleeLine) {
        methodCalls.add(new MethodCall(callerMethod, callerLine,
                                       calleeClass,  calleeMethod, calleeLine));
    }

    public String            getClassName()  { return className; }
    public String            getFilePath()   { return filePath; }
    public int               getClassLine()  { return classLine; }
    public List<MethodCall>  getMethodCalls(){ return methodCalls; }
    public List<AssociationAccess> getAssociationAccesses() { return associationAccesses; }
    public List<StaticCall> getStaticCalls() { return staticCalls; }
    public List<EntityTypeAccess> getEntityTypeAccesses() { return entityTypeAccesses; }
    public boolean isHelper() { return helper; }

    /** 그래프 노드 id 접두사: 서비스는 "svc:", DTO/매퍼의 static 메서드는 "helper:" */
    public String nodePrefix() { return helper ? "helper:" : "svc:"; }

    /** Type.method(...) 또는 Type::method 호출. 대상이 알려진 helper 클래스일 때만 그래프에 연결한다. */
    public void addStaticCall(String callerMethod, int callerLine, String calleeClass, String calleeMethod, int line) {
        staticCalls.add(new StaticCall(callerMethod, callerLine, calleeClass, calleeMethod, line));
    }

    /**
     * 엔티티 타입 파라미터에 getId() 가 아닌 getter 를 호출한 기록. 예) AuthorDto.from(User user) 의 user.getNickname()
     * 넘겨받은 값이 지연 로딩 프록시면 이 시점에 초기화된다. 엔티티 여부는 그래프를 만들 때 판단한다.
     */
    public void addEntityTypeAccess(String callerMethod, int callerLine, String entityType, int line) {
        entityTypeAccesses.add(new EntityTypeAccess(callerMethod, callerLine, entityType, line));
    }

    public record StaticCall(String callerMethod, int callerLine, String calleeClass, String calleeMethod, int line) {}
    public record EntityTypeAccess(String callerMethod, int callerLine, String entityType, int line) {}

    /**
     * 연관 엔티티를 실제로 초기화하는 접근을 기록한다. 예) report.getPost().getTitle() → attribute "post"
     * (getId()만 꺼내는 경우는 프록시가 id를 갖고 있어 로딩되지 않으므로 기록하지 않는다.)
     */
    public void addAssociationAccess(String callerMethod, int callerLine, String attribute, int line) {
        associationAccesses.add(new AssociationAccess(callerMethod, callerLine, attribute, line));
    }

    public record AssociationAccess(String callerMethod, int callerLine, String attribute, int line) {}

    // ── 내부 레코드 ───────────────────────────────────────────────────────────

    public static class MethodCall {
        public final String callerMethod;
        public final int    callerLine;    // Service 메서드 선언 라인
        public final String calleeClass;
        public final String calleeMethod;
        public final int    calleeLine;    // 실제 호출 표현식 라인

        public MethodCall(String callerMethod, int callerLine,
                          String calleeClass,  String calleeMethod, int calleeLine) {
            this.callerMethod = callerMethod;
            this.callerLine   = callerLine;
            this.calleeClass  = calleeClass;
            this.calleeMethod = calleeMethod;
            this.calleeLine   = calleeLine;
        }
    }
}