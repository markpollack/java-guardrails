# A quality pass you can review and undo

Use this procedure before the individual gate playbooks. A pass owns an isolated local branch,
preserves public/protected API, and accepts no findings it introduced. A successful result is
verified scope with explicit inherited follow-up work; it is not a claim that all possible debt
has disappeared. Nothing is pushed or merged.

## Start from the maintainer's request

Say what will happen: prepare a local branch, preserve published APIs, fix introduced findings,
verify checks, and return a report explaining what to keep or undo. Ask for missing project scope
only when it cannot be resolved from recorded decisions and the defaults below.

Use JDK 21+ and the target's Maven wrapper. Commit or set aside unrelated edits before starting.
Run:

```
<kit>/jbang <kit>/scripts/Pass.java start <target> <kit>
```

Work in the printed worktree. The original checkout and branch stay available. The pass writes
`config/guardrails/decisions.md` and keeps large evidence in locally ignored `.guardrails-run/`.
Never force-add that directory. Do not install/update another session's global skill.

Mode is one decision key: `GUARDRAILS_MODE: unattended` (the 0.4.0 default) or
`GUARDRAILS_MODE: control`. Preserve explicit answers and unrelated keys. Label automatically
recorded decisions as kit defaults with the evidence/reason, not as choices the owner made.
If existing entries conflict, resolve the conflict before proceeding; never silently overwrite it.

Record hands-off scope before the baseline. Entries use `selector | reason`, separated by
semicolons. Examples:

```
HANDS_OFF: member:com.example.Client#call(Request) | awaiting redesign; class:com.example.WireSchema | published protocol
```

Selectors are `module:path`, `class:fully.qualified.Type`,
`member:fully.qualified.Type#method(ParameterType,OtherType)`, or `file:relative/path.java`.
Parameter spellings match the source declaration; overloads must be exact. Nested types use `$`.
Unmatched or ambiguous selectors block the pass. Module/class selectors protect production
code; tests and gate configuration can still change. Hands-off declarations are compared exactly,
including their comments/annotations. They remain measured and receive no allowance for new debt.

Run the original inventory before refactoring:

```
<kit>/jbang <kit>/scripts/Pass.java baseline <worktree> <kit>
<kit>/jbang <kit>/scripts/Assess.java <worktree>
```

The original source is taken from the immutable start commit. Scope decisions are resolved
before aggregation or the first gate. Default measurement uses scratch overlays, without installing
new enforcement in the target. Maven uses a private repository, never `install` into `~/.m2`.
Public API includes reactor source modules even when they are excluded from quality gates.

## Policy for every routine stop

In unattended mode, use the following defaults. Explicit current owner scope and recorded answers
win, subject to API preservation, complete measurement and no introduced findings. In control mode,
relay unanswered decide blocks verbatim and wait. Mechanical notifications continue in either mode.

