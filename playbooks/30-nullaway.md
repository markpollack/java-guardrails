# NullAway, one package at a time

> Status: measurement and falsification implemented; local compiler fixtures prove the gate and
> negative controls. Maven, JDK 21+. See `evidence/nullaway.md` for the scope actually tried.

NullAway is an Error Prone checker. It needs Error Prone's **compiler integration**, not a
completed cleanup of every other Error Prone finding. The kit's existing `errorprone` profile
already puts NullAway on the processor path, enables `NullAway:ERROR`, and sets
`JSpecifyMode=true` and `OnlyNullMarked=true`. With no marked code, that configuration can
compile green while checking none of your packages. Do not count that as an installed gate.

This is an optional adoption step outside the default `GATE_ORDER`: choose the nullness scope
explicitly. Run `measure-nullaway --inventory` before deciding which package to mark. Existing
recorded gate orders continue to work.

## Steps

1. **Script** — inventory, which needs no installed plugin or build:

   ```
   <kit>/jbang <kit>/scripts/NullAwayMeasure.java <target> --inventory
   ```

   It lists `module=package`, file counts, and JSpecify package defaults. The inventory counts
   exact package annotations, not class/method markers or `@NullUnmarked` exceptions. A package
   default does not apply to child packages. This first increment does not measure annotation
   coverage inside methods and classes, or audit `@NullUnmarked`, suppressions and configured
   exclusions. All existing classes could opt out while a new probe is still checked. Review
   existing declarations and checker configuration before claiming their coverage.
2. **AI** — use the compiler integration from playbook 11 if absent: the profile in
   `maven/errorprone.profile.xml`, its Error Prone/NullAway properties, and `maven/jvm.config`.
   Match the target's main compile execution ID; preserve its other annotation processors.
   Tests stay outside the main-source execution. This increment supports the kit's root-POM
   `errorprone` profile. Add `maven/jspecify.dependency.xml` to each module whose sources will
   import the annotations; dependency management alone does not put annotations on the classpath.
   JSpecify mode on JDK 21 needs `-XDaddTypeAnnotationsToSymbol=true`, already in the slice.
3. **AI** — choose a small package with the owner within the requested adoption scope, using
   the inventory. Add `configs/nullaway/package-info.java.tmpl` as its `package-info.java`, with
   the real package name. When the owner already named a package, use that authorization.
   Do not mark every package merely to improve a count. Public nullable contracts often need
   annotation changes before implementations; retain the behavior callers rely on.
4. **Script** — measure the selected module in report mode:

   ```
   <kit>/jbang <kit>/scripts/NullAwayMeasure.java <target> --module module-path
   ```

   Omit `--module` to compile the reactor. The script runs `clean compile` through the target's
   Maven wrapper, with the `errorprone` profile and dependencies of the selected module (`-am`).
   It demotes errors to warnings for this invocation, groups unique NullAway diagnostics by
   file, line and column, and exits nonzero when the report-mode build fails. With `--module`,
   its main count covers the selected module; findings from other reactor sources built by
   `-am` appear separately as upstream findings. A dependency build failure still makes the
   report partial. It changes no source or
   POM. Other checks may still report; fix build failures before trusting the count. A zero
   count alone cannot show that the checker ran. ANSI colors are stripped before parsing. If
   Maven logging hides warnings (for example `.mvn/maven.config` contains `-q`), the script
   refuses to report a count; use normal logging for measurement.
5. **AI** — fix the marked package. Use `org.jspecify.annotations.Nullable` on genuinely
   nullable parameters, returns, record components and type arguments; check before dereference.
   Annotate the declaration that owns the contract, including interfaces. Do not introduce a
   null check that changes an intentionally nullable API into a rejecting one. Add behavioral
   tests for discovered bugs. Narrow, explained configuration exceptions require evidence;
   neither removing the marker nor adding blanket suppressions is a fix.
6. **Script** — prove enforcement on a new unannotated class in that **existing** package:

   ```
   <kit>/jbang <kit>/scripts/Falsify.java <target> nullaway --module module-path --package com.example.api
   ```

   It first creates a reserved probe with a valid return and requires a green control compile.
   It then changes only the return expression to `null` and requires a red compile plus a
   `[NullAway]` diagnostic naming that return. Both builds use the same real gate flags. This
   accepts a WARN check legitimately enforced by `failOnWarning=true`, but cannot mistake a
   pre-existing build failure for a fatal NullAway finding. It does not add a marker or enable
   a disabled checker to manufacture success. Without selectors it uses the first marked
   package in path order. Success proves enforcement on this new unannotated class only;
   it does not audit existing declarations for opt-outs, suppressions or exclusions.

   Normal completion and ordinary JVM shutdown (including Ctrl-C/SIGINT and SIGTERM) attempt
   to remove the owned probe. An existing probe is refused without overwriting it. A changed
   or replaced probe is left untouched with a cleanup diagnostic. SIGKILL, a JVM crash or
   power loss cannot run cleanup: inspect and manually remove only a verified leftover before
   retrying. Do not commit a planted probe.
7. **Script** — after restoration, run the target's normal tests and gated compile. `clean`
   removes any compiled probe left by a negative control. Re-measure and record the package
   scope, findings, fixes, full command and planted-failure proof in evidence; state whether an
   assess fact predicted each finding. Record the new-class proof separately from the audit of
   existing declaration coverage. Commit that package's
   adoption, then repeat for the next one.

## Enabling NullAway selectively

For a target that wants nullness checking before triaging the other compiler checks, retain the
Error Prone integration but use this ordered plugin argument instead of its default-check list:

```
-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:OnlyNullMarked=true ${errorprone.extraArgs}
```

Keep the other compiler arguments and both processor dependencies from the slice. This is an
explicit selection of NullAway; do not apply it over a target's already accepted Error Prone gate.
Report mode remains `errorprone.failOnWarning=false` plus
`errorprone.extraArgs=-XepAllErrorsAsWarnings`; the script passes these for that run only.

`-XepDisableAllChecks` disables other Error Prone checks, not javac's own warnings. A removal
or lint warning under `failOnWarning=true` can stop compilation before NullAway runs. Clear
those javac warnings before proving the gate. If the owner explicitly chooses NullAway at
ERROR without gating javac warnings, setting `errorprone.failOnWarning=false` still leaves
NullAway errors fatal; do not assume WARN-only NullAway is enforced with that setting. The
valid-probe control compile must pass before any NullAway proof is accepted.

## References

- [NullAway configuration](https://github.com/uber/NullAway/wiki/Configuration): checker flags and Maven integration.
- [JSpecify support](https://github.com/uber/NullAway/wiki/JSpecify-Support): mode and JDK requirements.
