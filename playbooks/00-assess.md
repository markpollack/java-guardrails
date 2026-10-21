# Assess the target, and choose the gate order

> Status: `assess` implemented 2026-10-08; proven on the MCP Java SDK. Gate analysis and order from
> three questions added 2026-10-21; proven on fixtures (`fixtures/assess/`, run in CI) and on
> agent-journal at two points in its history (`evidence/agent-journal.md`).

1. **Script** - `./jbang assess <target> [--quick] [--pmd-timeout SECONDS]` (from elsewhere:
   `<kit>/jbang <kit>/scripts/Assess.java <target>`). Reads files and the git history, runs PMD
   and CPD in-process under a time limit (default 180 s; about 2 s on the code bases tried;
   `--quick` skips it), runs no build. Prints the build tool, Java level, modules with tests per
   10 main files, the tooling in the build, the decisions recorded, a **gate analysis**, the
   **order**, and the next gate.
2. **Notify** - `GATE_ORDER`: the recommended order as one line to record. The block is
   `notify`; reordering is an edit to the line, and later runs name the next gate from it and
   say what the analysis would have recommended.
3. **AI** - read it. A missing Maven wrapper is added before anything else. A PMD plugin
   already in the build is measured before its configuration is replaced. Gradle targets can be
   measured but not gated yet.
4. The next step is the first gate in the order that is not gating. For a gate that is only
   reporting, the step is to turn the report into a gate.

## The three questions

Each gate is answered from cheap facts. Value is the three together; there is no score.

**1. Is the question already answered here?** Not "is the plugin in the pom":

| State | Meaning | How `assess` reads it |
|---|---|---|
| absent | the tool is not in the build | no plugin or library found |
| reporting | in the build, but it cannot fail it | no `check` goal; `failOnViolation` or `failOnError` false; Error Prone without `-Werror` or `failOnWarning`; ArchUnit on the classpath with no test stating a rule |
| gating | a violation fails the build | `check` goal bound (JaCoCo, SpotBugs, PMD or CPD); `-Werror` or `failOnWarning`; a test that states an ArchUnit rule |

The kit adds value when a gate is absent or reporting. A gating gate stays in the recorded
order and is skipped as the next gate. A state that cannot be read from the poms in the target
(a parent outside the checkout) reads as absent; say so to the owner.

**2. Will it find something?**

| Gate | Fact it is read from | Evidence |
|---|---|---|
| JaCoCo | test sources per module; a module with main code and none; no tests at all | agent-journal: floors on every module |
| SpotBugs | main-source files using concurrency primitives, per module | MCP SDK 6 and agent-journal 4 at the gate, all locks or races |
| Error Prone | not predictable from source; a steady rate on code never compiled with it | about 3 real bugs per 130 files on acp-java and the MCP SDK |
| PMD and CPD | **measured**, not predicted: violations and duplicate blocks at the reference thresholds, and which references fit | agent-journal: 154 with test support out, four references near the 5% line |
| ArchUnit | needs compiled classes; stays a band | MCP SDK: 13 cycles in the core, none in two modules |

**3. Can it go green at a cost the owner will pay?** Floors cost no refactoring. SpotBugs is a
handful by hand. PMD is measured: fix-all up to 100 items when every reference fits, a ratchet
above that or when a reference does not fit. ArchUnit cycles through public API are a session
each.

## The order

A fixed base sequence moved by modifiers, each printed with its reason.

- **Base:** coverage, Error Prone, SpotBugs, PMD, ArchUnit. Coverage first because a floor
  protects what exists and needs no decision; the bug finders next because they find real bugs
  cheaply; PMD is a refactoring target before it is a gate; ArchUnit last.
- **No tests outside test support:** coverage goes last. There is nothing to measure.
- **Concurrency in main sources:** SpotBugs goes ahead of Error Prone.
- **PMD measured at 25 items or fewer with every reference fitting:** PMD goes ahead of the bug
  finders, behind coverage. 25 is a kit constant (`PMD_CHEAP`) to calibrate against evidence.
- **Already gating:** skipped as the next gate.

Test-support modules (named like tests, test libraries on the compile classpath, mostly
test-named classes) are left out of the measurement and of the test counts unless the decisions
file says otherwise. PIT is advanced and not in the order. The table shows it, and the scoped
command is printed when a profile exists (`playbooks/32-pit.md`).

## The questions, the costs, the cadences

| Question | Gate | Cost | Cadence |
|---|---|---|---|
| Is the code executed by its own tests? | JaCoCo floors | seconds after the tests | every commit |
| Is executed code wrong in a known shape? | SpotBugs; Error Prone | minutes; a compile under JDK 21 | every change |
| What must be refactored before the others can see it? | PMD complexity and duplication | minutes, then the refactoring | a refactoring target, then a gate |
| Is the structure drifting? | ArchUnit no-cycles | seconds after compile | every change |
| Does the executing test check anything? (advanced) | PIT | tens of minutes whole-module; about two scoped | changed classes during work; whole module at acceptance |

A judgment a threshold cannot make (is this surviving mutant equivalent; is this flat per-record
dispatch switch tangled or merely long) is not the gate's to make and not the agent's to guess:
it goes to a judge with a written rubric, and the evidence file records the call.

## Proving the analysis

`fixtures/assess/` holds tiny projects with known answers; CI runs `assess` on each and
requires the order and states in `expected-quick.txt`. A rule that changes the order changes a
fixture's expectation in the same commit.