| Decision | Unattended action | Boundary |
|---|---|---|
| TEST_SUPPORT_MODULES | Gate published or uncertain support modules, including a TCK. Resolve before assessment. | Write own-module tests; consumer tests do not supply the owning module's coverage report. Preserve legacy `exclude` entries. |
| EXCLUDED_MODULES | Preserve explicit exclusions. Exclude samples automatically only with positive unconditional publication-skip evidence, including release profiles. | Names alone do not prove nonpublication. Unknown scope stays included. An exclusion never removes API protection or answers the support question. |
| GATE_ORDER | Record the assessment's deterministic order, preserving an existing order. | This schedules installation, not regression measurement. |
| PMD_MECHANISM | Fix within API/hands-off constraints. For eligible inherited remainder, record a decreasing per-module PMD ratchet and exact reasons. | New findings cannot enter the count. CPD allowance stays zero. |
| CLASS_SIZE | Apply a proven structural recommendation narrowly, with the actual shape and reason. Otherwise refactor or retain eligible inherited PMD debt under its ratchet. | No public type reshaping to clear a metric. |
| THRESHOLD_FIT_* | Keep the reference and fix/ratchet eligible inherited debt. | Never invent a higher threshold for the worst code. Preserve explicit reference-wrong decisions and disclose them. |
| ERRORPRONE_DISABLED | Fix mechanically safe findings, including frequent style checks. | Preserve explicit named choices. New API-blocked inherited findings use the narrow exception below, never blanket disable-all. |
| SPOTBUGS_MECHANISM | Fix selected bug/concurrency findings; narrowly exclude proven false positives with reason. | There is no generic SpotBugs count ratchet in this kit. If no honest narrow enforcement works, completion is blocked. |
| ARCHUNIT_CYCLES | First repair/undo introduced cycles. Attempt API-preserving fixes for inherited cycles; explicitly defer remaining inherited scopes and gate cycle-free modules. | A cycle containing a public class does not by itself prove a fix would break API. Never omit a module after a crash. |
| JACOCO_MISSING_REPORTS / JACOCO_OWN_TESTS | Write/enable own-module tests, then measure actual reports. | No zero floors. Do not activate external-system IT suites blindly. A plan line or skipped module is not remediation. |
| JACOCO_FLOORS | Propose positive floors from complete actual reports. Preserve existing positive floors and fill only missing scope. | Never lower a floor to hide a regression. A saved zero must be replaced with a measured positive floor. |
| Optional gates | Include every configured/selected check in regression measurement. | Unconfigured NullAway/PIT/Lincheck is **not assessed**. A dormant NullAway processor with no adopted marked/package scope is not an assessed nullness contract. Do not invent an adoption contract or silently activate external tests. A configured adapter that cannot prove completeness halts. |

A missing JaCoCo report is not measured zero coverage. Baseline attribution may identify complete
bytecode as unexecuted only when Maven proves no default-phase tests ran (including an explicit
Surefire zero-tests probe for IT-only modules). This classification permits writing tests first;
it does not create a report, floor or successful coverage gate. Unknown missing output halts.

For inherited compiler findings whose only fix would break API, use the smallest supported
external/member exception. If necessary, a named `@SuppressWarnings("CheckName")` on the smallest
declaration is allowed with the affected member, check and API reason recorded in decisions.
Javac `removal` is separate from Error Prone. Scratch measurement strips warning suppressions and
uses Error Prone's suppression-independent option so these findings stay visible. Never use
`all`, a whole-file all-check exemption or an exception for an introduced finding. Prove a nearby
new finding still fails enforcement before accepting the exception.

## Each hotspot is a transaction

Follow the gate's playbook for installation, calibration and fixes. Before a candidate can
influence a stop, count or allowance, compare full findings. For every hotspot:

```
<kit>/jbang <kit>/scripts/Pass.java check <worktree> <kit>
<kit>/jbang <kit>/scripts/Pass.java accept <worktree> <kit> "<one hotspot message>"
```

`check` measures every default gate and each configured/selected optional gate, including gates
not installed yet. It compares against both the original start and last accepted snapshot. Finding
identity is rule plus bound class/member, with multiplicity. Line shifts do not create findings;
moving a finding to another declaration does. Once fixed, a finding cannot reappear as inherited
debt. No token/AST normalization or moved-code matching is used in 0.4.0.

Configured PIT keeps its effective mutation scope. The Lincheck adapter supports Java test classes
with explicit `ModelCheckingOptions`, `StressOptions` or `LinChecker.check` invocations and fresh
Surefire outcomes. Its test-source contract is frozen across hotspots so removing or weakening the
model cannot hide a regression. Unsupported layouts halt for an adapter; they are not assessed as
zero. These checks cover the configured model and mutation scope, not every possible behavior.
NullAway's marked/unmarked declaration scope and checker options are also compared: a new private
opt-out cannot hide a finding while leaving the public API unchanged.

The API comparison uses pinned japicmp 0.26.5 at protected visibility, includes synthetic members
and transitive external ancestry, and rejects all shape changes, including compatible additions.
Both dependency classpaths and original artifacts are preserved. Missing classes are incomplete
comparison, never ignored. This protects signatures, not all runtime behavior; add behavioral
regressions for the actual fix.

