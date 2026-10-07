# SpotBugs: rank 9 or less, plus concurrency at any rank

> Status: not written. The procedure below is the outline; each numbered step will say whether it is a script, an AI task or a stop for the owner.

- AI: add `maven/spotbugs.plugin.xml`; copy `configs/spotbugs/include.xml` and the empty `exclude.xml`.
- AI: triage the findings (expect a handful): fix, or exclude narrowly with a reason.
- Script: `./jbang falsify spotbugs`.
