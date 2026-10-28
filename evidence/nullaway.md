# NullAway: package adoption and gate proof

## Local compiler fixture, 2026-10-27

`./jbang tests/NullAwayTest.java .` builds a temporary Maven project from the kit's actual
Error Prone profile, properties and JVM arguments, using JDK 21, Maven 3.9.9, Error Prone
2.50.0, NullAway 0.13.8 and JSpecify 1.0.0.

The fixture has one marked package and one unmarked child package. A null returned from a
non-null method produces one unique NullAway report finding and fails the ordinary compile.
Fixing it produces zero findings; falsification then proves the gate with a new nullable return
inside the existing marked package. Disabling NullAway cannot pass that proof. Comment-only
markers, an unrelated compiler failure, and a colliding target-owned probe cannot pass either.
Sources remain unchanged after the probe, including negative runs that complete normally. The fixture also compiles a
dropped exception with other Error Prone checks disabled, while the NullAway plant still fails:
other Error Prone cleanup is not a prerequisite for nullness adoption.

The source inventory counts **exact package defaults**, not all checked declarations. It does
not recursively treat child packages as marked, or claim class/method annotation coverage.
`assess` reports processor presence and scope requiring proof; it retains the default gate order.

## MCP Java SDK: one existing package, 2026-10-27

An isolated export of the previous trial target at `61a1db89a477f80783c8563bf68c2bba0c8e131b`
was used. No target checkout was modified or pushed. The scope was the two-file
`mcp-core=io.modelcontextprotocol.json.schema` package, with a new JSpecify package default and
the JSpecify dependency in `mcp-core`. The other nine core packages stayed unmarked.

| Measurement | Result |
|---|---|
| Report-mode compile after marking | 3 unique NullAway diagnostics |
| Findings | Two factories construct a validation response with a null component; the default schema validator passes null to the successful-response factory |
| Fix | Mark the documented nullable response components and successful-response parameter `@Nullable`; also preserve the documented nullable schema parameter of `assertConforms` |
| Report-mode compile after annotation changes | 0 NullAway findings |
| `falsify nullaway --module mcp-core --package io.modelcontextprotocol.json.schema` | Build red, `[NullAway]` names the planted nullable return; probe removed |
| Normal restored `clean verify`, selected module and its dependencies | Green; 452 tests, zero failures/errors/skips; existing PMD/CPD, SpotBugs and coverage checks pass |

Commands, invoked from the kit with the scratch target substituted for `<target>`:

```
./jbang scripts/NullAwayMeasure.java <target> --module mcp-core
./jbang scripts/Falsify.java <target> nullaway --module mcp-core --package io.modelcontextprotocol.json.schema
<target>/mvnw -B -P errorprone -Dspring-javaformat.skip=true -pl mcp-core -am clean verify
```

Prediction: no existing assess rule predicted these findings. Package marking exposed incomplete
nullability contracts already documented in the API; this experiment establishes annotation and
enforcement mechanics, not three new runtime bugs. It proves only the selected package, not
whole-SDK null safety or nullness across unmarked module boundaries. More precisely, the new-class
probe proves the package default on that unannotated class. It does not audit whether existing
classes and methods opt out, suppress the check or match compiler exclusions.

## Additional local compiler controls

`./jbang tests/NullAwayAdversarialTest.java .` includes the original compiler suite and adds
warning-only versus enforced-WARN checks, declaration opt-outs, javac warnings, same-line
diagnostic columns, upstream reactor scope, colored/quiet output, marker text inside an
annotation string, and Linux process-group SIGINT. A package excluded by
`UnannotatedSubPackages` also rejects an incorrectly self-marking probe.

Falsification now requires a green valid-probe control, then changes only its return expression
to null and requires a red compile and the named NullAway diagnostic. This preserves a WARN
check enforced by `failOnWarning=true`, while rejecting pre-existing compiler failures as proof.
The quiet-report case is refused rather than misreported as zero. Ordinary shutdown cleanup is
tested on Linux; SIGKILL, JVM crashes, Windows and newer Maven/compiler output formats remain
outside this evidence.

## Shared falsify restoration, 2026-10-28

The owned-plant helper is shared by all seven falsifiers. A Linux stand-in build that remains
active until signalled verifies restoration and build termination for each gate, both process-group
SIGINT and JVM-only SIGTERM. Separate ownership checks preserve atomically replaced files even
when their bytes still match the plant, and reject symbolic-link sources before modifying them.
These checks were observed failing on the earlier implementation before the fixes passed.

All seven falsifiers also caught real planted violations on the isolated MCP SDK trial, with
source hashes identical before and after each invocation. This checks ordinary Maven execution
separately from signal behavior. It does not establish cleanup after SIGKILL, crashes, or on Windows.
