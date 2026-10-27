# NullAway compiler proof

Run from the kit root on JDK 21+:

```
./jbang tests/NullAwayTest.java .
```

The Java runner creates an isolated temporary Maven target using the shipped Error Prone profile,
properties and JVM arguments. These wrapper files are Apache Maven Wrapper 3.3.2 (Apache-2.0),
using Maven 3.9.9. The runner keeps its scratch directory for inspection and prints the path.

It proves a real NullAway diagnostic appears in report mode, the normal compile fails, fixing the
source clears it, and falsification catches a planted nullable return. Negative controls disable
the checker, remove the package annotation, collide with an existing target file and break the
build. None can pass proof or overwrite target sources. An unmarked child package stays outside
its parent's package default. The new probe is removed after both positive and negative runs.
