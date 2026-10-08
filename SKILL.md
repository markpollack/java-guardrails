---
name: java-guardrails
description: Install deterministic quality gates (PMD complexity and duplication, SpotBugs, Error Prone, ArchUnit, JaCoCo coverage floors, NullAway, Lincheck, PIT) on an existing Java code base, one gate at a time, with thresholds calibrated so the build fails on what matters and nothing else; then refactor until the build is green. Use when asked to add quality gates, code quality checks, complexity limits, static analysis, coverage floors or architecture rules to a Java or Maven project, to make a brownfield Java code base enforce a standard, or to hold an AI coding agent to a build-enforced target.
license: Business Source License 1.1; see LICENSE
compatibility: Requires a JDK 17 or newer and a Maven project with the Maven wrapper. The bundled JBang wrapper resolves everything else on first run. Gradle is not yet supported.
metadata:
  author: markpollack
  version: "0.1.0"
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

The gates, in the order to install them, each with its playbook:

| Tier | Gate | Playbook | Status |
|---|---|---|---|
| 1 | PMD size, complexity and duplication | `playbooks/10-pmd.md` | proven on two code bases |
| 1 | Error Prone defaults at ERROR | `playbooks/11-errorprone.md` | outline |
| 1 | SpotBugs rank 9 plus concurrency | `playbooks/12-spotbugs.md` | outline |
| 2 | ArchUnit no-cycles, then layers | `playbooks/20-archunit.md` | outline |
| 2 | JaCoCo coverage floors | `playbooks/21-jacoco.md` | outline |
| 3 | NullAway, Lincheck, PIT | `playbooks/3x-*.md` | outline |

Follow a playbook step by step. Every step is marked **script**, **AI** or **stop**. Do not
write a gate's playbook from memory; an outline means the gate is not yet proven and you say so.

## Stops: how the owner decides

Some steps need a decision only the owner can make: which modules are test code, how to go green,
which exemptions to write. The scripts detect when such a decision is missing and end their report
with one `STOP <ID>` block per decision, carrying the data, the options, a recommendation computed
from the data, and the exact line to record.

- Relay each block to the owner **as written**. Do not paraphrase, merge or summarise blocks, and
  do not answer them yourself. In Claude Code, one question per block with the block's options is
  the natural form.
- Write the owner's answer as the `Record as:` line into `config/guardrails/decisions.md` in the
  target, creating the file with a `# java-guardrails decisions` heading if absent, and append
  the date in parentheses.
- Run the script again. It reads the file, applies the decision, and does not re-ask. A changed
  decision is an edit to that file.

Do not proceed past a block until it is answered.

## Rules that are not negotiable

- **Fix, never baseline.** A violation is fixed in the code. A false positive is excluded narrowly,
  in the ruleset, with a written reason; never a suppression comment in the code, never a baseline
  file. The ratchet (`maxAllowedViolations`, coverage floors) is the only accepted alternative and
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
<kit>/jbang <kit>/scripts/PmdMeasure.java   <target> [--top N] [--cpd-tokens N] [--exclude module,module]
<kit>/jbang <kit>/scripts/Falsify.java      <target> pmd | cpd
<kit>/jbang <kit>/scripts/Assess.java       <target>      # not yet implemented
<kit>/jbang <kit>/scripts/JacocoFloors.java <target>      # not yet implemented
```

`measure` runs PMD's metrics engine in-process over every `src/main/java` below the target and
needs no build. `falsify` plants in the first class of the first module, runs that module's check
goal through the target's `mvnw`, restores the file byte for byte, and passes only if the build
went red and the module's report names the plant.

## Where things are

- `configs/` the files copied into `<target>/config/`: the PMD ruleset with every threshold's
  reason beside it, SpotBugs filters, Error Prone lists, the ArchUnit template.
- `maven/` the plugin blocks and properties to paste into the target's root pom.
- `evidence/` what each gate found on each code base; the source of the tiers and thresholds.
  Read `evidence/acp-java.md` and `evidence/mcp-java-sdk.md` before claiming a threshold fits.
- `README.md` the front door for people.