The consumer dependency check separately stages the actual publication POMs and artifacts
in private Maven storage, then resolves each publication in a fresh consumer with no parent,
imported BOM, project `.mvn/maven.config`, or inherited Maven settings. It includes attached
binary JARs (except source and Javadoc archives), and preserves resolved graphs and POMs.
This catches Maven mediation changes caused by dependency order even when japicmp and the
producer's managed build remain green. The original start is immutable; every hotspot and
the finish report compare against it. Unknown packaging, missing current publication output
or failed dependency resolution halts. A cached producer POM cannot substitute for this build.

Verify publication profiles against the project's release command before baseline capture.
The kit recognizes `release` or one unambiguous publication-transform profile. For other
layouts, record `PUBLICATION_PROFILES: profile-a,profile-b` (or `none` for the default build).
Multiple ambiguous publication profiles require this recorded answer. Capture invokes package
and the local install goal only; it never invokes deploy. No install goes into `~/.m2`.

Prefer repairing accidental version changes. For an intentional dependency change, record
one exact line per affected publication in `config/guardrails/decisions.md`:

```text
DEPENDENCY_CHANGE: group:module:jar: | group:dependency:jar: | 1.0@compile | 1.1@compile | Concrete reason for this change
```

The trailing colon denotes an empty classifier; include the classifier for attached artifacts.
The comparator prints the exact coordinates and version/scope values. Additions and removals
use `absent`. No wildcards, blank reasons or unrelated-module exceptions are accepted. These
records permit dependency changes only; they cannot permit a Java API change or introduced
finding. The end report lists the before/after values and reasons instead of claiming that
explicitly changed closures are unchanged.

Only the exact checked tree may be accepted. The full build must pass, installed gates must be
falsified, and plants restored. Concurrent changes prevent automatic acceptance; preserve and
inspect them. Never roll back another writer's files. Record full validation wall time per hotspot.

## Halt, resume, or stop work

When measurement fails, stop before the next change in either mode. Preserve accepted commits,
state, original start and diagnostics. The `KIT_DEFECT (halt)` detail should include kit version
and source identity, target/start/current commit, affected module/check, sanitized command/profile,
exit status, Maven's original error and log pointer, expected versus observed, last accepted step
and reproduction instructions. A target build failure does not itself establish a kit bug.
Diagnostic settings may explain a failure; they cannot mint an accepted result or omitted scope.

On cancellation, stop owned builds, restore owned plants and identify any unaccepted working diff.
Do not call the run complete. Resume with:

```
<kit>/jbang <kit>/scripts/Pass.java resume <worktree> <kit>
```

Resume requires the same kit version. A new kit version starts a new run at the original start
commit, reusing recorded decisions. Never recapture the current changed tree as the original.
Changed or missing original evidence blocks acceptance; recreate it from that original commit.

## Finish for a person who has never read this kit

Generate the report from accepted evidence with `Pass.java report <worktree> <kit>`. Review its
accuracy and add concrete maintainer guidance: what changed, which modules are enforced or
excluded, why inherited debt remains, and ranked next actions. Name the real commands and commits
to review or undo. Do not replace computed introduced counts with reassuring prose.

A successful report requires complete required measurements, unchanged API, zero introduced
findings for every assessed check, green accepted commits and falsified enforcement. Report
unconfigured optional checks as not assessed. Coverage's raw remainder is uncovered instructions,
not a count of bugs. Distinguish inherited exceptions and enforcement scope from full measurement.
A blocked scope or unknown count means incomplete, with the last verified commit named.

After reviewing the report, run `Pass.java finish <worktree> <kit>` to commit only that report; any other unaccepted edit blocks this step. An incomplete report remains explicitly incomplete.

The target branch carries decisions and the report; evidence remains local and ignored. Keep the
original branch while the maintainer reviews. Reverting one hotspot may require reverting dependent
commits and rerunning verification. Never merge or publish the target on the maintainer's behalf.
