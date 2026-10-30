# Error Prone: the compiler's bug checks at ERROR

> Status: proven end to end on the MCP Java SDK (2026-10-08, `evidence/mcp-java-sdk.md`: measured, decided, installed, green, falsified) after acp-java.
> Each step is a **script**, an **AI** task, or a **stop** for the owner. Maven only. Error Prone
> runs on JDK 21 or newer; the profile activates by JDK, so a JDK 17 build compiles as before.

The gate: Error Prone's default-enabled checks through a Maven profile `errorprone` on the main
compile execution, with javac's `-Werror` so that a WARNING-level check fails the build like an
ERROR one, plus the checks the kit raises for a library (`SystemOut`; `CheckReturnValue` over
Reactor with a written list of droppable results). Style checks are disabled by name in the
profile with a reason in `configs/errorprone/disabled-checks.txt`; a real exception is a
`@SuppressWarnings` with a reason, never a disabled check.

## Steps

1. **AI** — the facts. `assess` shows the Java level and whether the compiler plugin replaces
   `default-compile` (the MCP SDK does, with `java-compile`); the profile must name the target's
   main compile execution id. A JDK 21+ must be on the machine; `./mvnw -v` says which runs.
2. **AI** — install in report mode: `maven/jvm.config` to `<target>/.mvn/jvm.config`;
   `configs/errorprone/reactor-ignorable-results.example.txt` to
   `<target>/config/errorprone/reactor-ignorable-results.txt` (only if the target uses Reactor;
   otherwise drop the `CheckReturnValue` options from the profile); the four properties
   (`errorprone.version`, `nullaway.version`, `errorprone.failOnWarning`, `errorprone.extraArgs`)
   and the profile from `maven/errorprone.profile.xml` into the root pom, with the execution id
   from step 1. Modules the owner left out of the gates get
   `<errorprone.extraArgs>-XepDisableAllChecks</errorprone.extraArgs>` in their own pom.
3. **Script** — `./jbang measure-errorprone <target>` (from elsewhere:
   `<kit>/jbang <kit>/scripts/ErrorProneMeasure.java <target>`). Runs the profile in report mode
   (every finding a warning, nothing fails), groups findings by check and module, and classifies
   each check from Error Prone's own metadata: **bug** when tagged LIKELY_ERROR, FRAGILE_CODE or
   CONCURRENCY or ERROR by default, or raised by the kit; **style** when tagged STYLE,
   SIMPLIFICATION, REFACTORING or PERFORMANCE; **untagged** otherwise (Error Prone leaves its
   Javadoc checks and some bug finders untagged alike). javac's own warnings appear as
   `javac:removal`, `javac:deprecation`, `javac:unchecked`, `javac:classpath`: the gate's
   `-Werror` fails on them too.
4. **Notify** — `ERRORPRONE_DISABLED: none` is the default for new decisions. Fix mechanically
   safe findings, including frequent style checks. Preserve existing explicit decisions; a count
   never justifies disabling a check. Inherited API-blocked findings follow the narrow member
   exception policy in `01-pass.md` and remain visible in full pass measurement.
5. **AI** — go green, in this order:
   1. javac's own warnings first. Until they are gone, javac under `-Werror` stops before Error
      Prone runs, so nothing else can be checked. A use of an API marked for removal is a real
      finding; a missing class on the compile classpath is a dependency to declare.
   2. Each **bug** finding fixed in the code, red-first where a test can show it; a named member exception only for an inherited API-blocked finding under `01-pass.md`.
   3. Each mechanically safe style finding fixed, regardless of count. Do not change a published
      supertype, bean return descriptor or TCK field type to clear a removal warning.
   One check per commit, tests green after each, counts in the body.
6. **Script** — `./jbang falsify <target> errorprone`: plants a dropped `new Exception(...)`
   (`DeadException`, ERROR by default), compiles the module under the profile, and passes only
   if the build went red and the output names `DeadException` on the planted file. It reports
   NOT PROVEN, not failure, when javac's own warnings stopped Error Prone from running.
7. **AI** — flip to fail is already the profile's shape (`errorprone.failOnWarning` true);
   `./mvnw -P errorprone verify` on JDK 21 is the target. Commit with the measurement; append
   the code base to `evidence/`, with the `assess` fact that predicted each real bug, or that none did.

## Known shapes

- **`-Werror` hides Error Prone behind javac's warnings.** javac counts `-Werror` warnings as
  errors, and `--should-stop=ifError=FLOW` then ends compilation before the Error Prone plugin
  runs. Report mode (`errorprone.failOnWarning=false`) sees everything; the gate sees Error
  Prone only once javac is clean. Found on the MCP SDK, which had two removal deprecations and
  one missing `javax.annotation` class.
- **Deprecated methods without `@InlineMe`** (`InlineMeSuggester`) and **missing Javadoc
  summaries** (`MissingSummary`) dominate a library's first run and are style: disabled with
  reasons on the SDK (42 and 20 findings).
- **`System.out` in a stdio transport** is the one legitimate `SystemOut`: a suppression with
  the reason, on that line.

Historical disable-by-frequency examples below describe earlier runs, not the 0.4.0 default.
