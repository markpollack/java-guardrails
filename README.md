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

| Gate | Tier | Status |
|---|---|---|
| PMD size, complexity and duplication | 1 | **Proven end to end on two code bases**: measured, decided, installed, green, falsified. `evidence/mcp-java-sdk.md` has the numbers. |
| Error Prone | 1 | **Proven end to end on two code bases**: measured, decided, installed, green, falsified. Three real bugs on the second. |
| SpotBugs | 1 | **Proven end to end on two code bases**: six real findings on the second, all concurrency or locking. |
| ArchUnit, JaCoCo floors | 2 | Template and mechanism from the first code base; playbooks are outlines. |
| NullAway, Lincheck, PIT | 3 | Outlines. |

Maven only. A Gradle slice is planned; `measure` already works on Gradle projects, the gate
install does not.

## What happens when you use it

You install the kit as a skill in your coding agent, open a Maven project, and ask for quality
gates. The agent then follows `playbooks/10-pmd.md`:

1. **Assess.** A script inventories the project: modules, test-support modules, Java level,
   tooling already in the build. Nothing is run.
2. **Measure.** A script computes every metric the gate will enforce for every method, class
   and lambda, and prints the distribution, the worst cases by name, and the cost at the
   kit's threshold: how many fail, and whether the threshold fits this code base.
3. **You decide.** The script ends with one `STOP` block per decision only you can make, with
   the data, the options and a recommendation. The agent relays it as written and waits:

   ```
   STOP PMD_MECHANISM
     Cost of going green in the measured modules: 57 PMD violations and 33 CPD blocks.
       mcp-core: 57 PMD
     Options: (a) fix-all: every violation fixed in the code, one hotspot per commit
              (b) ratchet: maxAllowedViolations at today's count per module, lowered as fixes land
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

Prerequisites: a JDK 17 or newer and a Maven project with the Maven wrapper (`mvnw`). The kit's
scripts are Java, run through a bundled JBang wrapper that resolves its own dependencies on
first run. Nothing else is needed, not JBang, not Node, not Python.

The repository is an [Agent Skill](https://agentskills.io): `SKILL.md` at the root with the
scripts, configs, playbooks and evidence beside it. Any of these puts the same directory where
your agent reads it:

```
# Claude Code, nothing else installed
/plugin marketplace add markpollack/java-guardrails
/plugin install java-guardrails@java-guardrails

# any agent that reads the Agent Skills format, with Node present
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
| `npx skills add` | not by default | `npx skills update java-guardrails` |
| `git clone` | `git checkout v0.1.0` | `git pull` |

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
./jbang falsify <target> pmd                       # expect red, and the plant named in the report
./jbang falsify <target> cpd
./jbang falsify <target> errorprone
./jbang falsify <target> spotbugs
```

The aliases resolve from `jbang-catalog.json` in the current directory, so from anywhere else
name the script: `<kit>/jbang <kit>/scripts/PmdMeasure.java <target>`. On Windows use
`jbang.cmd` or `jbang.ps1`.

## The ideas behind it

- **Thresholds are constants, not knees.** Calibrated once, confirmed on each code base by a
  fit check (at most 5% of the population fails, and what fails is the worst code). A gap
  search for a "natural" threshold was tried and removed: on heavy-tailed metrics it always
  proposed sparing the worst code. A threshold that does not fit is a stop, recorded as
  evidence either way; it is never moved to spare the worst code.
- **Three ways to go green on existing code.** Fix all now, for small counts. Ratchet:
  `maxAllowedViolations` or coverage floors at today's level, lowered as fixes land. Scope by
  module: gate the core first.
- **Exemptions are shapes, never names.** "A final class with no instance fields whose nested
  types are records, enums, interfaces and constants holders" exempts a protocol schema and
  nothing else. Each is an XPath in your ruleset with the reason beside it.
- **The measurement equals the gate.** `measure`'s counts are checked against the real plugin's
  report rule by rule. A measurement not checked against the gate is an opinion.

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

## Licence

Business Source License 1.1; see `LICENSE`.
