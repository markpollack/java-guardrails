# Evidence: MCP Java SDK (modelcontextprotocol/java-sdk), October 2026

The second code base. Target: upstream `main` at `73a9a8fd` (2026-10-06), a fresh clone, Java 17,
seven Maven modules plus three conformance runners; 129 main-source files. Only the PMD gate has
been applied so far; this file grows with each gate.

## PMD and CPD, measured 2026-10-08 before any change

`./jbang measure` over every `src/main/java`, PMD 7.28.0, main sources only. Population sizes:
1,823 methods and constructors (1,847 parameter lists), 311 classes with methods, 375 types for
class NCSS, 473 block-bodied lambdas.

| Metric | Reference | p50 / p90 / p99 / max | Fail at reference | Fit |
|---|---|---|---|---|
| Cognitive complexity, method | 15 | 0 / 3 / 15 / 59 | 19 (1.0%) | yes |
| Cyclomatic complexity, method | 10 | 1 / 2 / 9 / 25 | 17 (0.9%) | yes |
| Cyclomatic complexity, class total | 80 | 4 / 22 / 60 / 98 | 1 (0.3%) | yes |
| NCSS, method | 30 | 3 / 7 / 26 / 94 | 11 (0.6%) | yes |
| NCSS, class, nested counted | 300 | 15 / 75 / 347 / 2,278 | 4 (1.1%) | yes |
| Statements in one lambda | 30 | 3 / 8 / 32 / 61 | 7 (1.5%) | yes |
| Parameters | 7 | 1 / 2 / 7 / 15 | 22 (1.2%) | yes |
| CPD, tokens, per module | 100 | — | 83 blocks: mcp-core 33, mcp-test 50 | n/a |

