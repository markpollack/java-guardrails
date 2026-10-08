# SpotBugs: rank 9 or less, plus concurrency at any rank

> Status: measured and installed on the MCP Java SDK (2026-10-08) after acp-java; going green.
> Each step is a **script**, an **AI** task, or a **stop** for the owner. Maven only. Runs on
> every JDK the build uses; no profile.

The gate: one SpotBugs analysis at `verify` on main sources, effort Max, threshold Medium,
reporting to rank 20, with **one include filter** that keeps every pattern at rank 9 or less
(the "scariest" and "scary" bands) and 32 multithreaded-correctness patterns at any rank
(`config/spotbugs/spotbugs-include.xml`, each group with its reason). A false positive is
excluded by class, method, field and pattern with its reason in `spotbugs-exclude.xml`, which
starts empty. Never a second `check` execution with its own filter: `check` forks the analysis
with the plugin-level configuration only, and the second would pass silently on a report that
was never written.

## Steps

1. **AI** — the facts from `assess`: which modules hold only a `module-info.java` or no classes
   (SpotBugs fails with "No files to analyze" on them: `<spotbugs.skip>true</spotbugs.skip>` in
   that module's pom), and which modules the owner left out of the gates (the same property).
2. **AI** — install in report mode: `configs/spotbugs/spotbugs-include.xml` and
   `spotbugs-exclude.xml` to `<target>/config/spotbugs/`; the three properties
   (`spotbugs-maven-plugin.version`, `spotbugs.version`, `spotbugs.includeFilterFile`) and the
   plugin block from `maven/spotbugs.plugin.xml` into the root pom. The include filter is a
   property so that `measure` can swap in an include-everything filter. `check` is bound to
   `verify` from the start; report mode is running `spotbugs:spotbugs` instead.
3. **Script** — `./jbang measure-spotbugs <target>` (from elsewhere:
   `<kit>/jbang <kit>/scripts/SpotBugsMeasure.java <target>`). Compiles and runs the analysis
   twice, with the gate filter and with every rank, and prints what the gate fails on by name
   (rank, pattern, class, method, line, message) and the long tail it leaves out by band and
   pattern. A STOP `SPOTBUGS_MECHANISM` only when more than 30 fail at the gate.
4. **AI** — triage each finding at the gate, expecting a handful: a real bug or risky pattern is
   fixed in the code, red-first where a test can show it; a false positive is one `<Match>` in
   `spotbugs-exclude.xml` naming class, method, field and pattern, with the reason. Never a
   whole pattern or package. The long tail (exposed representations in records, inner classes
   that could be static, constructors that throw) stays out by design; a pattern joins the
   gate only through the include filter with a written reason.
5. **Script** — `./jbang falsify <target> spotbugs`: plants a counter written under the monitor
   and read without it (`IS2_INCONSISTENT_SYNC`, rank 17, kept by the concurrency list and
   invisible to Error Prone), runs the module's `spotbugs:check` with the errorprone profile
   off, and passes only if the build went red and the report names the planted method.
6. **AI** — `./mvnw verify` green; commit with the counts at the gate and below it; append the
   code base to `evidence/`.

## Known shapes

- **Lazy static initialisation under `synchronized` static methods** reports
  `USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION` (rank 7, the class object is a public lock) and,
  when the field is read again outside, `NP_NULL_ON_SOME_PATH` (rank 6). The fix is a private
  lock object or a holder class, not an exclusion.
- **`IS2_INCONSISTENT_SYNC` at rank 17** is the pattern the rank gate alone misses and the one
  a transport library most needs; it is in the include list for that reason.
- **The long tail is almost all `EI_EXPOSE_REP*`** (rank 18): records returning the lists and
  maps they hold. 263 of 283 on the SDK core. Advice, not gated.
