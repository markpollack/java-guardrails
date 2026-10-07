import java.nio.file.Path;

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
		return (args.length > 0 && !args[0].startsWith("--")) ? Path.of(args[0]).toAbsolutePath() : Path.of("").toAbsolutePath();
	}

	static void notYetImplemented(String alias) {
		System.err.println("./jbang " + alias + ": not implemented yet; see playbooks/ for the procedure it will automate");
		System.exit(2);
	}
}
