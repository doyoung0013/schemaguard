package schemaguard.model;

import java.util.Objects;

/**
 * 의존성 그래프의 노드.
 *
 * 명명 규칙:
 *   API        → "api:GET /users/{id}"
 *   CONTROLLER → "ctrl:UserController.getUser"
 *   SERVICE    → "svc:UserService.getUser"
 *   REPOSITORY → "repo:UserRepository.findById"
 *   FIELD      → "field:User.email"
 *   FK_FIELD   → "fkfield:Post.author"
 *   COLUMN     → "col:users.email"
 *
 * filePath, lineNumber 는 파서가 추출한 실제 소스 위치이다.
 * 위치 정보가 없는 노드(COLUMN, API)는 null / -1 로 설정된다.
 */
public class Node {

    private final String   id;
    private final NodeType type;
    private final String   name;

    // 소스 위치 정보 (파서에서 채운다)
    private String filePath;   // ex. "src/main/java/com/example/UserRepository.java"
    private int    lineNumber; // ex. 14  (없으면 -1)

    public Node(String id, NodeType type, String name) {
        this(id, type, name, null, -1);
    }

    public Node(String id, NodeType type, String name, String filePath, int lineNumber) {
        this.id         = id;
        this.type       = type;
        this.name       = name;
        this.filePath   = filePath;
        this.lineNumber = lineNumber;
    }

    // ── getter ────────────────────────────────────────────────────────────────

    public String   getId()         { return id; }
    public NodeType getType()       { return type; }
    public String   getName()       { return name; }
    public String   getFilePath()   { return filePath; }
    public int      getLineNumber() { return lineNumber; }

    public boolean hasLocation() {
        return filePath != null && lineNumber > 0;
    }

    /** "src/main/java/.../UserRepository.java:14" 형태 */
    public String getLocationString() {
        if (!hasLocation()) return null;
        return filePath + ":" + lineNumber;
    }

    // ── setter (DependencyGraphBuilder 에서 위치 정보 보강 시 사용) ───────────

    public void setFilePath(String filePath)    { this.filePath = filePath; }
    public void setLineNumber(int lineNumber)   { this.lineNumber = lineNumber; }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node)) return false;
        return Objects.equals(id, ((Node) o).id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() { return id; }
}