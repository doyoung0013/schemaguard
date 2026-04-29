package schemaguard.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository 클래스에서 추출한 정보.
 */
public class RepositoryInfo {

    private final String className;      // ex. "UserRepository"
    private final String entityClass;    // JpaRepository<User, Long> 에서 추출한 "User"
    private final List<String> methodNames = new ArrayList<>();  // ex. ["findById", "findByEmail"]
    private final List<String> jpqlQueries = new ArrayList<>();  // @Query 어노테이션의 JPQL 문자열
    private final List<String> nativeQueries = new ArrayList<>(); // nativeQuery=true 인 SQL 문자열

    public RepositoryInfo(String className, String entityClass) {
        this.className   = className;
        this.entityClass = entityClass;
    }

    public void addMethod(String methodName)       { methodNames.add(methodName); }
    public void addJpqlQuery(String jpql)          { jpqlQueries.add(jpql); }
    public void addNativeQuery(String sql)         { nativeQueries.add(sql); }

    public String getClassName()            { return className; }
    public String getEntityClass()          { return entityClass; }
    public List<String> getMethodNames()    { return methodNames; }
    public List<String> getJpqlQueries()    { return jpqlQueries; }
    public List<String> getNativeQueries()  { return nativeQueries; }

    /** 그래프 노드 ID prefix: "repo:UserRepository." */
    public String nodeIdPrefix() { return "repo:" + className + "."; }
}
