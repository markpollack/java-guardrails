---
name: java-guardrails
description: Install deterministic quality gates (PMD complexity and duplication, SpotBugs, Error Prone, ArchUnit, JaCoCo coverage floors, NullAway, Lincheck, PIT) on an existing Java code base, one gate at a time, with thresholds calibrated so the build fails on what matters and nothing else; then refactor until the build is green. Use when asked to add quality gates, code quality checks, complexity limits, static analysis, coverage floors or architecture rules to a Java or Maven project, to make a brownfield Java code base enforce a standard, or to hold an AI coding agent to a build-enforced target.
license: Business Source License 1.1; see LICENSE
compatibility: Requires a JDK 17 or newer and a Maven project with the Maven wrapper. The bundled JBang wrapper resolves everything else on first run. Gradle is not yet supported.
metadata:
  author: markpollack
  version: "0.3.1"
---

# java-guardrails

A gate is a command whose exit code is the goal. The tools measure and gate; you refactor and
triage; the owner decides. No judgment is spent where a threshold will do.

The kit is this directory. Its scripts are Java and run through the wrapper beside this file
(`jbang`, or `jbang.cmd` and `jbang.ps1` on Windows). From the target project, call the wrapper
and the script both by the absolute path of this directory:

```
<kit>/jbang <kit>/scripts/PmdMeasure.java <target>
```

The short aliases (`./jbang measure`) resolve only when the current directory is the kit, because
JBang reads `jbang-catalog.json` from where it is run. They need only a JDK.

## The loop, for every gate

1. Add the plugin in report mode.
2. Measure. The thresholds are constants of the kit, calibrated on real code bases; the script
   reports the cost at each threshold and whether it fits.
3. Go green under the mechanism the owner chose: fix, never baseline.
4. Falsify: plant a violation, see the build go red, restore.
5. Flip to fail and commit with the measurement in the body.

The gates, each with the question it answers and its playbook. **The order to install them
comes from `assess`**, which answers three questions per gate from the code base itself:
is the question already answered here (absent, reporting or gating), will the gate find
something (tests per module, concurrency, the measured PMD cost), and what does going green
cost. It prints the order with the reason for each move and records `GATE_ORDER`
(`playbooks/00-assess.md` has the rules). Follow the order; do not reorder it yourself, the
owner edits the line. The tier says how proven a gate is, not when to install it.

| Gate | Question it answers | Cost, cadence | Playbook | Status |
|---|---|---|---|---|
| JaCoCo coverage floors | Is the code executed by its own tests? | seconds; every commit | `playbooks/21-jacoco.md` | proven on two code bases; measured and floors recorded on a third |
| SpotBugs rank 9 plus concurrency | Is executed code wrong in a known shape? | minutes; every change | `playbooks/12-spotbugs.md` | proven on three code bases |
| Error Prone defaults at ERROR | Is executed code wrong in a known shape? | a compile under JDK 21; every change | `playbooks/11-errorprone.md` | proven on two code bases |
| PMD size, complexity and duplication | What must be refactored before the others can see it? | minutes, then the refactoring; a target, then a gate | `playbooks/10-pmd.md` | proven on two code bases; measured on a third |
| ArchUnit no-cycles, then layers | Is the structure drifting? | seconds; every change | `playbooks/20-archunit.md` | no-cycles proven on two code bases; layers not yet |
| NullAway (optional, explicit package scope) | Can null cross a non-null contract? | a compile under JDK 21; every change | `playbooks/30-nullaway.md` | measurement and falsification implemented; see `evidence/nullaway.md` for proven scope |
| Lincheck | tier 3 | | `playbooks/31-lincheck.md` | outline |
| PIT, scoped (advanced) | Does the executing test check anything? | tens of minutes whole-module, about two scoped; changed classes during work, whole module at acceptance | `playbooks/32-pit.md` | written from one code base's record; a profile, not a gate, never in the order |

Follow a playbook step by step. Every step is marked **script**, **AI**, **notify** or **stop**.
Do not write a gate's playbook from memory; an outline means the gate is not yet proven and you
say so.

For NullAway, follow playbook 30: inventory package defaults, choose adoption scope, measure,
fix, and falsify each claimed package. It needs Error Prone's compiler integration; other Error
Prone findings need not be cleaned up first. NullAway is outside the default `GATE_ORDER` in this
increment. A configured processor with no marked scope, or a zero report count, is not proof.
Falsification requires a green valid-probe control followed by a red nullable-return probe with
the same gate flags. It proves the package default on a new unannotated class only; audit existing
declarations and configuration separately for opt-outs, suppressions and exclusions. Selective
Error Prone checking does not disable javac warnings; `failOnWarning` can stop analysis first.

## Stops: notify or decide

Some steps need the owner: which modules are test code, how to go green, which exemptions to
write. The scripts detect when such an answer is missing and end their report with one
`STOP <ID> (<kind>)` block each, carrying the data, the options, a recommendation computed from
the data, and the exact line to record. The kind is the second line of every block.

- **`notify`**: the recommendation is the kit's mechanical default and the data is unambiguous
  (`GATE_ORDER`; `JACOCO_FLOORS` at measured minus 2; `ERRORPRONE_DISABLED` when every check is
  style-tagged). Write the `Record as:` line into the decisions file, show the block to the owner
  in your reply, and continue. Do not ask.
