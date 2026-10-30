# JaCoCo floors

> Status: proven end to end on the MCP Java SDK (2026-10-08, `evidence/mcp-java-sdk.md`: measured, decided, installed, falsified) after acp-java.
> Each step is a **script**, an **AI** task, or a **stop** for the owner. Maven only.

The gate: each module's LINE and BRANCH covered ratio, its own classes exercised by its own
tests, checked at `verify` against a floor the module declares. The parent's default floor is
full coverage, so a new module with execution data fails until it declares measured floors.
JaCoCo can skip its check when execution data is absent: a 1.00 default alone cannot prove
coverage is gated. A floor is the coverage
measured from a clean run less 2 points, rounded down: enough for run-to-run variation of
concurrent tests, too little to lose a test class unnoticed. Raise a floor when coverage rises;
lower one only with a reason in the commit. Exclusions require an explicit scope decision.

## Steps

1. **Script** — `./jbang measure-jacoco <target>` (from elsewhere:
   `<kit>/jbang <kit>/scripts/JacocoMeasure.java <target>`). Needs nothing installed: names the
   agent and report goals on the command line. First it emits `TEST_SUPPORT_MODULES (decide)`
   if selected test-support scope is unanswered; relay it and wait before any measurement.
   `assess` asks this before the first gate too, regardless of gate order.
   With scope resolved, it runs each gated module's own tests under the
   agent, reads each `jacoco.xml`, and prints LINE and BRANCH coverage with the proposed floors.
   It reads surefire's `<argLine>` first: `@{name}` (late-bound) works and the agent is written
   into that property; `${name}` is interpolated before JaCoCo runs and the agent never attaches,
   which the script reports with the one-token fix. A failed Maven build prints its diagnostics
   and is not measured, including failures before tests.
   After a successful clean build, JaCoCo's bytecode instruction counters distinguish modules
   with executable code from abstract-only interfaces and other code with no instructions.
   Every selected module is accounted for. Modules with no executable code are named and need
   no floor. Executable modules without `target/site/jacoco/jacoco.xml` produce
   `JACOCO_MISSING_REPORTS (decide)` and a nonzero exit **before any floors are proposed or
   validated**, even if `JACOCO_FLOORS` is already recorded. Missing compiled output or LINE data
   also stops measurement; it cannot establish coverage for the standard layout.
   Relay the stop and wait. Add/enable default-phase own-module tests and fix agent/report
   configuration, explicitly exclude modules with a reason, or defer for a separately designed
   integration/aggregate coverage setup. Record the plan under `PROJECT_JACOCO_REMEDIATION`;
   for exclusions also update `EXCLUDED_MODULES`, preserving prior exclusions. A recorded plan
   does not bypass remeasurement. The script runs through `package` so sibling test-jars honor
   their packaging filters. It runs default-phase tests; opt-in `*IT` sources alone do not
   supply data; it does not silently activate integration profiles or fabricate zero coverage.
2. **Notify** — `JACOCO_FLOORS`: the floors per module, measured minus 2. The recommendation is
   the kit's default from unambiguous data, so the block is `notify`: record the line in
   `config/guardrails/decisions.md`, show it to the owner, continue. It becomes `decide` only
   when a module measured zero coverage or recorded floors omit a now-measured module. Missing
   reports stop earlier and are never treated as zero coverage. Existing floors are preserved
   in a completion proposal. The owner may edit the line to their own floors,
   never above measured.
3. **AI** — install: `jacoco-maven-plugin.version` 0.8.15 and the default floors 1.00 from
   `maven/properties.xml`, `jacoco.propertyName` set to surefire's argLine property when it is
   not `argLine`, the plugin block from `maven/jacoco.plugin.xml`, and in each gated module's
   pom the two floor properties `measure-jacoco` prints once the decision is recorded. Modules
   the owner left out set `<jacoco.skip>true</jacoco.skip>`; a module with no executable code
   is explicitly reported and declares nothing. Check that every selected executable module
   produces execution data and a report; a successful skipped `jacoco:check` is not proof.
   `./mvnw verify` is now the gate.
4. **Script** — `./jbang falsify <target> jacoco`: plants 800 never-executed statements in the
   first gated class, runs that module's `verify` with the other gates off, and passes only if
   `jacoco:check` reports the line floor broken.
5. **AI** — the first run is a bug hunt (acp-java: agents registered by class were created per
   request, found by writing tests to hold a floor). Code reached only from another module's
   tests counts as uncovered in its own module: that is a test to write there, not a floor to
   lower. Commit with the measured ratios and floors in the body; append to `evidence/` with
   the `assess` fact that predicted the finding (a module with main code and no tests, or a
   low tests-per-main ratio), or that none did.

## Known shapes

- **Surefire's own `argLine`.** A pom that sets `<argLine>${someProperty} ...</argLine>` must use
  `@{someProperty}`, or JaCoCo's agent is silently dropped and the report says "missing
  execution data file". The MCP SDK had this.
- **Gated test support (for example, a TCK).** `TEST_SUPPORT_MODULES: gate` includes its own
  executable main classes in exactly the same per-module measurement. Concrete consumers may
  execute abstract TCK tests from their own test JVMs, but those execution files and reports
  belong to the consumers. This script does not aggregate them back into the TCK. With no own
  tests/report the TCK triggers `JACOCO_MISSING_REPORTS`; it gets neither an invented zero floor
  nor an automatic exemption. Add tests in the owning module, ask the owner for an exclusion,
  or defer to an explicitly designed aggregate setup. Abstract methods without bodies are not
  executable; constructors, default methods and concrete helper methods are.

Regression evidence: `evidence/jacoco-completeness.md`. JaCoCo documents
[bytecode counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html) and
[per-project reports](https://www.jacoco.org/jacoco/trunk/doc/report-mojo.html).

In 0.4.0, write own-module tests when calibration would produce a zero floor. `JACOCO_OWN_TESTS` is remediation, not permission to record zero. Both modes require positive measured floors; legacy positive floors are preserved.
