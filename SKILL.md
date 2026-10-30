---
name: java-guardrails
description: Install deterministic quality gates (PMD complexity and duplication, SpotBugs, Error Prone, ArchUnit, JaCoCo coverage floors, NullAway, Lincheck, PIT) on an existing Java code base, one gate at a time, with thresholds calibrated so the build fails on what matters and nothing else; then refactor until the build is green. Use when asked to add quality gates, code quality checks, complexity limits, static analysis, coverage floors or architecture rules to a Java or Maven project, to make a brownfield Java code base enforce a standard, or to hold an AI coding agent to a build-enforced target.
license: Business Source License 1.1; see LICENSE
compatibility: Requires a JDK 21 or newer for a full pass and a Maven project with the Maven wrapper. The bundled JBang wrapper resolves everything else on first run. Gradle is not yet supported.
metadata:
  author: markpollack
  version: "0.4.0"
---

# java-guardrails

Prepare a local, reviewable branch with enforced quality gates, preserved public APIs and no
findings introduced by the pass. Finish with one plain-language report explaining what the
maintainer can review, keep or undo. Nothing is pushed.

**0.4.0 defaults to unattended mode.** Read `playbooks/01-pass.md` before starting. Resolve
`GUARDRAILS_MODE: unattended|control` from `config/guardrails/decisions.md`; missing means
unattended. There is no extra mode flag. An explicit owner answer always beats a default.
Control mode retains policy questions, while API freeze, introduced-finding checks, positive
coverage floors and measurement-failure halts apply in both modes.

Tell the maintainer what they will receive: a local branch, verified checks and a review report.
Do not require them to learn STOP syntax to use unattended mode. Progress names completed work
and meaningful failures. At the end, lead with the outcome and the next action.

The kit is this directory. Its scripts are Java and run through the wrapper beside this file
(`jbang`, or `jbang.cmd` and `jbang.ps1` on Windows). From the target project, call the wrapper
and the script both by the absolute path of this directory:

```
<kit>/jbang <kit>/scripts/PmdMeasure.java <target>
```

The short aliases (`./jbang measure`) resolve only when the current directory is the kit, because
JBang reads `jbang-catalog.json` from where it is run. They need only a JDK.

## Start, then work one hotspot at a time

1. Run `Pass.java start <target> <kit>` from a clean committed target. Work only in the isolated
   worktree it prints. Preserve the original branch and other writers' work.
2. Resolve scope and record any exact `HANDS_OFF` selectors with reasons. Run
   `Pass.java baseline <worktree> <kit>` before refactoring. It records the original commit,
   API artifacts and full findings. Run records live in the ignored `.guardrails-run/` directory.
3. Run assessment and follow the recommended gate order. Use each gate's playbook for report
   mode, fixes, enforcement and planted proof. In unattended mode apply the policy table in
   `playbooks/01-pass.md`; in control mode relay unanswered decisions and wait.
4. After every hotspot, run `Pass.java check <worktree> <kit>`. It fully remeasures all five
   default gates plus configured/selected optional checks, compares API and findings, runs
   verification and falsifies installed gates. Repair or undo introduced findings before any
   decision, exclusion or allowance. Do not commit a failed candidate.
5. Run `Pass.java accept <worktree> <kit> "<hotspot message>"` on the exact checked tree.
   At completion, generate and review the final report as described in the pass playbook.

Do not substitute standalone measurement output for pass validation. `GUARDRAILS_MODE: control`
changes policy interaction only. A measurement crash or incomplete required report halts both
modes with diagnostics; no workaround or omitted module certifies completion.

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
| Lincheck | configured concurrency model | model dependent | `playbooks/31-lincheck.md` | installation outline; configured Java models measured by the pass |
| PIT, scoped (advanced) | Does the executing test check anything? | tens of minutes whole-module, about two scoped; changed classes during work, whole module at acceptance | `playbooks/32-pit.md` | optional profile outside the default order; configured mutation contract measured by the pass |

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

## Resolve policy before proceeding

Scripts retain structured STOP blocks for standalone and control use. The pass policy in
`playbooks/01-pass.md` governs unattended use, including notification defaults. Never auto-disable
a frequent compiler check: mechanically safe fixes include style checks.

