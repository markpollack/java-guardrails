# java-guardrails

Deterministic quality gates for Java code bases that already exist, installed one at a time by
an AI coding agent that then refactors until the build is green.

The tools are old friends: PMD and its copy-paste detector, SpotBugs, Error Prone, ArchUnit,
JaCoCo, NullAway, Lincheck, PIT. Out of the box their signal-to-noise ratio is poor. The
settings here were calibrated on a real code base until the build failed on what matters and
nothing else, then confirmed unchanged on a second one. The point is to give an agent a target
instead of a conversation: a gate is a command whose exit code is the goal. The tools measure
and gate; the agent refactors and triages; the owner decides; no intelligence is spent where a
threshold will do.

## Status

Each gate answers one question about the code; the order to install them comes from the code
base's own facts, which `assess` reads (below), not from the table.

| Question | Gate | Cost, cadence | Status |
|---|---|---|---|
| Is the code executed by its own tests? | JaCoCo floors | seconds after the tests; every commit | **Proven end to end on two code bases**, measured with floors recorded on a third; measurement checks report completeness and the surefire argLine trap; complete nonzero coverage proposes floors by notification. |
| Is executed code wrong in a known shape? | SpotBugs rank 9 plus concurrency | minutes; every change | **Proven end to end on three code bases**: six real findings on the second and four on the third, all concurrency or locking. |
| Is executed code wrong in a known shape? | Error Prone defaults at ERROR | a compile under JDK 21; every change | **Proven end to end on two code bases**: measured, decided, installed, green, falsified. Three real bugs on the second. |
| What must be refactored before the others can see it? | PMD size, complexity and duplication | minutes, then the refactoring; a target, then a gate | **Proven end to end on two code bases**: measured, decided, installed, green, falsified; measured on a third, where the references did not fit (`evidence/agent-journal.md`). |
| Is the structure drifting? | ArchUnit no-cycles | seconds after compile; every change | **Proven on two code bases**; on the second, gating the cycle-free modules while the core's 13 measured cycles wait for their own session. Layers not yet. |
| Can null cross a non-null contract? | NullAway, explicit package scope | a compile under JDK 21; every change | Measurement and falsification implemented; see `evidence/nullaway.md` for the scope proven. Optional, outside the default gate order. |
| | Lincheck | | Outline. |
| Does the executing test check anything? (advanced) | PIT, scoped | tens of minutes whole-module, about two scoped; changed classes during work, whole module at acceptance | Playbook written from one code base's record; a profile, never a gate, never in the order. |

Maven only. A Gradle slice is planned; `measure` already works on Gradle projects, the gate
install does not.

For nullness checking, ask to adopt NullAway on a selected package (`playbooks/30-nullaway.md`).
It runs through Error Prone's compiler integration; you can select NullAway before cleaning up
other Error Prone checks. `measure-nullaway --inventory` lists package defaults without a build.
The report and planted-violation proof require the profile, JSpecify, and JDK 21+.
The proof compares a green valid probe with a red nullable-return probe using the same flags.
It establishes enforcement on a new unannotated class; existing declaration opt-outs,
suppressions and exclusions need a separate audit. Selective Error Prone checking still leaves
javac warnings subject to `failOnWarning`; clear those warnings or explicitly choose an
ERROR-level NullAway gate without gating javac warnings. Measurement requires normal Maven
logging; quiet output is refused rather than counted as zero.

## Getting started

You need a JDK 17 or newer (21 or newer for the Error Prone gate), a Maven project with the
Maven wrapper, and a coding agent that reads Agent Skills (Claude Code, or any of the agents the
`skills` CLI installs into). Nothing else: not JBang, not Node, not Python.

1. **Install the kit as a skill.** In Claude Code:

   ```
   /plugin marketplace add markpollack/java-guardrails
   /plugin install java-guardrails@java-guardrails
   ```

   If the first command answers that the marketplace is already on disk, it was added before;
   go on to the second. For other agents, with Node.js 22.20 or newer, install for all projects:
   `npx skills add markpollack/java-guardrails -g`. By hand:
   `git clone https://github.com/markpollack/java-guardrails .claude/skills/java-guardrails`.

