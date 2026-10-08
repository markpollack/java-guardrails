# java-guardrails

Deterministic quality gates for brownfield Java code bases, with calibrated settings and a method for
installing them one at a time. The tools are old friends (PMD, SpotBugs, Error Prone, NullAway,
ArchUnit, JaCoCo, Lincheck, PIT). Out of the box their signal-to-noise ratio is poor; the settings
here were iterated on a real code base until the build failed on what matters and nothing else.

The point is to give an AI coding agent a concrete target instead of a conversation: a gate is a
command whose exit code is the goal. The tools measure and gate; the agent refactors and triages.
No intelligence is spent where a threshold will do.

## The loop, for every gate

1. Add the plugin in report mode.
2. Measure the distribution; set each threshold at the knee, where the worst code fails and ordinary code passes.
3. Make the build green: fix, never baseline. A false positive is excluded narrowly with a written reason.
4. Falsify: plant a violation, see the build fail, revert.
5. Flip to fail and commit with the measurement in the body.

## Three ways to go green on existing code

- **Report then gate.** Fix every violation now. Right for small counts.
- **Ratchet.** `maxAllowedViolations` (PMD, SpotBugs) or coverage floors (JaCoCo) at today's level; fail on any increase; lower as fixes land.
- **Scope by module or package.** Gate one module, clean it, move on. NullAway's `OnlyNullMarked` is the same idea for packages.

## Tiers

| Tier | Gates | Why |
|---|---|---|
| 1, copies as-is | PMD complexity and CPD, Error Prone defaults, SpotBugs rank 9 plus concurrency | The config files travel unchanged; measured thresholds are the only per-repo input |
| 2, one prompt each | ArchUnit no-cycles then layers, JaCoCo floors | Needs one fact from the code base (root package, measured coverage) and an owner decision on layers |
| 3, optional | NullAway, Lincheck, PIT | Pays on libraries with concurrency or a stable core; expensive to apply |

`evidence/acp-java.md` records what each gate found on the first code base.

## Layout

```
playbooks/   one procedure per gate; each step is a script, an AI task, or a stop for the owner
configs/     build-tool neutral files copied into <target>/config/: PMD ruleset, SpotBugs filters, Error Prone lists, ArchUnit template
maven/       the Maven-specific slice: plugin blocks, the errorprone profile, jvm.config, properties
scripts/     the deterministic parts as Java, run through the JBang wrapper
evidence/    what each gate found, per code base
```

## Installing the kit as a skill

The repository is an [Agent Skill](https://agentskills.io): `SKILL.md` at the root, the scripts,
configs, playbooks and evidence beside it. Any of these puts the same directory where an agent
reads it:

```
# Claude Code, nothing else installed
/plugin marketplace add markpollack/java-guardrails
/plugin install java-guardrails@java-guardrails

# any agent that reads the Agent Skills format, with Node present
npx skills add markpollack/java-guardrails

# by hand
git clone https://github.com/markpollack/java-guardrails .claude/skills/java-guardrails
```

Then, in the agent, in the project to gate: "install quality gates on this project". The
scripts' decisions for the owner are recorded in `<target>/config/guardrails/decisions.md`,
one `KEY: value` line each; a re-run never re-asks.

## Running the scripts

The scripts are Java and run through the checked-in JBang wrapper, the same pattern as `mvnw`.
A JDK is the only prerequisite; dependencies are resolved and cached on first run.

```
./jbang alias list          # the toolset
./jbang assess  <target>    # inventory the target project (not yet implemented)
./jbang measure <target>    # every PMD metric's distribution, worst cases, and cost at the reference threshold
./jbang measure <target> --exclude mcp-test,conformance-tests --top 20 --cpd-tokens 50
./jbang floors  <target>    # JaCoCo floors from the last verify (not yet implemented)
./jbang falsify <target> pmd    # plant a violation, expect the module's pmd:check red and the plant in its report
./jbang falsify <target> cpd
```

On Windows use `jbang.cmd` or `jbang.ps1`. The aliases resolve from `jbang-catalog.json` in the
current directory, so from anywhere else name the script:
`<kit>/jbang <kit>/scripts/PmdMeasure.java <target>`.

## Licence

Business Source License 1.1; see `LICENSE`.