- **`decide`**: the options lead to materially different outcomes (`TEST_SUPPORT_MODULES`,
  `PMD_MECHANISM`, `CLASS_SIZE`, `THRESHOLD_FIT_*`, `SPOTBUGS_MECHANISM`, `ARCHUNIT_CYCLES`).
  Relay the block to the owner **as written**, as a fenced text block in your reply. Do not
  paraphrase, merge or summarise blocks, and do not answer them yourself. A question widget with
  the block's options is optional; the text is the contract. Do not proceed past a `decide` block
  until it is answered.
- Either way the answer is the `Record as:` line in `config/guardrails/decisions.md` in the
  target, created with a `# java-guardrails decisions` heading if absent, the date in
  parentheses. Run the script again: it reads the file, applies the answer, and does not re-ask.
  A changed answer is an edit to that file.

The decisions file is the kit's memory in the target. Keys the kit reads: `GATE_ORDER`,
`TEST_SUPPORT_MODULES`, `EXCLUDED_MODULES`, `PMD_MECHANISM`, `CLASS_SIZE`, `THRESHOLD_FIT_<METRIC>`,
`ERRORPRONE_DISABLED`, `SPOTBUGS_MECHANISM`, `JACOCO_FLOORS`, `ARCHUNIT_CYCLES`. Any other key
is kept and ignored; `PROJECT_*` is never claimed by the kit, so the target's own notes go there.

`EXCLUDED_MODULES: module-a, tools/module-b` excludes general module paths (and their
children) from measurement and plant selection; `none` clears this list. It does not answer
`TEST_SUPPORT_MODULES`. Existing `TEST_SUPPORT_MODULES: exclude ...` entries still exclude
those paths; an entry that excludes only non-test modules no longer suppresses the test-support
stop. Move general exclusions to `EXCLUDED_MODULES` when convenient, then answer the emitted
stop for test-support modules. `TEST_SUPPORT_MODULES: gate` remains a complete answer.

## Rules that are not negotiable

- **Fix, never baseline.** A violation is fixed in the code. A false positive is excluded narrowly,
  in the ruleset, with a written reason; never a suppression comment in the code, never a baseline
  file. The ratchet (`pmd.maxAllowedViolations` scoped to PMD, coverage floors) is the only accepted alternative and
  only when the owner chose it.
- **Falsify before commit.** A gate is committed only after `falsify` showed the planted violation
  reported by name. A gate that never failed may be checking nothing.
- **One hotspot per commit**, the full test suite green after each, the measurement in the commit
  body. No AI attribution in commits.
- **Never move a threshold to spare the worst code.** A threshold that does not fit is a stop,
  recorded as evidence either way.
- Nothing is pushed or proposed upstream from a trial target without the owner.

## Scripts

```
<kit>/jbang <kit>/scripts/Assess.java       <target> [--quick]   # gate analysis, order, the next gate; run this first and after every gate
<kit>/jbang <kit>/scripts/PmdMeasure.java   <target> [--top N] [--cpd-tokens N] [--exclude module,module]
<kit>/jbang <kit>/scripts/ErrorProneMeasure.java <target> [--top N]   # needs the errorprone profile installed and JDK 21+
<kit>/jbang <kit>/scripts/SpotBugsMeasure.java   <target> [--top N]     # needs the plugin slice installed
<kit>/jbang <kit>/scripts/JacocoMeasure.java     <target>               # needs nothing installed; runs the tests
<kit>/jbang <kit>/scripts/ArchUnitMeasure.java   <target> [--top N]     # needs nothing installed; compiles
<kit>/jbang <kit>/scripts/NullAwayMeasure.java   <target> [--module path] [--inventory] # inventories without a build; otherwise needs the profile and JDK 21
<kit>/jbang <kit>/scripts/Falsify.java      <target> pmd | cpd | errorprone | spotbugs | jacoco | archunit | nullaway
<kit>/jbang <kit>/scripts/Falsify.java      <target> nullaway [--module path] [--package com.example] # existing JSpecify-marked package
```

`assess` reads files and the git log, runs PMD and CPD in-process under a time limit
(`--quick` skips it) and no build, and says what is there, each gate's state, the gate order,
the next gate from the recorded order, and the scoped PIT command when a profile exists. `measure` runs PMD's metrics engine in-process over every `src/main/java` below the target and
needs no build. `falsify` plants in the first class of the first module, runs that module's check
goal through the target's `mvnw`, restores the file byte for byte (on Ctrl-C or SIGTERM too, and
never over an edit or replacement made meanwhile), and passes only if the build went red and the module's report
names the plant.

## Where things are

- `configs/` the files copied into `<target>/config/`: the PMD ruleset with every threshold's
  reason beside it, SpotBugs filters, Error Prone lists, the ArchUnit template.
- `maven/` the plugin blocks and properties to paste into the target's root pom.
- `evidence/` what each gate found on each code base, and which `assess` fact predicted it;
  the source of the thresholds. Read `evidence/acp-java.md`, `evidence/mcp-java-sdk.md` and
  `evidence/agent-journal.md` before claiming a threshold fits. When you append to it, say which
  fact predicted each real finding, or that none did.
- `README.md` the front door for people.
