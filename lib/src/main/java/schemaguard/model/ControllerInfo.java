package schemaguard.model;

import java.util.ArrayList;
import java.util.List;

public class ControllerInfo {

    private final String className;
    private final String filePath;
    private final int    classLine;

    private final List<EndpointMapping> endpoints = new ArrayList<>();

    public ControllerInfo(String className) {
        this(className, null, -1);
    }

    public ControllerInfo(String className, String filePath, int classLine) {
        this.className = className;
        this.filePath  = filePath;
        this.classLine = classLine;
    }

    /** 서비스 호출이 하나뿐인(또는 없는) 엔드포인트. 기존 호출부 호환용. */
    public void addEndpoint(String httpMethod, String path,
                            String controllerMethod, int methodLine,
                            String calleeServiceClass, String calleeServiceMethod,
                            int serviceCallLine) {
        List<ServiceCall> calls = calleeServiceClass == null || calleeServiceMethod == null
                ? List.of()
                : List.of(new ServiceCall(calleeServiceClass, calleeServiceMethod, serviceCallLine));
        addEndpoint(httpMethod, path, controllerMethod, methodLine, calls);
    }

    /** 컨트롤러 메서드 안에서 호출하는 서비스 메서드 전부. */
    public void addEndpoint(String httpMethod, String path,
                            String controllerMethod, int methodLine,
                            List<ServiceCall> serviceCalls) {
        addEndpoint(httpMethod, path, controllerMethod, methodLine, serviceCalls, List.of());
    }

    /** @param staticCalls Type.method(...) / Type::method 호출 (DTO 변환 등). 알려진 helper 클래스만 그래프에 연결된다. */
    public void addEndpoint(String httpMethod, String path,
                            String controllerMethod, int methodLine,
                            List<ServiceCall> serviceCalls, List<ServiceCall> staticCalls) {
        endpoints.add(new EndpointMapping(
                httpMethod, path, controllerMethod, methodLine, serviceCalls, staticCalls));
    }

    public String                  getClassName() { return className; }
    public String                  getFilePath()  { return filePath; }
    public int                     getClassLine() { return classLine; }
    public List<EndpointMapping>   getEndpoints() { return endpoints; }

    // ── 내부 레코드 ───────────────────────────────────────────────────────────

    /** 컨트롤러 메서드 안의 서비스 호출 하나. */
    public record ServiceCall(String serviceClass, String serviceMethod, int line) {}

    public static class EndpointMapping {
        public final String httpMethod;
        public final String path;
        public final String controllerMethod;
        public final int    methodLine;          // @GetMapping 메서드 선언 라인
        public final List<ServiceCall> serviceCalls;
        public final List<ServiceCall> staticCalls;

        // 첫 번째 호출 (기존 필드 호환용; 없으면 null / -1)
        public final String calleeServiceClass;
        public final String calleeServiceMethod;
        public final int    serviceCallLine;     // service 호출 표현식 라인

        public EndpointMapping(String httpMethod, String path,
                               String controllerMethod, int methodLine,
                               List<ServiceCall> serviceCalls) {
            this(httpMethod, path, controllerMethod, methodLine, serviceCalls, List.of());
        }

        public EndpointMapping(String httpMethod, String path,
                               String controllerMethod, int methodLine,
                               List<ServiceCall> serviceCalls, List<ServiceCall> staticCalls) {
            this.staticCalls         = List.copyOf(staticCalls);
            this.httpMethod          = httpMethod;
            this.path                = path;
            this.controllerMethod    = controllerMethod;
            this.methodLine          = methodLine;
            this.serviceCalls        = List.copyOf(serviceCalls);
            ServiceCall first        = serviceCalls.isEmpty() ? null : serviceCalls.get(0);
            this.calleeServiceClass  = first != null ? first.serviceClass()  : null;
            this.calleeServiceMethod = first != null ? first.serviceMethod() : null;
            this.serviceCallLine     = first != null ? first.line()          : -1;
        }

        public String apiNodeId() {
            return "api:" + httpMethod + " " + path;
        }

        public String controllerNodeId(String className) {
            return "ctrl:" + className + "." + controllerMethod;
        }
    }
}