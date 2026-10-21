# PIT mutation testing: a scoped profile, not a gate

> Status: written 2026-10-21 from one code base's record (agent-journal, eight modules, two
> profiles, 25 minutes per whole-module run; `evidence/agent-journal.md`) after acp-java's
> (score 61 to 90 percent, one leak found). No kit script runs PIT yet; `assess` prints the
> scoped command. Each step is a **script**, an **AI** task, or a **stop** for the owner.

The question PIT answers is whether the executing tests check anything; coverage says only
that the code ran. The cost is the whole-module run, tens of minutes, and every mutant that
breaks a loop or a wait costs a timeout. So PIT is a profile over named classes, run on the
classes a change touched during work and over the whole module only at acceptance; it is never
bound to `verify`, and its threshold lives only in the profile.

## Steps

1. **AI** — coverage floors first (`playbooks/21-jacoco.md`). A mutation run on never-executed
   code reports "no coverage" mutants that a line-coverage report shows in seconds; on
   agent-journal 34 such mutants in one class were the fact that put JaCoCo ahead of PIT.
2. **AI** — add a `pit` profile to each module that owns logic worth mutating: `pitest-maven`
   with `pitest-junit5-plugin`, `targetClasses` naming the classes (and their nested classes,
   `Foo$*`), the DEFAULTS mutators, `threads` 4, test classes that launch processes or sleep
   excluded, `mutationThreshold` and `coverageThreshold` at 70, `timestampedReports` false.
   The thresholds are in the profile only; `./mvnw verify` does not run it.
3. **Script** — during work, `./jbang assess <target>` prints the scoped command for the main
   classes changed in the working tree, one per module:
   `./mvnw -Ppit -pl <module> test -DtargetClasses=a.b.C*,a.b.D*`. About two minutes on
   agent-journal against 25 for the module.
4. **AI** — read the survivors by class. A survivor is one of: a missing assertion (write the
   behaviour test: exit codes at their boundaries, both branches of each option, each refusal);
   dead or defensive code (delete it or state why it stays); an equivalent mutant (a boundary
   on a comparison the test's timing cannot distinguish, a second destroy call the stand-in
   does not need). State the equivalent ones in the commit rather than claim them; counting
   them by hand is the judgment the threshold cannot make, and goes to a judge with a rubric
   when it matters.
5. **AI** — at acceptance, the whole module: `./mvnw -Ppit -pl <module> test`. Record the
   generated, killed, no-coverage and timed-out counts and the score in the commit body; a
   timed-out mutant that broke a supervision loop counts as killed and is said so.
6. **AI** — append to `evidence/`: the scores before and after, what moved them, the survivors
   not pursued, and the cost per run.

## Known shapes

- **Lifecycle stand-ins under mutation.** Tests that supervise a child process or poll with a
  grace period turn every mutant on the loop into a timeout; the run is slow for the right
  reason. Keep those tests, scope the run.
- **Behaviour tests move the score; prose pins do not.** agent-journal went from 69 to 73
  percent on one module with tests on exit-code meaning, option parsing through a real launch
  and refusal paths, not with assertions on strings.
- **Boundary mutants on timing comparisons** (`>=` against `>` at a 20 ms poll) are
  indistinguishable by a 1 s test limit and stay as stated survivors.
