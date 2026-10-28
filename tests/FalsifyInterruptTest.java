///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Ctrl-C and SIGTERM during falsify leave the target as it was, for each kind of plant: an edited
 * existing class (pmd, cpd, errorprone, spotbugs, jacoco), two new classes (archunit) and a new
 * probe (nullaway). The target's mvnw is a stand-in that records its pid, ignores SIGINT the way a
 * Maven still shutting down outlives it, and sleeps: the signal always lands mid-build, the JVM
 * cannot race the build to a finally block, and no Maven is needed. Linux only: it signals process
 * groups.
 */
public class FalsifyInterruptTest {

	record Result(int exit, String output) {
	}

	static Path kit;

	public static void main(String[] args) throws Exception {
		kit = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
		if (!System.getProperty("os.name").equals("Linux")) {
			System.out.println("SKIP falsify interrupt: signals process groups, Linux only");
			return;
		}
		for (String gate : List.of("pmd", "cpd", "errorprone", "spotbugs", "jacoco", "archunit", "nullaway")) {
			interrupted(gate, "INT", true);
			interrupted(gate, "TERM", false);
		}
		editedMeanwhileIsKept();
		existingFileIsNotOverwritten();
		System.out.println("PASS falsify interrupt cleanup");
	}

	/** Ctrl-C reaches the whole process group; a harness may signal only the JVM. Both must restore and stop the build. */
	static void interrupted(String gate, String signal, boolean group) throws Exception {
		Path target = fixture();
		Map<Path, String> before = sources(target);
		Process falsify = start(target, gate);
		try {
			long build = awaitBuild(target, falsify);
			long pid = group ? falsify.pid() : jvm(falsify);
			kill(signal, (group ? "-" : "") + pid);
			require(falsify.waitFor(30, TimeUnit.SECONDS), gate + " SIG" + signal + ": falsify exits", target);
			require(sources(target).equals(before), gate + " SIG" + signal + (group ? " to the group" : " to the JVM only") + ": sources as before", target);
			require(exited(build), gate + " SIG" + signal + ": build stopped", target);
		}
		finally {
			stop(falsify);
		}
	}

	/** A planted file someone edits during the run is not overwritten by the restore. */
	static void editedMeanwhileIsKept() throws Exception {
		Path target = fixture();
		Path planted = target.resolve("src/main/java/a/A.java");
		Process falsify = start(target, "pmd");
		try {
			awaitBuild(target, falsify);
			Files.writeString(planted, "// edited during the run\n");
			kill("INT", "-" + falsify.pid());
			require(falsify.waitFor(30, TimeUnit.SECONDS), "edited meanwhile: falsify exits", target);
			require(Files.readString(planted).equals("// edited during the run\n"), "edited meanwhile: the edit is kept, not restored over", target);
		}
		finally {
			stop(falsify);
		}
	}

	/** A target file at a plant's path is refused untouched, and nothing else is left planted. */
	static void existingFileIsNotOverwritten() throws Exception {
		Path target = fixture();
		Path existing = target.resolve("src/main/java/b/GuardrailsPlantedB.java");
		Files.writeString(existing, "target-owned");
		Map<Path, String> before = sources(target);
		Process falsify = start(target, "archunit");
		require(falsify.waitFor(60, TimeUnit.SECONDS) && falsify.exitValue() == 1, "existing file: falsify archunit refuses", target);
		require(sources(target).equals(before), "existing file: untouched, and the first planted class removed", target);
	}

	static Path fixture() throws Exception {
		Path target = Files.createTempDirectory("guardrails-interrupt-");
		Files.writeString(target.resolve("pom.xml"), "<project><!-- stand-in; mvnw never reads it --><profiles><profile><id>errorprone</id>"
				+ "<arg>-Xep:NullAway:ERROR</arg></profile></profiles></project>\n");
		Files.writeString(target.resolve("mvnw"), "#!/bin/sh\ntrap '' INT\necho $$ > \"$(dirname \"$0\")/build.pid\"\nexec sleep 120\n");
		target.resolve("mvnw").toFile().setExecutable(true);
		Path main = target.resolve("src/main/java");
		Files.createDirectories(main.resolve("a"));
		Files.createDirectories(main.resolve("b"));
		Files.createDirectories(target.resolve("src/test/java"));
		Files.writeString(main.resolve("a/A.java"), "package a;\n\npublic class A {\n}\n");
		Files.writeString(main.resolve("a/package-info.java"), "@org.jspecify.annotations.NullMarked\npackage a;\n");
		Files.writeString(main.resolve("b/B.java"), "package b;\n\npublic class B {\n}\n");
		Files.writeString(target.resolve("src/test/java/NoCyclesTest.java"), "class NoCyclesTest {\n}\n");
		return target;
	}

	static Process start(Path target, String gate) throws Exception {
		return new ProcessBuilder("setsid", kit.resolve("jbang").toString(), kit.resolve("scripts/Falsify.java").toString(), target.toString(), gate)
				.redirectErrorStream(true).redirectOutput(target.resolve("falsify.log").toFile()).start();
	}

	/** The stand-in build's pid, once falsify has planted and started it. */
	static long awaitBuild(Path target, Process falsify) throws Exception {
		Path pid = target.resolve("build.pid");
		for (int i = 0; i < 1500 && falsify.isAlive() && !(Files.exists(pid) && !Files.readString(pid).isBlank()); i++) {
			Thread.sleep(20);
		}
		require(Files.exists(pid), "the build starts", target);
		return Long.parseLong(Files.readString(pid).trim());
	}

	/** The JVM running Falsify: the jbang launcher may be a shell that starts it as a child. */
	static long jvm(Process falsify) {
		return Stream.concat(Stream.of(falsify.toHandle()), falsify.descendants())
				.filter(p -> p.info().command().map(c -> c.endsWith("/java")).orElse(false))
				.findFirst().orElseThrow(() -> new AssertionError("no JVM below " + falsify.pid())).pid();
	}

	/** Whether the process is gone within a few seconds: a stop signal is delivered, not awaited. */
	static boolean exited(long pid) throws Exception {
		for (int i = 0; i < 250 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) {
			Thread.sleep(20);
		}
		return !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	static void kill(String signal, String pid) throws Exception {
		new ProcessBuilder("kill", "-" + signal, "--", pid).inheritIO().start().waitFor();
	}

	static void stop(Process falsify) throws Exception {
		if (falsify.isAlive()) {
			kill("KILL", "-" + falsify.pid());
		}
	}

	static Map<Path, String> sources(Path target) throws Exception {
		Map<Path, String> files = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(target.resolve("src"))) {
			for (Path file : walk.filter(Files::isRegularFile).toList()) {
				files.put(target.relativize(file), Files.readString(file, StandardCharsets.UTF_8));
			}
		}
		return files;
	}

	static void require(boolean condition, String name, Path target) throws Exception {
		if (!condition) {
			Path log = target.resolve("falsify.log");
			throw new AssertionError(name + "\nfixture: " + target + "\n" + (Files.exists(log) ? Files.readString(log) : ""));
		}
		System.out.println("PASS " + name);
	}
}