**Every reference threshold from acp-java fits this code base unchanged**: on each metric about
one percent of the population fails, and what fails is the code a reader would name first. The
real gate (`./mvnw pmd:pmd pmd:cpd` with the kit's ruleset and plugin block in the root pom)
reported exactly these counts, rule by rule: 81 PMD violations (mcp-core 73, mcp-test 7, one
conformance runner 1) and 83 CPD duplications.

### What fails, by shape

- **Three HTTP client transport methods** carry the complexity: `sendMessage` (cognitive 59),
  `connect` (53) and `reconnect` (41), each a reactive chain with the logic in nested lambdas;
  four of the seven long lambdas are inside them.
- **Two identical `completionCompleteRequestHandler` methods** (cognitive 40 each) in the
  stateful and stateless servers; CPD also reports the servers copying each other (two blocks of
  282 and 260 tokens).
- **Servlet `doPost`/`doGet`/`doDelete`** methods: NCSS 94, 55, 51, 45, 33 and the highest
  cyclomatic complexity (25); wide, flat request handling.
- **`McpAsyncClient`'s constructor**: cognitive 26, cyclomatic 19, NCSS 73, and the one class
  over the cyclomatic total (98).
- **Feature records** (`McpClientFeatures`, `McpServerFeatures`, `McpStatelessServerFeatures`)
  and six `McpSchema` records fail on parameter count (9 to 15 components); the same records'
  canonical constructors fail cyclomatic complexity through null-coalescing of each component.
- **Class NCSS**: `McpSchema` 2,278, `McpServer` (an interface holding its builders) 665,
  `AbstractMcpClientServerIntegrationTests` 786, `HttpServletStreamableServerTransportProvider`
  347.
- **CPD**: 50 of 83 blocks are in `mcp-test`, a published test-support module whose abstract
  test classes repeat scenarios across transports (blocks of 250 to 426 tokens).

### Findings for the kit

1. **The knee is not computed; the reference fits.** A gap search in [reference, 2×reference]
   found gaps of 1.26x to 1.38x on every metric and, searching above the reference, proposed
   sparing exactly the worst code (cyclomatic 19 instead of 10 to spare two methods; class
   NCSS 347 to spare `HttpServletStreamableServerTransportProvider`). Removed. `measure` now
   prints the cost at the reference and a fit check (fits when at most 5% fail). Both code
   bases so far fit every reference.
2. **The wire-record-container exemption does not match `McpSchema`**, which has a static
   logger (allowed by the XPath) and a nested `public static final class ErrorCodes` of int
   constants (not allowed: the XPath requires nested types to be records, interfaces or
   enums). Generalising the exemption to admit nested classes with only static members is an
   owner decision; until then `McpSchema` is a class-NCSS violation.
3. **`McpServer` is an interface of 665 statements** because it holds four nested builder
   classes (`AsyncSpecification`, `SyncSpecification` and their stateless twins, 42 to 44
   cyclomatic each). The acp-java rationale ("API breadth is not tangling") argues for
   splitting the builders out rather than excluding them; owner decision.
4. **Test code in `src/main/java`** (`mcp-test`, `conformance-tests`) should be decided
   explicitly: left out of the gate as test code, or held to it as published code.
   `measure --exclude mcp-test,conformance-tests` gives the core-only cost: 73 PMD and
   33 CPD.
5. **`measure` had to cross PMD's find boundaries** (nested types and lambdas) to see what the
   rules see; before that fix it under-counted by a third. The counts now match the gate
   exactly, which is the test the script carries.

## Falsification, 2026-10-08

`./jbang falsify <sdk> pmd` planted a seven-parameter, nested-loop, 30-statement method in the
first conformance runner class, ran `pmd:check` on that module, and read the module's
`target/pmd.xml`: red, with the planted method reported by NcssCount, CognitiveComplexity,
CyclomaticComplexity and ExcessiveParameterList. `./jbang falsify <sdk> cpd` planted two copies
of a 100-plus-token method: red, reported by CPD. Both restored the file byte for byte. The same
command on the ungated `main` branch fails, as it must.

## Decisions and the stop protocol, 2026-10-08

`measure` now ends with a `STOP <ID>` block per missing decision and reads
`config/guardrails/decisions.md` in the target. On the SDK with no file it emitted three blocks:
`TEST_SUPPORT_MODULES` (naming `mcp-test` for its test libraries on the compile classpath and
seven of ten classes named like tests, and the three conformance modules by name),
`PMD_MECHANISM` (75 PMD, 83 CPD; fix-all recommended, under 100) and `CLASS_SIZE` (`McpSchema`,
a wire schema of 69 records; `McpServer`, an interface carrying eight classes). The owner
answered the first two; with the file holding `TEST_SUPPORT_MODULES: exclude mcp-test,
conformance-tests` and `PMD_MECHANISM: fix-all`, the re-run measured `mcp-core` and the small
modules only, printed 67 PMD and 33 CPD, and emitted `CLASS_SIZE` alone. The third decision is
open.

**Record canonical constructors are exempt** from `ExcessiveParameterList` since this code base
(owner decision; the reason is in the ruleset). On `mcp-core` the real gate went from 73 to 67
violations: the six feature-record canonical constructors dropped out; the four `McpSchema`
constructors that remain (`Resource`, `ResourceTemplate`, `Tool`, `CreateMessageRequest`) are
secondary constructors with fewer parameters than components, and stay gated.

## Going green, 2026-10-08

Mechanism: fix-all on `mcp-core`, the test-support modules (`mcp-test`, the three conformance
runners) left out by owner decision with `pmd.skip`/`cpd.skip` in their poms. Starting cost
after the decisions and the shape exemptions: 57 PMD violations and 33 CPD blocks, all in
`mcp-core`. Five parallel implementation sessions, one per file group, each on its own
worktree and branch, merged with one nine-line conflict; then one more commit for the last
duplicate block.

Result: `./mvnw verify` on the whole reactor runs the gate at `verify` with `failOnViolation`
true and passes `mcp-core` with **0 PMD, 0 CPD**; `mcp-core` tests pass (the 12 errors in
`mcp-test` are Testcontainers failing to start on this machine, identical on the untouched
baseline). Falsified afterwards: the planted method reported by NcssCount,
CognitiveComplexity, CyclomaticComplexity and ExcessiveParameterList; the planted copies by
CPD; both restored.

| | Before | After |
|---|---|---|
| PMD violations, `mcp-core` | 57 (after decisions; 73 before them) | 0 |
| CPD blocks at 100 tokens, `mcp-core` | 33 | 0 |
| Worst method, cognitive complexity | 59 (`sendMessage`) | under 15 everywhere; `sendMessage` 4 |
| `McpAsyncServer` | 1,133 lines | 838 |
| `McpAsyncClient` | 1,170 lines | 937 |
| `HttpServletStreamableServerTransportProvider` | 1,136 lines, class NCSS 347 | 912, under 300 |
| Commits | | 27, one hotspot each, tests green after each |
| Main-source change | | 41 files, +3,471 / -3,391 lines, 15 new package-private or public helper classes |

What the fixes were, by shape:
- **Reactive chains in named steps.** `sendMessage`, `connect`, `reconnect` each became a
  sequence of named private methods; the three long lambdas inside them disappeared with
  them. Same for the stdio read and write loops and the servlet `doPost`/`doGet`/`doDelete`.
- **Copies between twins.** The stateful and stateless async servers shared thirteen blocks;
  they now extend one package-private base with an options holder, and completion checks
  live in one class. The feature records (`McpClientFeatures`, `McpServerFeatures`,
  `McpStatelessServerFeatures`) got one defaulting helper.
- **Handler registration as a table.** `McpAsyncClient`'s constructor registered seven
  handlers one by one; a loop over a table does it, and the schema cache and elicitation
  defaults moved to their own classes (the acp-java pattern).
- **Builders for seven- and eight-argument constructors** on the session classes; the
  servers' constructors take an options object.
- **Shared base builders** for the HTTP client transports and the servlet transports,
  declaring the common setters once.
- **Null-coalescing ternaries** in record canonical constructors (cyclomatic 11 to 12 from
  one ternary per component) replaced by branch-free helpers.

Caveats for a real adoption, not for the proof:
- The shared base builders change the erased return type of the common setters (source
  compatible, binary incompatible for already-compiled callers). The SDK's versioning policy
  speaks of callers' source; an adopter should decide whether binary compatibility is owed.
- Two deprecated package-private record constructors were removed; two package-private
  session constructors became builders; a test subclass in `mcp-test` was updated to pass a
  configured builder. All recorded in the commit messages.
- Log categories moved with the extracted code in the servers (message texts unchanged).

## Error Prone, 2026-10-08, same day

The kit's profile installed on the SDK branch (its compile execution is `java-compile`, not
`default-compile`; the profile names it), `.mvn/jvm.config`, the Reactor droppable-results
list, test-support modules set to `-XepDisableAllChecks`. Report mode over the whole reactor
in 16 seconds.

