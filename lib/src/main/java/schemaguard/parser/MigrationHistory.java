package schemaguard.parser;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 검사하는 마이그레이션보다 먼저 적용되는 마이그레이션 파일을 찾는다.
 *
 * Flyway 이름 규칙(V<버전>__<설명>.sql)을 따른다. 버전은 숫자 단위로 비교한다(V2 &lt; V10, V1.2 &lt; V1.10).
 * community 처럼 설명 없이 V2.sql 로 쓴 파일도 받는다. 반복 실행 마이그레이션(R__)은 순서가 없어서 제외한다.
 * 같은 폴더만 본다. 검사 파일 이름이 이 규칙을 따르지 않으면 이력을 알 수 없으므로 빈 목록이다.
 */
public final class MigrationHistory {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)(?:__.*)?\\.sql$");

    private MigrationHistory() {}

    public static List<File> earlierThan(File migration) {
        List<BigInteger> current = version(migration.getName());
        File dir = migration.getAbsoluteFile().getParentFile();
        if (current == null || dir == null) return List.of();

        File[] files = dir.listFiles();
        if (files == null) return List.of();

        List<File> out = new ArrayList<>();
        for (File f : files) {
            List<BigInteger> v = version(f.getName());
            if (f.isFile() && v != null && compare(v, current) < 0) out.add(f);
        }
        out.sort(Comparator.<File, List<BigInteger>>comparing(f -> version(f.getName()), MigrationHistory::compare)
                .thenComparing(File::getName));
        return out;
    }

    static List<BigInteger> version(String fileName) {
        Matcher m = VERSIONED.matcher(fileName);
        if (!m.matches()) return null;
        return Arrays.stream(m.group(1).split("[._]")).map(BigInteger::new).toList();
    }

    /** 1.2 == 1.2.0 */
    static int compare(List<BigInteger> a, List<BigInteger> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            BigInteger x = i < a.size() ? a.get(i) : BigInteger.ZERO;
            BigInteger y = i < b.size() ? b.get(i) : BigInteger.ZERO;
            int c = x.compareTo(y);
            if (c != 0) return c;
        }
        return 0;
    }
}
