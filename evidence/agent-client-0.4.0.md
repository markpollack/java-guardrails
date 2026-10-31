# Agent-client 0.4.0 candidate evidence

The replay starts from agent-client commit `2655defc60d15e9a0430a1102ab38335bdc4333c`
in an isolated local branch with private Maven storage. The existing guardrails
installation and the original project worktree are untouched.

## Compatibility checks

Independent Maven builds and japicmp comparisons reproduced the published-shape
regression in `d8fcd10`: ten providers changed their superinterfaces, configuration
bean methods changed their return descriptors, and a protected TCK field changed
its descriptor. Twelve of the 23 publication-eligible source modules changed.

The restored control, `1a9430a22699e4615620e456d77734fa62348e29`, restores those
changes. Twenty-two modules compare unchanged; the auto-configuration module still
has a historical change to `DeprecatedPropertyMigrator`'s Spring Boot interface.
That remaining difference is detected, not silently accepted. The replay must
retain the original interface. Comparator inputs were independently built JARs and
dependency classpaths; the existing javap comparison was not used as input.

Fixture plants cover inheritance, method and field descriptors, generic signatures,
visibility, checked exceptions, compatible additions, retained annotations, and
incomplete classpaths. A method-body-only change remains green. Structured change
status is checked in addition to japicmp's process exit status.

Consumer compatibility is checked separately for 34 publications. A dependency
reorder makes agent-tck consumers select SLF4J 1.7.32 instead of 2.0.18 while the
producer build and japicmp remain green. The closure check rejects it; restoring
the order restores all closures. See [the consumer dependency proof](consumer-dependencies.md).

## Runtime changes and measurements

Measurement shares a fresh build across its checkers. Source-only candidates measure
changed modules and reactor dependents, preserve unchanged-module evidence, and record
the compared trees plus `no POM changed` before reusing consumer closures. POM changes,
gate boundaries, and finish require full measurement; start and finish always capture
consumer closures. Original enforcement builds and planted failures remain separate
boundary work.

The reactor dependency graph includes ordinary dependencies, reactor build plugins,
plugin dependencies, annotation processors including execution configuration, and
build extensions. A red-first graph fixture exposed the previously missing build
inputs. Recomputing agent-client's effective graph after the correction gave the same
graph used in its earlier scoped comparisons.

Three actual replay samples produced identical scoped and full finding multisets for
all five default checks. A fixture also propagates an upstream change into a dependent
module and rejects a deliberately omitted downstream finding. A red batch fixture
requires bisection and repair before acceptance, preserving individual hotspot commits.

| Observation | Measured wall time |
|---|---:|
| Same full coverage candidate before shared-build changes | 991.800 s |
| Same full coverage candidate after shared-build changes | 535.930 s |
| 15 compiler module batches | 1179.409 s total; 78.627 s mean |
| 17 complexity module batches | 2222.309 s total; 130.724 s mean |
| Final Claude batch, ordinary validation | 99.963 s |
| Additional full comparison for that batch | 288.087 s |

These observations use warm private repositories with concurrent compatibility and
trial work. They are not a controlled cold-start benchmark. The under-one-hour target
was not met. The complete development run took 450.9 minutes, including investigation,
editing, rejected candidates, and interruptions from the original start time. The later
compiler and complexity batches alone took 56.7 minutes, before boundaries and finish.
Validation timings alone are not end-to-end timings.

## Defects reproduced during the replay

- A descriptor-only module failed ArchUnit import. The fixture first reproduced the
  failure, then verified the fix for a compiled `module-info.class`. Missing output
  for ordinary code or the descriptor still fails. The actual MCP project then
  completed its cycle snapshot, including its descriptor-only module.
  Full shared-build compatibility exposed a second path: SpotBugs cannot analyze
  a descriptor-only module. The extended fixture reproduced that failure and now
  completes all five inventories with the module retained. The scratch overlay
  skips that empty SpotBugs analysis; missing executable-code reports still fail.
- A SpotBugs enforcement filter leaked into all-rank measurement and falsely removed
  findings. A real Maven fixture reproduced it. Explicit neutral measurement filters
  restore the complete inventory under both include and exclude property controls.
- Excluded sample applications inherited javac `-Werror` even with Error Prone
  disabled. The compiler installation guidance now covers both controls while
  preserving their ordinary compilation.
- ACP compatibility exposed Maven POM dependencies in API classpaths. Copying that
  XML under `.jar` names made the comparison incomplete. A real reactor fixture
  reproduced the failure with both reactor `pom.xml` and repository `.pom` entries.
  Capture now validates and omits that non-bytecode metadata while rejecting corrupt
  JARs. A public return-type plant still fails. Corrected capture of ACP's saved
  outputs preserves every API class byte and compares unchanged across all 17 source
  modules. Agent-client's original and final classpaths contain no POM entries.
- MCP's `dependency:properties` lifecycle goal adds coordinate-named properties
  containing colons. Capturing the effective model afterward produced invalid XML.
  A real lifecycle fixture reproduced it; model capture now precedes lifecycle
  execution in the same Maven invocation. The fixture retains its reactor edge,
  all five inventories and publication closure. Earlier capture retains all eight
  MCP source modules and produces exactly the same graph for agent-client, preserving
  the three scoped/full comparisons. The omitted-dependent plant still fails.

## Completed replay

At the last accepted source batch, 95 steps are accepted and every assessed check has
zero introduced findings. Coverage, compiler, bug, and complexity measurement covers
23 selected library modules; two sample applications are explicitly excluded from
quality checks while retaining API protection.

| Measured finding | Original | Current |
|---|---:|---:|
| Uncovered instructions | 14617 | 9988 |
| Compiler diagnostics | 115 | 23 |
| All-rank SpotBugs findings | 144 | 134 |
| PMD/CPD findings | 70 | 0 |
| Dependency cycles | 12 | 9 |

The compiler remainder consists of measured inherited API-bound exceptions with
specific checks and declaration-level reasons. Unconfigured optional checks are
not assessed. Nesting the private catalog implementation and loader in the existing
Builder removed three cycles without changing published API. Own-module tests cover
the moved failure paths; the strict declaration-bound matcher reports no introduced
uncovered instructions or other findings.

The PMD/CPD boundary passed ordinary reactor verification and all installed gate
plants in 749.139 seconds. The ArchUnit boundary passed in 768.747 seconds, including
the ordinary build and six distinct plants (coverage, compiler, bugs, complexity,
duplication and cycles). Cycle enforcement covers the 20 cycle-free selected modules.
The nine inherited cycles in core, Gemini SDK and SWE SDK remain measured and
explicitly deferred; their public type relationships need a compatible redesign.

Mandatory final full validation passed in 630.128 seconds, including fresh resolution
of all consumer publication closures, API comparison, complete finding inventories,
normal enforcement and all six plants. The final source commit is
`2341d8ed494feb3edf5bfc0867848de22d7a3fcf`; report-only commit
`ad99a1acb8b83a6d10bff6ddb695d8e70ecbc469` records the scope, remaining work,
review/verification/undo commands, timings, and the missed runtime target.

The report leads with the result and next actions, keeping full module lists and
commit timings in expandable details. Review checked its instructions, scope,
counts, retained exceptions and timing against the saved validation evidence.
