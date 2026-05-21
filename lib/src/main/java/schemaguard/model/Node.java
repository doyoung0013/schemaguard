package schemaguard.model;

import java.util.Objects;

/**
 * 의존성 그래프의 노드.
 * id 는 그래프 내 고유 식별자로 사용된다.
 *
 * 명명 규칙:
 *   API        → "api:GET /users/{id}"
 *   CONTROLLER → "ctrl:UserController.getUser"
 *   SERVICE    → "svc:UserService.getUser"
 *   REPOSITORY → "repo:UserRepository.findById"
 *   FIELD      → "field:User.email"
 *   COLUMN     → "col:users.email"
 */
public class Node {

    private final String id;
    private final NodeType type;
    private final String name;

    public Node(String id, NodeType type, String name) {
        this.id = id;
        this.type = type;
        this.name = name;
    }

    public String getId()     { return id; }
    public NodeType getType() { return type; }
    public String getName()   { return name; }

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