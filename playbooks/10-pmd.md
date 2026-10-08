# PMD: size, complexity and duplication

> Status: proven end to end on the MCP Java SDK (2026-10-08, `evidence/mcp-java-sdk.md`: measured, decided, installed, green, falsified) after acp-java.
> Each step is a **script** (deterministic, no model), an **AI** task (judgment), or a **stop**
> (the owner decides). Maven only; the Gradle slice is not written.

The gate: `maven-pmd-plugin` running `pmd:check` and `pmd:cpd-check` at `verify` on main sources,
with `config/pmd/ruleset.xml` from this kit and `cpd.minimumTokens` 100. The thresholds in the
ruleset are constants of the kit, calibrated on the first code base and confirmed on each one
since; what changes per code base is the cost of going green, the mechanism chosen to get there,
and any narrow, written exclusion.

## Steps

1. **Script** — `./jbang measure <target> [--exclude mod,mod]`. Runs PMD's metrics engine
   in-process over every `src/main/java` below the target (no build needed, Maven or Gradle),
   and prints for each gated metric: the population and percentiles, the worst cases by name
   and line, and the cost at the reference threshold with a fit check. CPD runs per source root
   at 50 tokens so the 100-token cut can be seen. A reference *fits* when at most 5% of the
   population fails; the counts it prints are exactly what `pmd:check` will report (verified
   rule by rule on the MCP Java SDK).
2. **Stop** — `measure` ends with one `STOP <ID>` block per decision it is missing, each with
   the data, the options, a recommendation computed from the data, and the line to record.
   Relay each block to the owner as written; write the answer into
   `<target>/config/guardrails/decisions.md`; run `measure` again. The decisions this gate asks
   for:
   - `TEST_SUPPORT_MODULES`: modules that keep test code in `src/main/java` (named like a test
     module, test libraries on the compile classpath, or most classes named like tests): leave
     them out of the gate, or gate them as published code.
   - `PMD_MECHANISM`: **fix-all** when the cost is under 100 items, **ratchet**
     (`maxAllowedViolations` at today's count per module, lowered as fixes land; `measure`
     prints the values once the decision is recorded) above it, or **scope** to named modules.
   - `CLASS_SIZE`: a type that fails class NCSS for a reason other than tangled code, a wire
     schema or an interface carrying builders: exempt it by a written structural rule in the
     ruleset, or refactor.
   - `THRESHOLD_FIT_<METRIC>`: a reference that does not fit (more than 5% fail). Either the
     code base is unusual, or the reference is wrong for this kind of code base; the evidence
     file records which. The threshold is never moved to spare the worst code.
   The record exemption for canonical constructors is a kit constant since the SDK and needs no
   decision.
3. **AI** — install the gate in report mode: copy `configs/pmd/ruleset.xml` to
   `<target>/config/pmd/ruleset.xml`; add the three properties from `maven/properties.xml`
   (`maven-pmd-plugin.version`, `pmd.version`, `cpd.minimumTokens`) and the plugin block from
   `maven/pmd.plugin.xml` to the root pom. Run `./mvnw pmd:pmd pmd:cpd` and confirm the per-module
   counts in `target/pmd.xml` and `target/cpd.xml` match step 1. Nothing fails yet: `pmd:check`
   is bound to `verify`, which this step does not run. Modules the owner left out get
   `<pmd.skip>true</pmd.skip>` and `<cpd.skip>true</cpd.skip>` in their own pom; a `CLASS_SIZE`
   exemption is an XPath on the `NcssCount` rule naming the shape, with the reason beside it.
4. **Script** — `./jbang falsify <target> pmd` and `./jbang falsify <target> cpd`. Each plants a
   violation in the first class of the first module, runs the check goal for that module, and
   restores the file byte for byte. It passes only when the build went red *and* the module's
   report names the planted member, because a brownfield module may be red already. On a target
   without the gate it fails, which is how the script itself is tested.
5. **AI** — go green under the mechanism from step 2, one hotspot per commit with the full test
   suite green after each. Fix in the code; never a baseline file. An exclusion is an XPath or
   a named pattern in the ruleset with the reason beside it, never a suppression comment in the
   code.
6. **AI** — flip to fail: `./mvnw verify` is green with `failOnViolation` true (it already is in
   the slice). Commit with the measurement in the body: the counts from step 1, the mechanism,
   and the exclusions.
7. **AI** — append the code base to `evidence/`: what the gate found, which thresholds were
   confirmed, what did not fit, and the first-run triage.

## Known shapes

- **Reactive code hides in lambdas.** NCSS and cognitive complexity do not see a lambda body as
  a method; the `LongLambda` rule in the ruleset does. On the SDK four of the seven long lambdas
  were inside methods already failing cognitive complexity.
- **Records with many components** failed `ExcessiveParameterList` through their canonical
  constructor on the SDK (six feature records); the ruleset now exempts canonical record
  constructors with the reason beside it. Secondary record constructors stay gated: the SDK's
  schema keeps four.
- **The wire-record-container exemption** written for acp-java (`final` class, no instance
  fields, nested types only records, interfaces and enums) did not match the SDK's `McpSchema`,
  which also carries a logger field and a nested constants class. Generalising it is an owner
  decision recorded in the evidence file.
- **Test support in `src/main/java`** (abstract test classes published for downstream use)
  dominates CPD: 50 of the SDK's 83 duplicate blocks were in `mcp-test`.