| | Count |
|---|---|
| Findings, whole reactor, 28 checks | 179 |
| In the gated modules (`mcp-core`, two Jackson modules, `mcp`) | 114 |
| After `InlineMeSuggester` (42) and `MissingSummary` (20) disabled by decision | 55 (incl. 3 javac warnings) |
| Bug-tagged by Error Prone's own metadata | 10 across 5 checks |
| After the fix session | **0**; the gate compiles with `-Werror` on |

**Real bugs, three:** a boxed `Boolean` compared by identity (`result.isError() == Boolean.TRUE`)
in the tool-output schema cache; the stdio client reading the server's stdout and stderr in the
platform default charset while writing UTF-8; and two authorization-handler interfaces whose
`NOOP` constant instantiated a nested subclass during interface initialisation
(`ClassInitializationDeadlock`). Plus a classpath gap: Reactor's `@Nullable` is meta-annotated
with JSR-305, which was not on `mcp-core`'s compile classpath (now `provided`).

**Suppressions, four, each with its reason on the line:** `removal` on the deprecated
authorization-handler builder overload kept for compatibility; `ReferenceEquality` on
`McpError`'s self-cause loop (Throwable's idiom); `SystemOut` on the stdio server transport,
which owns `System.out`; and `-Xlint:-requires-transitive-automatic` on the `mcp` aggregator
module alone, whose transitive requires of automatic modules is its documented design.

