import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared by every script through {@code //SOURCES Util.java}. */
final class Util {
	private Util() {
	}

	/** The target project's Maven wrapper command for this operating system. */
	static String mvnw(Path target) {
		boolean windows = System.getProperty("os.name").startsWith("Windows");
		return target.resolve(windows ? "mvnw.cmd" : "mvnw").toString();
	}

	/** The target project directory: the first argument, else the working directory. */
	static Path target(String[] args) {
		return (args.length > 0 && !args[0].startsWith("--")) ? Path.of(args[0]).toAbsolutePath().normalize()
				: Path.of("").toAbsolutePath();
	}

	/**
	 * The Java language level the target compiles for, read from the root pom.xml
	 * ({@code java.version}, {@code maven.compiler.release} or {@code maven.compiler.source}), else
	 * from a Gradle build file's {@code languageVersion} or {@code sourceCompatibility}; 17 if none.
	 */
	static String javaVersion(Path target) {
		String[] files = { "pom.xml", "build.gradle.kts", "build.gradle" };
		Pattern[] patterns = {
				Pattern.compile("<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source|release)>\\s*(?:1\\.)?(\\d+)\\s*<"),
				Pattern.compile("(?:languageVersion\\s*(?:=|\\.set\\()\\s*JavaLanguageVersion\\.of\\(|sourceCompatibility\\s*=?\\s*(?:JavaVersion\\.VERSION_)?['\"]?(?:1[._])?)(\\d+)") };
		for (int f = 0; f < files.length; f++) {
			Path file = target.resolve(files[f]);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			try {
				Matcher m = patterns[f == 0 ? 0 : 1].matcher(Files.readString(file));
				if (m.find()) {
					return m.group(1);
				}
			}
			catch (IOException e) {
				// fall through to the default
			}
		}
		return "17";
	}

	static void notYetImplemented(String alias) {
		System.err.println("./jbang " + alias + ": not implemented yet; see playbooks/ for the procedure it will automate");
		System.exit(2);
	}
}
