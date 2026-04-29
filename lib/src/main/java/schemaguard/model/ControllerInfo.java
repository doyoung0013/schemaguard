package schemaguard.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller 클래스에서 추출한 정보.
 * API 엔드포인트와 Controller 메서드 간의 매핑, 그리고 Service 호출 관계를 담는다.
 */
public class ControllerInfo {

    private final String className;   // ex. "UserController"
    private final List<EndpointMapping> endpoints = new ArrayList<>();

    public ControllerInfo(String className) {
        this.className = className;
    }

    public void addEndpoint(String httpMethod, String path,
                            String controllerMethod,
                            String calleeServiceClass, String calleeServiceMethod) {
        endpoints.add(new EndpointMapping(
                httpMethod, path, controllerMethod,
                calleeServiceClass, calleeServiceMethod));
    }

    public String getClassName()                   { return className; }
    public List<EndpointMapping> getEndpoints()    { return endpoints; }

    // ── 내부 레코드 ──────────────────────────────────────────────────────────
    public static class EndpointMapping {
        public final String httpMethod;           // ex. "GET"
        public final String path;                 // ex. "/users/{id}"
        public final String controllerMethod;     // ex. "getUser"
        public final String calleeServiceClass;   // ex. "UserService"
        public final String calleeServiceMethod;  // ex. "getUser"

        public EndpointMapping(String httpMethod, String path,
                               String controllerMethod,
                               String calleeServiceClass, String calleeServiceMethod) {
            this.httpMethod          = httpMethod;
            this.path                = path;
            this.controllerMethod    = controllerMethod;
            this.calleeServiceClass  = calleeServiceClass;
            this.calleeServiceMethod = calleeServiceMethod;
        }

        /** API 노드 ID: "api:GET /users/{id}" */
        public String apiNodeId() {
            return "api:" + httpMethod + " " + path;
        }

        /** Controller 노드 ID: "ctrl:UserController.getUser" */
        public String controllerNodeId(String className) {
            return "ctrl:" + className + "." + controllerMethod;
        }
    }
}