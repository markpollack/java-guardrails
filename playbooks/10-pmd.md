# PMD: size, complexity and duplication

> Status: not written. The procedure below is the outline; each numbered step will say whether it is a script, an AI task or a stop for the owner.

- AI: add `maven/pmd.plugin.xml` and its properties to the parent pom, report mode.
- Script: `./jbang measure` prints each metric's distribution and proposed knee.
- Stop: owner confirms thresholds.
- Script: write `configs/pmd/ruleset.xml` with those thresholds into `<target>/config/pmd/`.
- AI: make the build green (all at once, or `maxAllowedViolations` as the ratchet, or one module at a time).
- Script: `./jbang falsify pmd` plants a long method, expects red, reverts.
- AI: flip to fail, commit with the measurement in the body.
