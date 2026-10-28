# PMD ratchet and CPD isolation

## Real Maven fixture, 2026-10-28

`./jbang tests/PmdRatchetTest.java .` creates a temporary parent and source module from the kit's
actual PMD slice, properties and rules. Maven 3.9.9 runs plugin 3.28.0 with PMD 7.28.0. The
module has one `ExcessiveParameterList` finding and a measured PMD allowance of one.

Before the fix, that property also allowed CPD duplication: the planted copy appeared in
`cpd.xml`, but `verify`, plain `pmd:cpd-check` and the named CPD goal exited zero. The falsifier
correctly refused that result as proof. Raising the PMD property to 999 also weakened plain CPD.

The revised slice gives PMD and CPD separate executions. Common plugin configuration and the
CPD execution explicitly allow zero; only the PMD execution reads `pmd.maxAllowedViolations`.
The fixture requires:

- `verify` and `pmd:check@pmd-gate` accept the existing finding at allowance one.
- Plain `pmd:check`, or lowering the named execution's allowance to zero, rejects it.
- A new PMD plant exceeds the ratchet under `verify`.
- Duplication fails `verify`, plain CPD and named CPD; raising the PMD property cannot loosen
  plain CPD.
- Both falsifiers catch their plants and restore the original source; restored `verify` is green.

These checks run in CI after local proof. They cover the supplied slice; older copied plugin
configurations require the migration in playbook 10, including removal of inherited positive
plugin-level allowances.

The isolated MCP Java SDK trial from `evidence/nullaway.md` was also upgraded to this slice.
Restored `clean verify` with Error Prone enabled passed 452 tests with no failures, errors or
skips. Both PMD and CPD falsifiers then caught real plants, with every source hash unchanged
after restoration. No target checkout or upstream branch was changed.
