///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//SOURCES ../scripts/Plant.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Restoration must preserve a replacement file's ownership even when its bytes match the plant. */
public class PlantOwnershipTest {

	public static void main(String[] args) throws Exception {
		List<String> failures = new ArrayList<>();
		for (boolean existing : List.of(true, false)) {
			Path target = Files.createTempDirectory("guardrails-plant-ownership-");
			Path file = target.resolve("Example.java");
			if (existing) {
				Files.writeString(file, "original");
			}
			try (Plant plant = new Plant("ownership test", target)) {
				if (existing) {
					plant.replace(file, "planted");
				}
				else {
					plant.create(file, "planted");
				}
				// An editor's atomic save replaces the inode without changing the text.
				Path replacement = target.resolve("replacement.java");
				Files.writeString(replacement, "planted");
				Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			String name = existing ? "replacement of edited source" : "replacement of created probe";
			if (!Files.exists(file) || !Files.readString(file).equals("planted")) {
				failures.add(name);
			}
			else {
				System.out.println("PASS " + name + " preserved");
			}
		}
		Path target = Files.createTempDirectory("guardrails-plant-symlink-");
		Path outside = Files.createTempFile("guardrails-outside-", ".java");
		Files.writeString(outside, "outside original");
		Path link = target.resolve("Linked.java");
		try {
			Files.createSymbolicLink(link, outside);
			boolean refused = false;
			try (Plant plant = new Plant("symlink test", target)) {
				try {
					plant.replace(link, "planted through link");
				}
				catch (IOException expected) {
					refused = true;
				}
			}
			if (!refused || !Files.readString(outside).equals("outside original") || !Files.isSymbolicLink(link)) {
				failures.add("symlink must be refused before modifying its target");
			}
			else {
				System.out.println("PASS symlink and outside source left untouched");
			}
		}
		catch (UnsupportedOperationException e) {
			System.out.println("SKIP symlink test: filesystem does not support symbolic links");
		}
		if (!failures.isEmpty()) {
			throw new AssertionError("Failed ownership checks: " + failures);
		}
		System.out.println("PASS plant ownership checks");
	}
}
