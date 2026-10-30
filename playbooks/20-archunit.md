# ArchUnit: no cycles, then layers

> Status: no-cycles proven on the MCP Java SDK (2026-10-08, `evidence/mcp-java-sdk.md`: measured, decided, installed on the cycle-free modules, falsified) after acp-java. Layers not yet.
> Each step is a **script**, an **AI** task, or a **stop** for the owner. Maven, JUnit 5.
> Evidence for this gate records which `assess` fact (module count, churn) predicted each cycle found, or that none did.

The gate: a `NoCyclesTest` in each gated module's test sources, from
`configs/archunit/NoCyclesTest.java.tmpl`, with two rules over the module's main classes: no
dependency cycles between packages at any depth, and none between the top-level classes of one
package. ArchUnit derives the slices from the class files; nothing about the layering is
assumed. A `layeredArchitecture()` rule is a second step that needs the owner's confirmation of
the layers; it is not part of this playbook's proof yet.

## Steps

1. **Script** — `./jbang measure-archunit <target>` (from elsewhere:
   `<kit>/jbang <kit>/scripts/ArchUnitMeasure.java <target>`). Needs nothing installed: compiles
   the gated modules, imports each one's `target/classes`, derives the root package, evaluates
   both rules as ArchUnit the library, and prints every cycle with the dependencies that close
   it, per module. Every imported package participates, including the root itself. A missing
   or empty main-class import fails measurement; it is never reported as a cycle-free module.
2. **Stop** — `ARCHUNIT_CYCLES`, only when cycles exist: break them all now (one per commit, API
   changes recorded) or gate the cycle-free modules first.
3. **AI** — install: `com.tngtech.archunit:archunit` (test scope, version from
   `maven/properties.xml`; not the JUnit engine artifact, which pins a JUnit Platform version)
   in each gated module's pom; the template into each module's test sources with the root
   package filled in (it is in the measurement; if there is no common named package, choose
   any valid test package). That package declaration does not restrict the import. The test
   imports the module's own compiled classes and runs with the module's tests, so
   `./mvnw verify` is the gate.
4. **AI** — break the cycles the measurement listed: move the type that the lower package
   reaches up for, or invert the dependency through an interface; one cycle per commit, tests
   green after each. A moved public type is a breaking change: record it.
5. **Script** — `./jbang falsify <target> archunit`: plants two classes in the first gated
   module's first two packages (both in the same package if there is only one) that reference
   each other, runs that module's `NoCyclesTest`, deletes both, and passes only if the test
   failed naming the planted cycle.
6. **AI** — later, the layers: dump the package dependency graph, propose a layering, **stop**
   for the owner, then encode it as a `layeredArchitecture()` rule beside the cycle rules.

## Known shapes

- **A single-package module.** Package slicing uses each class's complete package name, so
  the root package is a real slice even without subpackages. An acyclic module passes; a
  cycle between its top-level classes still fails the separate class rule. Keep the template's
  `importsSomething` test. Do not set `archRule.failOnEmptyShould=false` globally as a fix.
  For previously copied tests, replace the root-prefix package rule with the new `PACKAGE`
  assignment and retain the class rule and nonempty-import assertion, then falsify again.
  This also detects package cycles involving classes at the root that the old pattern omitted.

- **A cycle through a shared "spec" package.** Types that the protocol defines and the
  transport implements tend to reference each other both ways; acp-java had one in core and one
  in agent support, both broken by moving types, both breaking.
- **An empty or partial import passes vacuously.** Importing an anchor class's package tree
  misses sibling packages (the SDK's Jackson modules); importing a package name from the
  classpath pulls in other modules' classes. The template imports the module's own compiled
  classes and has a test that fails when nothing was imported.
- **ArchUnit's JUnit engine and JUnit 6** do not mix: the engine's platform dependency wins by
  declaration order and no test starts. The plain-library form has no such coupling.

In 0.4.0, published APIs stay frozen. Repair introduced cycles before the ARCHUNIT_CYCLES decision; only inherited cycles may enter a deferred scope. Run the full pass check per hotspot, including before this gate is installed.