- **Unattended:** apply an applicable documented default, record its reason as a kit default,
  and continue. Preserve explicit decisions and unrelated keys. If the promised scope cannot
  be enforced within API/hands-off constraints, report the blocked scope; do not weaken it.
- **Control:** relay unanswered `decide` blocks verbatim and wait. Record mechanical `notify`
  defaults and continue, subject to the same invariants. Do not accept a global compiler
  suppression as a remedy for an introduced finding.
- **Halt:** a failed or incomplete measurement produces `STOP KIT_DEFECT (halt)`. Preserve
  accepted work and the original diagnostic. It is a failed measurement, not by itself proof
  that the kit caused it. Diagnostic experiments cannot advance the pass.

Record policy in `config/guardrails/decisions.md`, with reasons and dates. This and the final
report belong on the branch; measurements, logs and run state do not. Same-version resume
retains the original start. A kit version change requires a new run from that original commit,
reusing decisions; never treat the changed HEAD as inherited debt.

The decisions file is the kit's memory in the target. Keys the kit reads: `GUARDRAILS_MODE`, `HANDS_OFF`, `GATE_ORDER`,
`TEST_SUPPORT_MODULES`, `EXCLUDED_MODULES`, `PMD_MECHANISM`, `CLASS_SIZE`, `THRESHOLD_FIT_<METRIC>`,
`ERRORPRONE_DISABLED`, `SPOTBUGS_MECHANISM`, `JACOCO_FLOORS`, `ARCHUNIT_CYCLES`,
`PUBLICATION_PROFILES`, and repeatable `DEPENDENCY_CHANGE` records (see the pass playbook). Any other key
is kept and ignored; `PROJECT_*` is never claimed by the kit, so the target's own notes go there.

`EXCLUDED_MODULES: module-a, tools/module-b` excludes general module paths (and their
children) from measurement and plant selection; `none` clears this list. It does not answer
`TEST_SUPPORT_MODULES`. Existing `TEST_SUPPORT_MODULES: exclude ...` entries still exclude
those paths; an entry that excludes only non-test modules no longer suppresses the test-support
stop. Move general exclusions to `EXCLUDED_MODULES` when convenient, then answer the emitted
stop for test-support modules. `TEST_SUPPORT_MODULES: gate` remains a complete answer.

Assessment resolves `TEST_SUPPORT_MODULES` before the first gate; direct JaCoCo measurement checks it
before building too. Do not defer this question until PMD. Gated test support needs its own
coverage report; consumer-only execution is not aggregated. JaCoCo names modules without
executable code and stops on missing reports for executable modules before proposing or
validating floors. Follow `playbooks/21-jacoco.md` for that decision, including already recorded
partial floors; a missing report is not measured zero coverage.

## Rules that are not negotiable

- **Fix, never baseline.** A violation is fixed in the code. A false positive is excluded narrowly,
  in the ruleset, with a written reason. Never use a baseline file. Inherited API-blocked compiler
  findings may use a named-check exception on the smallest declaration, with its reason recorded;
  scratch measurement must still see it. Never use `all`, a file-wide all-check exclusion, or an
  exception for an introduced finding. PMD's decreasing ratchet is the supported inherited-debt
  alternative; CPD stays zero. Coverage floors must be positive and based on own-module tests.
- **Falsify before commit.** A gate is committed only after `falsify` showed the planted violation
  reported by name. A gate that never failed may be checking nothing.
- **Public/protected API is frozen**, including supertypes, method/field descriptors, generics,
  visibility and compatible additions. Preserve the original artifacts across the whole pass.
- **Consumer dependency versions are protected.** Resolve every publication from its actual
  publication POM in an independent consumer at the start and every accepted hotspot, including
  finish. Repair changes or record the exact dependency, before/after versions and reason in
  decisions.md; surface every recorded change in the report. A green reactor or japicmp result
  does not establish consumer dependency compatibility.
- **No introduced findings.** Match rule plus bound declaration with multiplicity against both
  the original and previous accepted snapshots. A moved inherited finding is introduced; fix it
  or undo the move. Hands-off code is still measured.
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
