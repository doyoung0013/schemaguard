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

    public void addEndpoint(String httpMethod, String path,
                            String controllerMethod, int methodLine,
                            String calleeServiceClass, String calleeServiceMethod,
                            int serviceCallLine) {
        endpoints.add(new EndpointMapping(
                httpMethod, path, controllerMethod, methodLine,
                calleeServiceClass, calleeServiceMethod, serviceCallLine));
    }

    public String                  getClassName() { return className; }
    public String                  getFilePath()  { return filePath; }
    public int                     getClassLine() { return classLine; }
    public List<EndpointMapping>   getEndpoints() { return endpoints; }

    // ── 내부 레코드 ───────────────────────────────────────────────────────────

    public static class EndpointMapping {
        public final String httpMethod;
        public final String path;
        public final String controllerMethod;
        public final int    methodLine;          // @GetMapping 메서드 선언 라인
        public final String calleeServiceClass;
        public final String calleeServiceMethod;
        public final int    serviceCallLine;     // service 호출 표현식 라인

        public EndpointMapping(String httpMethod, String path,
                               String controllerMethod, int methodLine,
                               String calleeServiceClass, String calleeServiceMethod,
                               int serviceCallLine) {
            this.httpMethod          = httpMethod;
            this.path                = path;
            this.controllerMethod    = controllerMethod;
            this.methodLine          = methodLine;
            this.calleeServiceClass  = calleeServiceClass;
            this.calleeServiceMethod = calleeServiceMethod;
            this.serviceCallLine     = serviceCallLine;
        }

        public String apiNodeId() {
            return "api:" + httpMethod + " " + path;
        }

        public String controllerNodeId(String className) {
            return "ctrl:" + className + "." + controllerMethod;
        }
    }
}