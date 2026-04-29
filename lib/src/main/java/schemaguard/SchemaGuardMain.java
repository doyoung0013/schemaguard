package schemaguard;

import schemaguard.cli.SchemaGuardCLI;
import picocli.CommandLine;

/**
 * SchemaGuard 정적 분석 도구 진입점.
 *
 * 실행:
 *   java -jar schemaguard.jar --source <소스경로> --migration <SQL파일경로>
 *   java -jar schemaguard.jar --help
 */
public class SchemaGuardMain {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SchemaGuardCLI()).execute(args);
        System.exit(exitCode);
    }
}