**Findings for the kit:**
1. **`-Werror` hides Error Prone behind javac's own warnings.** javac counts `-Werror` warnings
   as errors and `--should-stop=ifError=FLOW` ends compilation before Error Prone runs, so on
   a module with any plain javac warning (two removal deprecations, one missing annotation
   class here) the gate is red for the right reason while Error Prone's findings are
   invisible and a plant is not checked. acp-java never had a javac warning. `measure` now
   lists javac's warnings as findings; `falsify errorprone` reports NOT PROVEN in that state;
   the playbook orders javac's warnings first.
2. **Classification from Error Prone's own metadata works, with one gap:** many checks are
   untagged, Javadoc checks and bug finders alike (`BoxedPrimitiveEquality` is an ERROR with no
   tag). Rule: bug when tagged LIKELY_ERROR, FRAGILE_CODE or CONCURRENCY or ERROR by default;
   style when tagged STYLE, SIMPLIFICATION, REFACTORING or PERFORMANCE; untagged to the owner
   with a recommendation from the count.
3. **Two style checks dominate a library's first run** and are now kit defaults with reasons:
   `InlineMeSuggester` and `MissingSummary`.
4. **The compile execution id is a per-target fact.** A build that replaces `default-compile`
   (this one) needs the profile to name its own execution.

Falsified on the finished branch: a planted dropped `new IllegalArgumentException(...)` turned
the gate red and the output named `DeadException` on the planted file; restored. 
`./mvnw -P errorprone verify` on JDK 21: `mcp-core`, the Jackson modules and `mcp` green with
542 tests; `mcp-test` keeps its 12 Testcontainers errors from the untouched baseline. The PMD
gate stayed at 0. Owner decision `ERRORPRONE_DISABLED` recorded as the script's recommendation,
owner to confirm.

## SpotBugs, 2026-10-08, same day

The kit's gate installed: one analysis at `verify`, effort Max, threshold Medium, reporting
to rank 20, the include filter keeping rank 9 or less plus the 32 concurrency patterns at any
rank; test-support modules skip by decision; the `mcp` module, a `module-info.java` alone,
skips because SpotBugs has nothing to open there. The exclude file starts empty. 18 seconds
per analysis over the reactor.

| | Count |
|---|---|
| Findings at any rank, gated modules | 289 |
| Of those, `EI_EXPOSE_REP` and `EI_EXPOSE_REP2` (rank 18, records handing out what they hold) | 269 |
| **At the gate** | **6**, all in `mcp-core` |
| After the fix | **0**; the exclude file is still empty |

The six were real, in two classes. `McpJsonDefaults` synchronised its two static accessors
on the class object, a public lock any caller can hold against it
(`USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION`, rank 7, twice), and re-read a static field after
an opaque constructor call that SpotBugs cannot see assigning it (`NP_NULL_ON_SOME_PATH`,
rank 6, twice): now a private lock and a local. `McpServiceLoader` wrote `supplier` and
`supplierResult` without the lock that `getDefault` reads them under
(`IS2_INCONSISTENT_SYNC`, rank 17, twice): now synchronized setters. The two `IS2` findings
are exactly the shape the concurrency list exists for; the rank gate alone would have let
them through.

**Findings for the kit:**
1. **The `IS2_INCONSISTENT_SYNC` plant is not reliable.** Planted alone in a utility class it
   does not fire; beside other synchronized code it does. `falsify spotbugs` now plants a
   null dereference of a local (`NP_ALWAYS_NULL`, rank 5, dataflow, not an Error Prone check).
