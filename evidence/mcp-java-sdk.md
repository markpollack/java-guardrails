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

## Not yet done

Error Prone, SpotBugs, ArchUnit and JaCoCo have not been measured here. The trial branch
`guardrails-pmd` in the local clone is not pushed and nothing is proposed upstream.
