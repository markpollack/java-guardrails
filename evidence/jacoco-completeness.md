# JaCoCo measurement completeness

`tests/JacocoCompletenessTest.java` builds a six-module Maven reactor with the target wrapper
and JaCoCo 0.8.15. It executes real tests and reads real class files/reports; no Maven success or
coverage report is mocked.

| Shape | Behavior reproduced in 0.3.1 | Required behavior |
|---|---|---|
| Main class, no tests | Missing report silently omitted from floors | Missing-report decide stop before any floors |
| Main class, only `*IT` test sources | Test sources compile but do not run by default; module omitted | Same stop; no automatic integration-profile activation |
| Test-support class exercised only by a consumer's tests | Consumer reports coverage, owning module omitted; scope not asked | Scope question first; gated support still stops without its own report |
| Abstract-only interface | No report silently omitted without classification | Bytecode proves no executable instructions; explicitly named, no floor |
| Interface default method | Missing report omitted | Executable bytecode means missing-report stop |
| Existing partial `JACOCO_FLOORS` | Missing modules invisible to saved-floor validation | Missing reports block validation; once reports exist, incomplete floors require a completion decision |

After adding default-phase tests in the owning modules, all five executable modules receive
coverage and floors. The interface-only module still receives none. Explicit exclusions and
legacy test-support decisions retain their effect. A compile failure before tests remains a
Maven failure with its original diagnostic, never a coverage proposal.

The early test-support question is exercised through both `assess` and direct JaCoCo measurement.
The fixture checks that no build starts before the direct-measurement scope question is answered.
`tests/ReactorScopeTest.java` retains reactor/plant proof and now records test-support scope
before coverage; the new completeness behavior does not silently exclude its support module.

The matching assessment signal is main sources with no own tests. Merely counting test sources
does not predict whether `*IT` tests execute; successful Maven compilation does not establish
coverage. JaCoCo instruction analysis distinguishes executable code independently of execution
data. The current measurement remains per-module, using `target/classes` and
`target/site/jacoco/jacoco.xml`, with the default `test` phase. It does not implement aggregated
consumer coverage or activate target-specific integration profiles.

A 1.00 default floor does not protect a module when JaCoCo skips for missing execution data.
Resolve missing-report stops and falsify the installed gate. The behavior and remediation
choices are documented in [the playbook](../playbooks/21-jacoco.md).

On an isolated MCP Java SDK copy, the updated measurement retained all three selected modules
and their existing floors: core measured 30.0/28.6, Jackson 2 measured 68.4/76.9, and Jackson 3
measured 59.1/76.9 (line/branch percent). The JaCoCo plant reduced core line coverage below its
0.28 floor and was reported by the real check. All 258 Java source files matched the original
committed source after restoration. All catalog aliases, the assessment expectations, existing
regression suites, skill validators and fresh-install measurement passed locally.
