# Maven reactor scope and checker isolation

`./jbang tests/ReactorScopeTest.java .` exercises real Maven builds using the shipped plugin
slices. It retains a temporary fixture and the command, exit code and complete output of each run.
The sibling fixture uses unique coordinates, so preexisting local artifacts cannot satisfy the
fresh-dependency case. It then installs an incompatible sibling API and changes that API in the
working tree to check stale-cache behavior explicitly.

Before the fixes, coverage and cycle measurement failed to resolve the fresh sibling, as did
PMD, Error Prone, SpotBugs, JaCoCo and ArchUnit falsification. CPD's source-only goal already
caught its plant in that fixture. Coverage also failed with the deliberately stale installed API.
The nested-project fixture selected a standalone Maven tool absent from the root's modules and
failed before tests; the diagnostic filter printed no Maven cause. A legacy exclusion of that
tool suppressed PMD's unrelated test-support decision.

The regression requires:

- Fresh and stale-cache sibling cases build from reactor source, with only selected modules
  included in measurement output.
- Every affected falsifier catches and restores its plant; disabling that gate rejects proof.
- Error Prone's JDK-activated profile cannot intercept other gates; a genuine upstream Error
  Prone failure cannot prove a plant in the selected consumer.
- A standalone nested project is absent from inventory, Maven selection and plant selection.
- General exclusions and the test-support answer are independent, while legacy test-support
  exclusions and explicit `gate` answers retain their behavior.
- Empty selections do not turn into whole-reactor builds. Pre-test compiler errors are visible
  from coverage, Error Prone, SpotBugs and ArchUnit measurement.
- Maven resolves active and inactive profile-defined modules instead of filesystem discovery
  guessing membership.

Scoped Maven invocations use a clean reactor lifecycle with `-am`. The shared command policy
controls only the kit's known profile and plugin properties. Upstream lifecycle work can still
fail; it is not reported as a successful measurement or a proven selected plant. External
artifacts remain subject to Maven repository settings. Module inventory uses conventional main
source roots within the target; custom source layouts are outside this proof.
