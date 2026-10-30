# Consumer dependency compatibility

The producer's successful build does not establish that its consumers resolve the same
dependencies. Maven uses declaration order to break ties at equal dependency depth;
dependency management in the producer can mask that difference. The
[Maven dependency mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html)
describes these rules. Publication transforms also matter: the
[Flatten Maven plugin](https://www.mojohaus.org/flatten-maven-plugin/)
can replace the POM used for installation and deployment.

`tests/ConsumerClosureTest.java` uses a real Maven reactor with a release flatten profile.
A library depends on a sibling that exposes SLF4J 2.0.18 and on zt-exec 1.13.0, whose
transitive SLF4J version is 1.7.32. The parent manages SLF4J 2.0.18. Reordering the
library's dependencies leaves its Java API and producer build unchanged while changing
the external consumer's selection.

| Evidence | Result |
|---|---|
| Original publication order | External consumer selects SLF4J 2.0.18. |
| Reordered publication | External consumer selects 1.7.32; producer still selects 2.0.18; japicmp reports unchanged. |
| Consumer comparison without a recorded reason | Rejects the version change. |
| Exact publication/dependency/before/after record with a reason | Accepts and reports the explicit change. Blank reasons and other-publication records fail. |
| Restored order plus a method-body change | Consumer closure unchanged. |
| Standalone nested Maven project outside the reactor | Its unrelated publication profile does not change discovery. |
| Missing snapshot or missing dependency POM | Incomplete evidence, including Maven's exit-zero missing-POM case. |

Run the proof with private Maven storage:

```text
./jbang tests/ConsumerClosureTest.java . /absolute/path/to/scratch-m2
```

The same mechanism resolved 34 publications in an agent-client reproduction. Putting
zt-exec before agent-model changed agent-tck's SLF4J selection from 2.0.18 to 1.7.32,
with 47 dependencies in either graph. Restoring the order restored all 34 publication
closures without a version exception. Parent and starter publications participated too.

The pass preserves actual publication POMs and independent consumer graphs alongside
its signature evidence. This check protects the resolved dependency versions and scopes
of those no-parent/no-BOM consumers; targeted behavior tests remain necessary for code fixes.
