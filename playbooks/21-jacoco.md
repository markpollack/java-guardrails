# JaCoCo floors

> Status: proven end to end on the MCP Java SDK (2026-10-08, `evidence/mcp-java-sdk.md`: measured, decided, installed, falsified) after acp-java.
> Each step is a **script**, an **AI** task, or a **stop** for the owner. Maven only.

The gate: each module's LINE and BRANCH covered ratio, its own classes exercised by its own
tests, checked at `verify` against a floor the module declares. The parent's default floor is
full coverage, so a new module fails until it declares measured floors. A floor is the coverage
measured from a clean run less 2 points, rounded down: enough for run-to-run variation of
concurrent tests, too little to lose a test class unnoticed. Raise a floor when coverage rises;
lower one only with a reason in the commit. Nothing is excluded.

## Steps

1. **Script** — `./jbang measure-jacoco <target>` (from elsewhere:
   `<kit>/jbang <kit>/scripts/JacocoMeasure.java <target>`). Needs nothing installed: names the
   agent and report goals on the command line, runs each gated module's own tests under the
   agent, reads each `jacoco.xml`, and prints LINE and BRANCH coverage with the proposed floors.
   It reads surefire's `<argLine>` first: `@{name}` (late-bound) works and the agent is written
   into that property; `${name}` is interpolated before JaCoCo runs and the agent never attaches,
   which the script reports with the one-token fix. A red test suite is not measured.
2. **Stop** — `JACOCO_FLOORS`: the floors per module, measured minus 2 as proposed, or the
   owner's own, never above measured. One line in `config/guardrails/decisions.md`.
3. **AI** — install: `jacoco-maven-plugin.version` 0.8.15 and the default floors 1.00 from
   `maven/properties.xml`, `jacoco.propertyName` set to surefire's argLine property when it is
   not `argLine`, the plugin block from `maven/jacoco.plugin.xml`, and in each gated module's
   pom the two floor properties `measure-jacoco` prints once the decision is recorded. Modules
   the owner left out set `<jacoco.skip>true</jacoco.skip>`; a module with no executable code
   passes an empty ratio and declares nothing. `./mvnw verify` is now the gate.
4. **Script** — `./jbang falsify <target> jacoco`: plants 800 never-executed statements in the
   first gated class, runs that module's `verify` with the other gates off, and passes only if
   `jacoco:check` reports the line floor broken.
5. **AI** — the first run is a bug hunt (acp-java: agents registered by class were created per
   request, found by writing tests to hold a floor). Code reached only from another module's
   tests counts as uncovered in its own module: that is a test to write there, not a floor to
   lower. Commit with the measured ratios and floors in the body; append to `evidence/`.

## Known shapes

- **Surefire's own `argLine`.** A pom that sets `<argLine>${someProperty} ...</argLine>` must use
  `@{someProperty}`, or JaCoCo's agent is silently dropped and the report says "missing
  execution data file". The MCP SDK had this.
- **Coverage that lives in a test-support module.** When the real tests are abstract classes
  published in a `src/main/java` module and run by other modules, a module's own-test coverage
  can be low (the SDK core: 30% line). The floor records today's truth; raising it means tests
  in the module that owns the code.
