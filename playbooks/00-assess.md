# Assess the target

> Status: not written. The procedure below is the outline; each numbered step will say whether it is a script, an AI task or a stop for the owner.

- Script: `./jbang assess <target>` prints build tool, JDKs, modules, root packages, quality plugins already present.
- Stop: owner picks the gates and, per gate, the mechanism: report-then-gate, ratchet, or scope-by-module.
