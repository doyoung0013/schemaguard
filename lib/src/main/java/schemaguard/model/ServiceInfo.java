package schemaguard.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Service 클래스에서 추출한 정보.
 * 각 메서드가 어떤 Repository(또는 다른 Service) 메서드를 호출하는지 담는다.
 */
public class ServiceInfo {

    private final String className;   // ex. "UserService"
    private final List<MethodCall> methodCalls = new ArrayList<>();

    public ServiceInfo(String className) {
        this.className = className;
    }

    public void addMethodCall(String callerMethod, String calleeClass, String calleeMethod) {
        methodCalls.add(new MethodCall(callerMethod, calleeClass, calleeMethod));
    }

    public String getClassName()             { return className; }
    public List<MethodCall> getMethodCalls() { return methodCalls; }

    // ── 내부 레코드 ──────────────────────────────────────────────────────────
    public static class MethodCall {
        public final String callerMethod;   // ex. "getUser"
        public final String calleeClass;    // ex. "UserRepository"  (필드 타입 이름)
        public final String calleeMethod;   // ex. "findById"

        public MethodCall(String callerMethod, String calleeClass, String calleeMethod) {
            this.callerMethod = callerMethod;
            this.calleeClass  = calleeClass;
            this.calleeMethod = calleeMethod;
        }

        @Override
        public String toString() {
            return callerMethod + " -> " + calleeClass + "." + calleeMethod;
        }
    }
}