2. **Open your project in the agent and ask for quality gates:**

   > install quality gates on this project

   The agent runs `assess`, which works out for each gate whether the build already enforces
   it, whether it will find something, and what going green costs (it measures PMD and
   CPD itself, in seconds on the code bases tried), prints the gate order with a reason per
   move, records it, and names the first gate. Then it follows that
   gate's playbook: measurement, a `STOP` block or two (a notification it records and goes on
   from, or a decision it waits for), the gate in report mode, the refactoring until
   `./mvnw verify` is green. Say "the next gate" for each one after.

3. **Try the measurement by hand first, if you prefer.** Clone the kit anywhere and point it at
   your project; it changes nothing:

   ```
   git clone https://github.com/markpollack/java-guardrails
   cd java-guardrails
   ./jbang assess  /path/to/your/project
   ./jbang measure /path/to/your/project
   ```

What the agent does, step by step, is below; what lands in your repository is a ruleset you
own, a decisions file, a few lines in your pom, and the refactoring commits.

## What happens when you use it

You install the kit as a skill in your coding agent, open a Maven project, and ask for quality
gates. The agent then follows `playbooks/00-assess.md` and the playbook of the gate it names:

1. **Assess.** A script inventories the project: modules, test-support modules, Java level,
   tooling already in the build. For each gate it then says whether the build already enforces
   it (absent, reporting, or gating), whether it will find something (tests per module,
   concurrency primitives, the measured PMD and CPD cost), and what going green costs, and it
   prints the gate order with the reason for each move. No build is run. The order is
   recorded as `GATE_ORDER` and every later run names the next gate from it.
2. **Measure.** A script computes every metric the gate will enforce for every method, class
   and lambda, and prints the distribution, the worst cases by name, and the cost at the
   kit's threshold: how many fail, and whether the threshold fits this code base.
3. **You decide, when there is a decision.** The script ends with one `STOP` block per
   answer it needs, with the data, the options, a recommendation and the line to record. A
   block marked `notify` is the kit's own default from unambiguous data (coverage floors at
   measured minus 2, the gate order): the agent records it, shows it to you, and goes on. A
   block marked `decide` is relayed as written, and the agent waits:

   ```
   STOP PMD_MECHANISM (decide)
     Kind: decide. Relay this block as written and do not proceed until the owner answers.
     Cost of going green in the measured modules: 57 PMD violations and 33 CPD blocks.
       mcp-core: 57 PMD
     Options: (a) fix-all: every violation fixed in the code, one hotspot per commit
              (b) ratchet: pmd.maxAllowedViolations at today's count per module, lowered as fixes land; CPD stays at zero
              (c) scope: gate the listed modules first, the rest later
     Recommendation: (a), the count is below 100
     Record as:  PMD_MECHANISM: fix-all
   ```

   Your answers go into `config/guardrails/decisions.md` in your project. A re-run never
   re-asks; changing a decision is an edit to that file.
4. **Install in report mode.** The agent copies `config/pmd/ruleset.xml` into your project
   (every threshold has its reason beside it; you own the file) and adds the plugin block to
   your root pom.
5. **Falsify.** A script plants a violation, runs the gate, and passes only if the build went
   red and the report names the plant. A gate that never failed may be checking nothing.
6. **Go green.** The agent fixes violations in the code, one hotspot per commit with your tests
   green after each. Never a baseline file, never a suppression comment. A false positive is
   excluded by a written structural rule in the ruleset, and only after you said so.
7. **Flip to fail.** `./mvnw verify` now fails on any violation, and that is the target every
   later change is held to.

What lands in your repository: `config/pmd/ruleset.xml`, `config/guardrails/decisions.md`,
three properties and one plugin block in the root pom, and the refactoring commits.

On the MCP Java SDK this took the core module from 57 violations and 33 duplicate blocks to
zero in 27 commits, with the worst method going from cognitive complexity 59 to 4.

## Install

Prerequisites: a JDK 17 or newer (21 or newer for the Error Prone gate and its measure) and a Maven project with the Maven wrapper (`mvnw`). The kit's
scripts are Java, run through a bundled JBang wrapper that resolves its own dependencies on
first run. Nothing else is needed, not JBang, not Node, not Python.

