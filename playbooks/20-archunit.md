# ArchUnit: no cycles, then layers

> Status: not written. The procedure below is the outline; each numbered step will say whether it is a script, an AI task or a stop for the owner.

- Script: `./jbang assess` gives each module's root package.
- AI: drop `configs/archunit/NoCyclesTest.java.tmpl` into each module; run it; list the cycles.
- AI: fix the cycles (often breaking: changelog each).
- AI: dump the package dependency graph and propose layers.
- Stop: owner confirms the layering before it is encoded as a `layeredArchitecture()` rule.
