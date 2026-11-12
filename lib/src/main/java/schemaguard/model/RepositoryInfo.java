package schemaguard.model;

import java.util.ArrayList;
import java.util.List;

public class RepositoryInfo {

    private final String className;
    private final String entityClass;
    private final String filePath;    // 인터페이스 파일 경로
    private final int    classLine;   // 인터페이스 선언 라인

    // 메서드명 + 라인 번호를 함께 보관
    private final List<MethodEntry> methods        = new ArrayList<>();
    private final List<String>      jpqlQueries    = new ArrayList<>();
    private final List<String>      nativeQueries  = new ArrayList<>();

    public RepositoryInfo(String className, String entityClass) {
        this(className, entityClass, null, -1);
    }

    public RepositoryInfo(String className, String entityClass,
                          String filePath, int classLine) {
        this.className   = className;
        this.entityClass = entityClass;
        this.filePath    = filePath;
        this.classLine   = classLine;
    }

    public void addMethod(String methodName, int lineNumber) {
        methods.add(new MethodEntry(methodName, lineNumber));
    }

    /** @param fetchPaths 이 메서드가 함께 로딩하는 연관 경로 (@EntityGraph attributePaths, JPQL join fetch). 예) "post.author" */
    public void addMethod(String methodName, int lineNumber, List<String> fetchPaths) {
        methods.add(new MethodEntry(methodName, lineNumber, fetchPaths));
    }

    public void addJpqlQuery(String jpql, int lineNumber)   { jpqlQueries.add(jpql); }
    public void addNativeQuery(String sql, int lineNumber)  { nativeQueries.add(sql); }

    public String getClassName()               { return className; }
    public String getEntityClass()             { return entityClass; }
    public String getFilePath()                { return filePath; }
    public int    getClassLine()               { return classLine; }
    public List<MethodEntry> getMethods()      { return methods; }
    public List<String> getJpqlQueries()       { return jpqlQueries; }
    public List<String> getNativeQueries()     { return nativeQueries; }

    /** 기존 코드 호환용: 메서드명 리스트만 반환 */
    public List<String> getMethodNames() {
        return methods.stream().map(MethodEntry::name).toList();
    }

    /** 메서드명으로 라인 번호 조회 */
    public int getMethodLine(String methodName) {
        return methods.stream()
                .filter(e -> e.name().equals(methodName))
                .map(MethodEntry::lineNumber)
                .findFirst().orElse(-1);
    }

    /** 메서드 정보를 그대로 넣는다 (@Lock, @Query 여부 포함). */
    public void addMethod(MethodEntry entry) {
        methods.add(entry);
    }

    /**
     * @param lockMode    @Lock(LockModeType.PESSIMISTIC_*) 의 모드 이름 (PESSIMISTIC_WRITE 등). 비관적 락이 아니면 null
     * @param query       @Query 의 쿼리 문자열. 없으면 null (메서드 이름이 조회 조건이다)
     * @param nativeQuery @Query(nativeQuery = true)
     */
    public record MethodEntry(String name, int lineNumber, List<String> fetchPaths, String lockMode,
                              String query, boolean nativeQuery) {
        public MethodEntry(String name, int lineNumber) { this(name, lineNumber, List.of()); }
        public MethodEntry(String name, int lineNumber, List<String> fetchPaths) { this(name, lineNumber, fetchPaths, null, null, false); }
        public MethodEntry { fetchPaths = List.copyOf(fetchPaths); }

        public boolean isPessimisticLock() { return lockMode != null; }
        public boolean hasQuery()          { return query != null; }
    }
}