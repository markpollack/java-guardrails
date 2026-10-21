# Assess the target, and choose the gate order from its signals

> Status: `assess` implemented 2026-10-08; proven on the MCP Java SDK. Gate order from signals added
> 2026-10-21; on agent-journal the signals reproduce the order its owner had chosen by hand
> (`evidence/agent-journal.md`).

1. **Script** — `./jbang assess <target>` (from elsewhere: `<kit>/jbang <kit>/scripts/Assess.java <target>`).
   Reads files and the git history; runs no build. Prints the build tool and whether its wrapper
   is present, the Java level, every module with main sources and its test-support signals, the
   root packages, the quality tooling already in the build, which of the kit's gates are on this
   target with the question each answers and what it costs, the decisions recorded so far, and
   then the **signals**: test sources, a PIT profile without coverage floors, main-source files
   that use concurrency primitives, the Java level against Error Prone's JDK 21 profile, module
   and file counts against PMD's fix-all limit, which gates are already in the build, and the
   main-source files touched most in the last 50 commits.
2. **Notify** — `GATE_ORDER`: the order the signals recommend, one reason per gate. Coverage
   first when there are tests (a fact in seconds; its floors are a notification); SpotBugs
   before Error Prone when main sources carry concurrency, after it otherwise; PMD after the
   bug finders, because it is a refactoring target before it is a gate; ArchUnit last; PIT
   scoped only, never in the order. The block is `notify`: record the line, show it to the
   owner, continue. Reordering is an edit to the line; every later run names the next gate from
   it and says what the signals would have recommended.
3. **AI** — read it. A missing Maven wrapper is added before anything else. A PMD plugin already in
   the build is measured before its configuration is replaced. Gradle targets can be measured but
   not gated yet.
4. The next step it names is the first step of the next gate's playbook: the first gate in the
   recorded order that is not in the build. The owner's decisions are asked there, by that gate's
   script, not here. When a PIT profile is present, `assess` also prints the scoped PIT command
   for the main classes changed in the working tree (`playbooks/32-pit.md`).

## The questions, the costs, the cadences

| Question | Gate | Cost | Cadence |
|---|---|---|---|
| Is the code executed by its own tests? | JaCoCo floors | seconds after the tests | every commit |
| Is executed code wrong in a known shape? | SpotBugs; Error Prone | minutes; a compile under JDK 21 | every change |
| Does the executing test check anything? | PIT | tens of minutes whole-module; about two scoped | changed classes during work; whole module at acceptance |
| What must be refactored before the others can see it? | PMD complexity and duplication | minutes, then the refactoring | a refactoring target, then a gate |
| Is the structure drifting? | ArchUnit no-cycles | seconds after compile | every change |

A judgment a threshold cannot make (is this surviving mutant equivalent; is this flat per-record
dispatch switch tangled or merely long) is not the gate's to make and not the agent's to guess:
it goes to a judge with a written rubric, and the evidence file records the call.
