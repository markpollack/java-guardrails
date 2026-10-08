# java-guardrails Agent Instructions

A kit of deterministic quality gates (PMD, SpotBugs, Error Prone, NullAway, ArchUnit, JaCoCo,
Lincheck, PIT) with calibrated settings and playbooks for installing them, one gate at a time, on an
existing Java code base, so that an AI coding agent has a build target instead of a conversation.

## Steward

Private planning and control state are authoritative in
`/home/mark/projects/java-guardrails-steward`; read its `BINDING.md` before planning or executing
work. Nothing from the steward is copied here.

## Build and test

There is no Maven build in this repository. The scripts are Java and run through the checked-in
JBang wrapper, which compiles them on first use and needs only a JDK:

```
./jbang alias list                 # every command the kit offers
./jbang <alias> --help             # Windows: jbang.cmd or jbang.ps1
```

The gate before a commit: every alias in `jbang-catalog.json` runs through `./jbang`, and a script
change is proven against a real target project, with its planted-violation case shown red, before
it is committed. CI only repeats a run already known to pass locally.

## The repository is the skill

`SKILL.md` at the root makes this directory one Agent Skill (agentskills.io); `.claude-plugin/`
holds the Claude Code plugin and marketplace manifests pointing at the root. Installers copy or
link the whole directory, so it stays clean: no build output, nothing private. Before a commit
that touches `SKILL.md`, the manifests, or the layout: `claude plugin validate .` passes (the
`CLAUDE.md` warning is known), `npx skills-ref validate .` prints `Valid skill`, and
`npx skills add <this dir> -a claude-code -y` in a scratch project yields a copy whose `jbang
measure` runs.

## Modules

- `playbooks/` one procedure per gate; every step is a script, an AI task, or a stop for the owner.
- `configs/` build-tool neutral files copied into a target's `config/`: PMD ruleset, SpotBugs filters, Error Prone lists, ArchUnit template.
- `maven/` the Maven-specific slice: plugin blocks, the errorprone profile, `jvm.config`, properties.
- `scripts/` the deterministic parts, Java, shared helpers through `//SOURCES`.
- `evidence/` what each gate found, one file per code base.
- `<target>/config/guardrails/decisions.md` (in the target, not here): the owner's answers to the scripts' STOP blocks, read on every run.

## Architecture

The kit is tool-agnostic: any agent or person applies it by reading the playbooks. A Claude Code
skill, when present, is a thin adapter over them and carries no procedure of its own. Configs are
copied into the target repository, never referenced from a jar, because the commented thresholds
are meant to be read and owned there. `configs/` holds nothing specific to Maven; only `maven/`
does, so a Gradle slice can be added beside it.

## Hard rules

- **Measure, then gate.** A threshold is set from the target's own distribution, at the knee. A default threshold is noise, and the first agent that meets it will exclude its way past it.
- **Fix, never baseline.** A false positive is excluded narrowly with a written reason. A baseline file hides every violation forever; the ratchet (`maxAllowedViolations`, coverage floors) is the only accepted alternative.
- **Falsify before commit.** A gate is committed only after a planted violation turned the build red. A gate that never failed may be checking nothing, as a misconfigured second SpotBugs execution once did.
- **Java only.** No Python or shell in `scripts/`; Windows users and Java developers both lose otherwise. A dependency goes in a `//DEPS` line, never a checked-in jar beyond the wrapper's own.
- **No SNAPSHOT in the catalog** and no fat or Boot-repackaged jar under a `//DEPS` coordinate; the shim would compile against nothing. `--help` and `--version` need no credentials.
- Commit messages contain no AI attribution. No planning, roadmap, status or owner-decision content in public files.

## Docs

`README.md` is the front door. `evidence/acp-java.md` is the record behind the tiers and
thresholds. JBang: https://www.jbang.dev/documentation/guide/latest/alias_catalogs.html
