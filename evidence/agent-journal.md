# Evidence: agent-journal (markpollack/agent-journal), October 2026

The third code base, and the first applied by someone other than the kit's author, through the
README's own install path: an application of eight Maven modules, Java 17, 196 main-source files,
installed as the Claude Code plugin on 2026-10-21 and followed in an order chosen for the code
base rather than the kit's default. Numbers below are from that project's own run as reported
to the kit (its `config/guardrails/decisions.md` and measure logs), re-measured here with the
kit's scripts where a build was not needed.

## The order, and the signals that predicted it

The project recorded `GATE_ORDER: jacoco, spotbugs, errorprone, pmd, archunit; pit scoped-only`
by hand, with reasons: a PIT profile had just shown 34 never-executed mutants in one class (a
coverage fact), and the new code was a reader thread with a lock and a latch (SpotBugs'
territory). `assess` with the signals added the same day recommends exactly that order from
what it reads without a build: 154 test-source files, a PIT profile in two modules, 13
main-source files using concurrency primitives (journal-core 11), Java 17, 196 files in 8
modules. The first-gate signal was the PIT-without-floors signal; the second-gate signal was
concurrency.

## JaCoCo, measured per module by its own tests

84.9 to 96.4 percent line, 66.2 to 79.8 percent branch; floors recorded at measured minus 2 for
all eight modules, as proposed. The owner, on seeing the `JACOCO_FLOORS` stop: "The plan was so
obviously good. You don't need to ask me, just do it." That is the origin of the `notify` kind.

## SpotBugs at the gate

4 findings, all `USO_UNSAFE_METHOD_SYNCHRONIZATION` in one older class; fixed, `falsify spotbugs`
red then restored, re-measured at zero, committed with the measurement in the body. Long tail
169, of which 139 `EI_EXPOSE_REP*` on records, as on the MCP SDK. Signal that predicted it: the
concurrency signal pointed at the new launcher; the findings were in an older class in the same
territory. A hit for the gate, a partial hit for the signal.

## PMD and CPD, measured by the kit, not installed

402 violations and 6 CPD blocks at the reference thresholds (`journal-capture-tck` 248, now
detected as test support by name; `journal-ocsf-writer` 63, `claude-code-capture` 42). **All four
method and class references fail the 5% fit** for the first time:

| Metric | Reference | Fail at reference | Fit |
|---|---|---|---|
| Cognitive complexity, method | 15 | 125 of 1,577 (7.9%) | no |
| Cyclomatic complexity, method | 10 | 145 of 1,577 (9.2%) | no |
| Cyclomatic complexity, class total | 80 | 16 of 301 (5.3%) | no |
| NCSS, method | 30 | 94 of 1,577 (6.0%) | no |

Worst: `SessionLogParser.doParse` cognitive 225; the profile and mapper `map` methods 100 to 142.
This is the shape the references were not calibrated on: per-record mapping switches, flat
dispatch over many record kinds, where cognitive complexity counts every case as a branch. The
`THRESHOLD_FIT_*` stops are the right outcome, and the question they put to the owner, "is this
tangled code or a flat dispatch that reads fine", is a judgment a threshold cannot make. The
record stands as evidence against neither the code base nor the reference until that call is
made; the kit's mechanism for a code base of this shape is a ratchet, which the `PMD_MECHANISM`
recommendation (b) says.

## PIT, from the project's own record

Two profiles, 70 percent thresholds, DEFAULTS mutators, 4 threads; whole-module runs of about
25 minutes each. One module went from 69 to 73 percent with behaviour tests on exit codes,
option parsing and refusals; 29 mutants timed out breaking a supervision loop (counted as
killed); the stated survivors are timing-boundary and double-destroy equivalents. The owner's
conclusion, now `playbooks/32-pit.md`: scoped runs on changed classes during work, whole module
at acceptance, threshold only in the profile.

## Defects in the kit found by this run

- `assess` printed "playbook an outline" for four proven gates (stale table; fixed).
- Playbook 12 described the `IS2_INCONSISTENT_SYNC` plant the script had stopped using (fixed;
  the playbook now says what the plant proves and what it does not).
- The `PMD_MECHANISM` record line led with `fix-all` whatever the recommendation (now the
  recommended form first).
- `journal-capture-tck` was not detected as test support: `tck` added to the name pattern.
- A project added its own key (`GATE_ORDER`, before the kit knew it) to the decisions file;
  the kit now reads that key, keeps unknown keys, and reserves `PROJECT_*` for the target.
