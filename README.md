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

## What a pass gives you

Ask your agent to add quality gates. In 0.4.0 it works through a first pass without routine
policy questions, on a local branch. It preserves published APIs, fixes findings it introduces,
verifies the checks with deliberate violations, and leaves a report at
`config/guardrails/report.md` explaining what changed, what remains and how to review or undo it.
A failed measurement stops the pass with the original diagnostic; missing evidence is never zero.
Nothing is merged or pushed for you.

To keep policy checkpoints, ask for control mode before starting, or record
`GUARDRAILS_MODE: control` in `config/guardrails/decisions.md`. Existing answers remain effective.
Control still adopts API protection, checks for introduced findings, positive coverage floors
and failure halts. Missing `GUARDRAILS_MODE` means `unattended`; no command-line flag is needed.
You need JDK 21+ for the full pass. Standalone source and coverage tools still support JDK 17.

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
| Is the configured concurrency model safe? | Lincheck | model dependent | Installation outline; existing configured Java models participate in pass validation. |
| Does the executing test check anything? (advanced) | PIT, scoped | tens of minutes whole-module, about two scoped; changed classes during work, whole module at acceptance | Optional profile outside the default order; configured mutation contracts participate in pass validation. |

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

You need JDK 21 or newer, a Maven project with the Maven wrapper, and a coding agent that reads
Agent Skills (Claude Code, or an agent supported by the `skills` CLI). The kit bundles its JBang
launcher. The `npx` install option below also needs Node.js; cloning or the plugin install does not.

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

   The agent creates a local worktree, establishes the starting API and findings, then runs
   assessment and follows the gate order. After baseline it gives you a hotspot/time estimate
   and progress updates, validates changes in batches, and retains one undo commit per hotspot. The final report tells you what to review and what
   remains. To reserve code for later work, name the classes or methods to leave untouched;
   they remain measured. See [the pass procedure](playbooks/01-pass.md) for scope and resume.

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

The agent follows [the pass procedure](playbooks/01-pass.md), then each gate's playbook:

1. **Establish the start.** Create a local branch and record the original API and findings.
   Resolve test-support and publication scope before calculating costs or floors.
2. **Choose the order.** Assessment uses existing tooling, tests, concurrency and measured
   complexity to schedule the gates. All required checks still participate in every validation.
3. **Apply recorded choices or defaults.** Unattended mode records routine policy choices with
   their reasons and continues. Control mode presents unanswered choices and waits. Both modes
   stop on incomplete measurements; neither can excuse a finding introduced by the pass.
4. **Fix and enforce.** Write missing own-module tests, fix mechanically safe findings and install
   checks with measured thresholds. Preserve published API and any reserved declarations.
   Narrow exceptions for inherited API-blocked compiler findings remain visible in measurement.
5. **Prove each change.** Between gate boundaries, measure changed modules and their reactor
   dependents, with full-measurement equivalence proofs. Measure everything at each gate boundary
   and finish, and prove enforcement with deliberate violations. The target is under an hour
   end to end for an agent-client-sized project; the report records actual time and any miss.
   Resolve published modules as an external consumer would, too: dependency version changes
   require a recorded reason, even when the project's own build remains green.
6. **Review the result.** The final report states what is enforced, what changed, what remains,
   and which commits to review or undo. You decide what to merge. Nothing is pushed for you.

Your branch carries the gate configuration, fixes, `config/guardrails/decisions.md` and
`config/guardrails/report.md`. Detailed measurements stay in an ignored local directory.
See the pass procedure for control-mode STOP blocks, exact hands-off selectors and resume rules.

On the MCP Java SDK this took the core module from 57 violations and 33 duplicate blocks to
zero in 27 commits, with the worst method going from cognitive complexity 59 to 4.

## Install

Prerequisites: JDK 21+ for a full pass and a Maven project with the Maven wrapper (`mvnw`).
Standalone source and coverage tools support JDK 17. The bundled JBang wrapper resolves Java
dependencies on first run; no separate JBang installation is needed.

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
