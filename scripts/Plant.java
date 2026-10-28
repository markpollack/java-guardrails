import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The source changes one falsify run plants in a target, undone on close and again from a JVM
 * shutdown hook, so Ctrl-C or a SIGTERM during the build leaves the target as it was. A file is
 * restored only while it is the same regular file and holds exactly what was planted: an edit
 * or replacement made meanwhile is left alone and named. Symbolic-link sources are refused.
 * Use it in try-with-resources around the planting and the build.
 */
final class Plant implements AutoCloseable {

	/** What a planted file held before (null: the plant created it) and what the plant last wrote. */
	private record Change(byte[] original, byte[] planted, Object fileKey) {
	}

	private final String label;
	private final Path target;
	private final Map<Path, Change> changes = new LinkedHashMap<>();
	private final Thread shutdown;
	private boolean closed;

	Plant(String label, Path target) {
		this.label = label;
		this.target = target;
		shutdown = new Thread(this::undo, "guardrails-plant-undo");
		Runtime.getRuntime().addShutdownHook(shutdown);
	}

	/** Plants a new file; refuses, changing nothing, when the path already exists. */
	synchronized void create(Path file, String source) throws IOException {
		requireOpen();
		byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
		Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
		changes.put(file, new Change(null, bytes, regularFile(file).fileKey()));
	}

	/** Replaces the content of an existing file, or of a file this plant created. */
	synchronized void replace(Path file, String source) throws IOException {
		requireOpen();
		Change change = changes.get(file);
		if (change != null) {
			requireUnchanged(file, change);
		}
		Object fileKey = change != null ? change.fileKey() : regularFile(file).fileKey();
		byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
		byte[] original = change != null ? change.original() : Files.readAllBytes(file);
		Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
		changes.put(file, new Change(original, bytes, fileKey));
	}

	@Override
	public void close() {
		undo();
		try {
			Runtime.getRuntime().removeShutdownHook(shutdown);
		}
		catch (IllegalStateException e) {
			// the JVM is shutting down and the hook is running or has run
		}
	}

	/** Restores every planted file, newest first; never throws, so a shutdown hook finishes. */
	private synchronized void undo() {
		if (closed) {
			return;
		}
		closed = true;
		List<Path> files = new ArrayList<>(changes.keySet());
		for (int i = files.size() - 1; i >= 0; i--) {
			Path file = files.get(i);
			Change change = changes.get(file);
			try {
				if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				requireUnchanged(file, change);
				if (change.original() == null) {
					Files.delete(file);
					System.out.println(label + ": removed " + target.relativize(file));
				}
				else {
					Files.write(file, change.original(), StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
					System.out.println(label + ": restored " + target.relativize(file));
				}
			}
			catch (IOException e) {
				System.err.println(label + ": NOT RESTORED " + file + ": " + e.getMessage());
			}
		}
	}

	private void requireOpen() throws IOException {
		if (closed) {
			throw new IOException(label + ": interrupted; nothing more is planted");
		}
	}

	private static void requireUnchanged(Path file, Change change) throws IOException {
		if (!Objects.equals(regularFile(file).fileKey(), change.fileKey())) {
			throw new IOException("replaced since it was planted; left as it is");
		}
		if (!Arrays.equals(Files.readAllBytes(file), change.planted())) {
			throw new IOException("changed since it was planted; left as it is");
		}
	}

	private static BasicFileAttributes regularFile(Path file) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile()) {
			throw new IOException("refusing a non-regular or symbolic-link source: " + file);
		}
		return attributes;
	}
}
