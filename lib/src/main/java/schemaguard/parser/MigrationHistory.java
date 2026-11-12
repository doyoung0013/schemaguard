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
 * 파일 이름의 V 번호로 마이그레이션 순서를 정하고, 검사하는 파일보다 앞선 파일을 찾는다.
 *
 * 이름이 V<번호>__<설명>.sql 또는 V<번호>.sql 인 파일만 번호가 있다고 본다. 번호는 숫자 단위로 비교한다
 * (V2 &lt; V10, V1.2 &lt; V1.10). R__ 로 시작하는 파일처럼 번호가 없는 파일은 순서를 알 수 없어 이력에서 뺀다.
 * 하위 폴더는 보지 않는다. 검사 파일 이름에 V 번호가 없으면 앞선 파일을 알 수 없으므로 빈 목록이다.
 *
 * 마이그레이션 도구의 설정(적용 이력 테이블, 여러 위치 등)은 읽지 않는다. 파일 이름만 본다.
 */
public final class MigrationHistory {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)(?:__.*)?\\.sql$");

    private MigrationHistory() {}

    /** 검사 파일과 같은 폴더에서 V 번호가 더 작은 파일 */
    public static List<File> earlierThan(File migration) {
        return earlierThan(migration, migration.getAbsoluteFile().getParentFile());
    }

    /** historyDir 에서 검사 파일보다 V 번호가 작은 파일을 번호 순서로 */
    public static List<File> earlierThan(File migration, File historyDir) {
        List<BigInteger> current = version(migration.getName());
        File dir = historyDir;
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

    /** 파일 이름에 V 번호가 있는지 (V<번호>__<설명>.sql, V<번호>.sql) */
    public static boolean hasVersion(File file) {
        return version(file.getName()) != null;
    }

    /**
     * 폴더 안의 .sql 파일을 V 번호 순서로 돌려준다. 하위 폴더는 보지 않는다.
     * V 번호가 있는 파일을 번호 순서로 먼저, 번호가 없는 파일(R__ 등)은 이름순으로 뒤에 둔다.
     */
    public static List<File> sqlFilesIn(File dir) {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".sql"));
        if (files == null) return List.of();
        List<File> out = new ArrayList<>();
        for (File f : files) if (f.isFile()) out.add(f);
        return inApplyOrder(out);
    }

    /** V 번호가 있는 파일을 번호 순서로 먼저, 번호가 없는 파일은 이름순으로 뒤에 둔다. */
    public static List<File> inApplyOrder(List<File> files) {
        List<File> out = new ArrayList<>(files);
        out.sort((a, b) -> {
            List<BigInteger> va = version(a.getName()), vb = version(b.getName());
            if (va != null && vb != null) {
                int c = compare(va, vb);
                if (c != 0) return c;
            } else if (va != null || vb != null) {
                return va != null ? -1 : 1;
            }
            int c = a.getName().compareTo(b.getName());
            return c != 0 ? c : a.getPath().compareTo(b.getPath());
        });
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
