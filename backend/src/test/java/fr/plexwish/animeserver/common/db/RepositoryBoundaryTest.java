package fr.plexwish.animeserver.common.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garde-fou du refacto « repositories » : l'accès à la base (SQL, JDBC, JPA, Panache) ne se fait que dans les classes
 * {@code *Repository} et dans {@code common/db}. Une resource ou un service qui écrit du SQL fait échouer ce test.
 */
class RepositoryBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** Accès à la base : JDBC, JPA, Panache « active record ». */
    private static final Pattern DB_ACCESS = Pattern.compile(
            "\\b(prepareStatement|prepareCall|createStatement|createNativeQuery|createQuery|getConnection)\\s*\\("
                    + "|\\b(AgroalDataSource|DataSource|EntityManager|PanacheEntityBase|PanacheEntity)\\b");

    /** Requête SQL dans une chaîne ou un bloc de texte (mots-clés en majuscules, comme dans tout le projet). */
    private static final Pattern SQL = Pattern.compile(
            "\\bSELECT\\b[\\s\\S]{0,400}?\\bFROM\\b|\\bINSERT\\s+INTO\\b|\\bDELETE\\s+FROM\\b|\\bUPDATE\\s+[a-z_]+\\s+(?:[a-z]+\\s+)?SET\\b"
                    + "|\\bTRUNCATE\\s+[a-z_]+");

    private static boolean allowed(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith("Repository.java") || MAIN.relativize(file).toString().replace('\\', '/').contains("/common/db/");
    }

    @Test
    void noDatabaseAccessOutsideRepositories() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).filter(f -> !allowed(f)).toList()) {
                String code = withoutComments(Files.readString(file));
                for (Pattern p : List.of(DB_ACCESS, SQL)) {
                    Matcher m = p.matcher(code);
                    if (m.find()) {
                        offenders.add(MAIN.relativize(file) + " : « " + m.group().replaceAll("\\s+", " ") + " »");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Accès à la base hors d'un *Repository :\n" + String.join("\n", offenders));
    }

    @Test
    void repositoriesAreFound() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            long n = files.filter(f -> f.getFileName().toString().endsWith("Repository.java")).count();
            assertTrue(n >= 19, "repositories attendus dans " + MAIN.toAbsolutePath() + " : " + n);
        }
    }

    @Test
    void detectorCatchesSqlAndJdbc() {
        assertTrue(DB_ACCESS.matcher("try (PreparedStatement st = c.prepareStatement(sql))").find());
        assertTrue(DB_ACCESS.matcher("@Inject AgroalDataSource dataSource;").find());
        assertTrue(DB_ACCESS.matcher("public class User extends PanacheEntityBase {").find());
        assertTrue(SQL.matcher("\"SELECT id, title FROM anime WHERE id = ?\"").find());
        assertTrue(SQL.matcher("\"\"\"\n    UPDATE remux_job SET status = 'QUEUED'").find());
        assertTrue(SQL.matcher("UPDATE episode e SET media_file_id = v.file_id").find());
        assertTrue(SQL.matcher("DELETE FROM web_job WHERE cache_key = ?").find());
        assertTrue(!SQL.matcher("// sélection : on garde le fichier le plus fiable").find());
    }

    /** Retire les commentaires (la Javadoc peut citer une table) ; les chaînes restent. */
    static String withoutComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        boolean inString = false;
        boolean inTextBlock = false;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (inTextBlock) {
                if (code.startsWith("\"\"\"", i)) {
                    inTextBlock = false;
                    out.append("\"\"\"");
                    i += 3;
                } else {
                    out.append(c);
                    i++;
                }
            } else if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < code.length()) {
                    out.append(code.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
            } else if (code.startsWith("\"\"\"", i)) {
                inTextBlock = true;
                out.append("\"\"\"");
                i += 3;
            } else if (c == '"') {
                inString = true;
                out.append(c);
                i++;
            } else if (c == '\'' && i + 2 < code.length()) {
                // caractère : '"' ne doit pas ouvrir une chaîne
                int end = code.indexOf('\'', i + (code.charAt(i + 1) == '\\' ? 3 : 2));
                end = end < 0 ? i : end;
                out.append(code, i, end + 1);
                i = end + 1;
            } else if (code.startsWith("//", i)) {
                int end = code.indexOf('\n', i);
                i = end < 0 ? code.length() : end;
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