2. **A module with only `module-info.java` fails the analysis** ("No files to analyze could be
   opened"): `spotbugs.skip` in that module's pom; `measure-spotbugs` says so when the run fails.
3. **The include filter as a property** lets `measure` swap in an include-everything filter
   and report the long tail without touching the gate.
4. **The kit's exclude file carried acp-java's two entries**; it is now an empty template.

Falsified on the finished branch: the planted null dereference turned `spotbugs:check` red and
the report named `NP_ALWAYS_NULL` on the planted method; restored. `./mvnw -P errorprone
verify` on JDK 21 runs all three gates (PMD and CPD, Error Prone, SpotBugs).

## JaCoCo floors, 2026-10-08, same day

Each gated module's own classes exercised by its own tests, LINE and BRANCH floors at
measured minus 2 points, checked at `verify`. The first run attached no agent: the SDK's
surefire reads `${surefireArgLine}`, a property the pom defines empty, which Maven
interpolates before JaCoCo sets it. Surefire's late-bound `@{surefireArgLine}` is the
one-token fix; `measure-jacoco` detects both forms and the plugin slice takes the property
name.

| Module | Line | Branch | Floor (line / branch) |
|---|---:|---:|---|
| mcp-core | 30.0% (2,045 of 6,815) | 28.6% | 28 / 26 |
| mcp-json-jackson2 | 68.4% | 76.9% | 66 / 74 |
| mcp-json-jackson3 | 59.1% | 76.9% | 57 / 74 |

**The core's 30% is a finding, not a gate failure.** The SDK's real test weight is the abstract
suites published in `mcp-test` (829 tests, Testcontainers), which the owner left out as test
support and which this machine cannot run. Measured by the kit's rule, the core's own tests
cover under a third of it. The floor records today's truth and stops it falling; raising it
means tests in the module that owns the code, which is the next thing an adopter would do.

Falsified: 800 never-executed statements planted in the first core class took the line ratio
from 0.30 to 0.26 and `jacoco:check` failed on the floor of 0.28, naming it; restored. With
the floors in, `./mvnw -P errorprone verify` on the gated modules runs all four gates, PMD and
CPD, Error Prone, SpotBugs and JaCoCo, and passes.

## ArchUnit no-cycles, 2026-10-08, same day

`measure-archunit` imports each gated module's compiled classes and evaluates the kit's two
rules as a library, so it needs nothing installed.

| Module | Classes | Packages | Package cycles | Class cycles |
|---|---:|---:|---:|---:|
| mcp-core | 400 | 10 | 9 | 4 |
| mcp-json-jackson2 | 5 | 2 | 0 | 0 |
| mcp-json-jackson3 | 5 | 2 | 0 | 0 |

The core's cycles have three roots: `util` (`Assert`, `McpServiceLoader`,
`McpUriTemplateManagerFactory`) depends back on `spec` and `json`, which depend on `util`;
`spec` and `server` depend on each other (handler `TypeRef`s on schema types one way, server
types referenced from the spec the other); and inside `client`, `McpAsyncClient` and its
lifecycle and notification helpers reference each other. Breaking them moves types across
packages, which is public API; the STOP block recommended scoping, and the decision recorded
as the script's recommendation gates the two cycle-free modules now and leaves the core for a
session of its own.

**Findings for the kit, three, all in the install shape:**
1. **ArchUnit's JUnit engine pins a JUnit Platform version.** The SDK is on JUnit 6;
   `archunit-junit5` brought the older platform, which won by declaration order, and no test
   could start. The template now uses ArchUnit as a plain library from an ordinary JUnit
   test; only `com.tngtech.archunit:archunit` is needed.
2. **An anchor class's package tree misses sibling packages.** `importPackagesOf(anchor)`
   imports the anchor's package and below; the Jackson modules keep a second package beside
   it, so a planted cycle between the two was invisible and the test passed vacuously. The
   template imports the module's own `target/classes` (or Gradle's `build/classes/java/main`).
3. **The class-slice rule in the old template did not compile**: `SliceAssignment` is not a
   functional interface. The first run of the measurement caught it.

Falsified on the installed modules: two planted classes in the module's two packages
referencing each other made `NoCyclesTest` fail naming the cycle; both deleted. The five
gates, PMD and CPD, Error Prone, SpotBugs, JaCoCo and ArchUnit, run together in
`./mvnw -P errorprone verify` on the gated modules.

## Not yet done

`mcp-core`'s 13 dependency cycles are measured, listed and scoped out; breaking them is a
session with API changes. The layering rule (playbook 20, step 6) is not written. The trial
branch `guardrails-pmd` in the local clone is not pushed and nothing is proposed upstream.
