package schemaguard.model;

import java.util.ArrayList;
import java.util.List;

public class ServiceInfo {

    private final String className;
    private final String filePath;
    private final int    classLine;

    private final List<MethodCall> methodCalls = new ArrayList<>();
    private final List<AssociationAccess> associationAccesses = new ArrayList<>();

    public ServiceInfo(String className) {
        this(className, null, -1);
    }

    public ServiceInfo(String className, String filePath, int classLine) {
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