The repository is an [Agent Skill](https://agentskills.io): `SKILL.md` at the root with the
scripts, configs, playbooks and evidence beside it. Any of these puts the same directory where
your agent reads it:

```
# Claude Code, nothing else installed
/plugin marketplace add markpollack/java-guardrails
/plugin install java-guardrails@java-guardrails

# any agent that reads the Agent Skills format, across all projects (Node.js 22.20+)
npx skills add markpollack/java-guardrails -g

# optional: this project only; run from its root
npx skills add markpollack/java-guardrails

# by hand, into the agent's skills folder
git clone https://github.com/markpollack/java-guardrails .claude/skills/java-guardrails
```

Then, in the agent, in the project to gate:

> install quality gates on this project

## Versions and upgrading

A release is a git tag and a GitHub release with notes; there is no jar. Every install channel
tracks the `main` branch, so the tag is for pinning and for humans.

| Channel | Pin | Upgrade |
|---|---|---|
| JBang catalog | `guardrails@markpollack/java-guardrails/v0.1.0` | next run after the cache expires, or `jbang --fresh ...` |
| Claude Code marketplace | the `version` in `plugin.json` pins until it changes | `claude plugin update java-guardrails@java-guardrails` |
| `npx skills add ... -g` (all projects) | not by default | `npx skills add markpollack/java-guardrails -g` again |
| `npx skills add ...` (one project) | not by default | `npx skills add markpollack/java-guardrails` again, from that project root |
| `git clone` | `git checkout v0.1.0` | `git pull` |

`-g` records a user-wide installation; without it, `add` records project scope in the current
directory's `skills-lock.json`, even when that directory is your home directory. To upgrade,
rerun `add` in the same scope and select the same agents; this refreshes their installed files,
including copied installations. `skills update` has a separate scope selection: choosing Global
does not update a project installation, even if its installed files look user-wide.

Upgrading the kit never touches your project. The ruleset and plugin block copied into your
repository are yours. When a new version changes a threshold or adds an exemption shape, run
`measure` again and compare the kit's `configs/pmd/ruleset.xml` with your `config/pmd/ruleset.xml`;
taking the change is your decision.

## Running the scripts yourself

```
./jbang alias list                                 # the toolset, from inside the kit
./jbang assess  <target>                           # inventory, the kit's gates on this target, next step
./jbang measure <target> [--top 20] [--cpd-tokens 50] [--exclude module,module]
./jbang measure-errorprone <target>                # Error Prone findings by check, classified; needs the profile and JDK 21+
./jbang measure-spotbugs <target>                  # SpotBugs: what the gate fails on, and the long tail it leaves out
./jbang measure-jacoco <target>                    # coverage per module by its own tests, and the floors to declare
./jbang measure-archunit <target>                  # package and class dependency cycles per module, with the dependencies that close them
./jbang falsify <target> pmd                       # expect red, and the plant named in the report
./jbang falsify <target> cpd
./jbang falsify <target> errorprone
./jbang falsify <target> spotbugs
./jbang falsify <target> jacoco
./jbang falsify <target> archunit
./jbang measure-nullaway <target> --inventory       # package defaults, no build
./jbang measure-nullaway <target> --module module-path
./jbang falsify <target> nullaway --module module-path --package com.example
```

The aliases resolve from `jbang-catalog.json` in the current directory, so from anywhere else
name the script: `<kit>/jbang <kit>/scripts/PmdMeasure.java <target>`. On Windows use
`jbang.cmd` or `jbang.ps1`.

## The ideas behind it

- **The code base chooses the order.** A gate is installed when the code base's own facts say
  it is the next question worth answering: coverage first because it protects what exists,
  SpotBugs ahead of Error Prone when main sources carry locks and latches, PMD early when its
  measured cost is small, and a gate the build already enforces is skipped. The tier table
  says how proven a gate is, not when to install it.
- **A stop whose answer is the computed default is a notification, not a decision.** Floors at
  measured minus 2 with nothing in the data against them are recorded and shown, not asked.
- **Thresholds are constants, not knees.** Calibrated once, confirmed on each code base by a
  fit check (at most 5% of the population fails, and what fails is the worst code). A gap
  search for a "natural" threshold was tried and removed: on heavy-tailed metrics it always
  proposed sparing the worst code. A threshold that does not fit is a stop, recorded as
  evidence either way; it is never moved to spare the worst code.
- **Three ways to go green on existing code.** Fix all now, for small counts. Ratchet:
  `pmd.maxAllowedViolations` or coverage floors at today's level, lowered as fixes land.
  The PMD allowance applies only to its named execution; CPD stays at zero (see playbook 10). Scope by
  module: gate the core first.
- **Exemptions are shapes, never names.** "A final class with no instance fields whose nested
  types are records, enums, interfaces and constants holders" exempts a protocol schema and
  nothing else. Each is an XPath in your ruleset with the reason beside it.
- **The measurement equals the gate.** `measure`'s counts are checked against the real plugin's
  report rule by rule. A measurement not checked against the gate is an opinion.
- **Evidence says which fact predicted the finding.** Each code base's evidence file records,
  for every real finding a gate made, the `assess` fact that pointed at it, or that none did;
  that is how a rule earns its place in the order. The analysis is itself falsified: tiny
  fixture projects with a known answer (`fixtures/assess/`) run in CI.

## Layout

```
SKILL.md         the skill: what an agent reads first
playbooks/       one procedure per gate; every step is a script, an AI task, or a stop for the owner
configs/         build-tool neutral files copied into <target>/config/: PMD ruleset, SpotBugs filters, Error Prone lists, ArchUnit template
maven/           the Maven-specific slice: plugin blocks, the errorprone profile, jvm.config, properties
scripts/         the deterministic parts as Java, run through the JBang wrapper
evidence/        what each gate found on each code base; the source of the thresholds
.claude-plugin/  Claude Code plugin and marketplace manifests pointing at this directory
```

## Roadmap

Proven, on two code bases (the ACP Java SDK, where the settings were calibrated, and the MCP
Java SDK, where they were confirmed unchanged): the five gates in the status table, each with
a measure script, a playbook, a plant that turns the build red, and evidence. A third,
agent-journal, applied the kit through its own README without the author in the loop
(JaCoCo and SpotBugs installed; PMD measured, and the first code base where the references did
not fit), and its feedback became the gate order from three questions and the notify/decide stops.

Next, in order:

- **A third code base, an application**: Spring Petclinic, in a fresh session that has only
  this skill, to show the gates and the playbooks work without their author in the loop.
- **One `guardrails` command** with the verbs as subcommands, `--help` and `--version`, so
  the JBang catalog form (`jbang guardrails@markpollack/java-guardrails`) is the human CLI.
- **Gradle**: the measures already work on Gradle projects; the gate install does not.
- **ArchUnit, the rest**: breaking cycles that run through public types, and a layering rule
  the owner confirms.
- **Tier 3**: Lincheck from the first code base's record; PIT's scoped playbook
  is written, a script to run it is not.
- **Windows** proof of the scripts, and a self-test fixture so CI runs the measures and the
  plants against known counts.

Issues and discussion: <https://github.com/markpollack/java-guardrails/issues>.

## Licence

Business Source License 1.1; see `LICENSE`.

### Maven reactor scope

Run the kit against the reactor root. Maven inventory follows that POM's `<modules>` recursively,
so a standalone project nested under `tools/` is not selected unless an aggregator declares it.
Profile-defined or interpolated module lists use Maven's effective model through the target's
wrapper and settings. Inventory expects the conventional `src/main/java` layout inside the target.

Scoped builds use `-pl ... -am` and a clean lifecycle, compiling sibling dependencies from the
working tree without a prerequisite `install` into the shared local repository. Excluded modules
can still be built as dependencies; their tests and the requested gate can run as upstream
prerequisites. An upstream failure makes measurement or proof fail and retains Maven's diagnostics.
External dependencies and parents still resolve through the target's Maven settings.

All Maven-backed scripts share a checker policy: Error Prone and NullAway enable the `errorprone`
profile; other gates explicitly disable it. Unrelated kit gates are skipped during that command.
The requested gate's own skip setting is preserved, so disabling it cannot produce a successful
falsification. This policy controls the kit's profiles and plugin properties; other target build
extensions still run normally, and their failures are shown rather than counted as proof.

Use `EXCLUDED_MODULES: path, other/path` in `config/guardrails/decisions.md` for general exclusions.
Old `TEST_SUPPORT_MODULES: exclude ...` entries keep excluding their paths. Only an actual test-support
answer suppresses the test-support stop; an old exclusion of a standalone tool no longer does.
`assess` asks this before the first gate, and direct JaCoCo measurement checks before building.
JaCoCo accounts for every selected module: executable classes without a report stop measurement
before floors are proposed or validated, while modules without executable bytecode are named and
need no floor. Gated test support needs its own tests/report; consumer-only execution is not
aggregated. See [the JaCoCo playbook](playbooks/21-jacoco.md) for missing reports, opt-in integration
tests and previously recorded partial floors.
Updating the kit does not rewrite a target's decisions or copied Maven configuration.
