# Evidence: acp-java (agentclientprotocol/java-sdk), October 2026

The one data point so far. Every gate was installed with the same loop: report mode, measure,
thresholds at the knee, fix rather than baseline, falsify by planting, then fail the build.
Commits are in that repository's history.

| Gate | What it found | Config copies as-is? | Work to go green |
|---|---|---|---|
| PMD complexity + CPD | 35 violations and 5 copied blocks; a 1,045-line transport split to 322; a 19-argument constructor became a handler table; worst cognitive complexity 18 to 7 | Ruleset yes; thresholds re-measured per repo | High: the refactors are the payoff |
| Error Prone defaults at ERROR | 3 real bugs (stdio charset, Turkish-locale media types, opaque ClassCastException) plus a reconnect race | Yes; only the style disable-list is per repo | Low: JDK 21 profile, jvm.config, one triage pass |
| SpotBugs rank 9 + concurrency filter | 308 raw findings, 3 at rank 9 or less: 1 real lock bug, 2 false positives | Yes, both filter files | Low; about 26 s per build |
| ArchUnit | A spec/error package cycle in core, an agent-support package cycle, a five-class Jetty cycle | Only the no-cycles rules; layers per repo | Medium; fixes were breaking |
| JaCoCo floors (measured minus 2) | 1 bug while writing tests to hold the floor (agents registered by class were created per request) | Mechanism yes | Low |
| NullAway, JSpecify, OnlyNullMarked | Shares the Error Prone bugs; null-marking surfaced 3 more through a Qodana scan | Mechanism yes | High: package by package |
| Lincheck | 4 bugs at install, 3 more later (cancel released the prompt lock early; a lost completion; EOF reply loss) | No; models are per repo | High: state extracted first; about 70 s per build |
| PIT | 1 leak (timed-out requests never left the pending map); 44 tests; score 61 to 90 percent | Profile yes; targets per repo | Medium; minutes of runtime |

Measured thresholds that produced the PMD ruleset: cognitive complexity hotspots 34, 24, 19, 18,
ordinary methods 10 or less; method NCSS long methods 35 to 53, every other 29 or less; class NCSS
392 then 253; class cyclomatic 114 then 72; two lambdas at 30 or more statements, all others under 25;
no method beyond 6 parameters except one 19-argument constructor.
