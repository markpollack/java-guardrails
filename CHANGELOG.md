# Changelog

## 0.4.0 — Unreleased

- Default to an unattended local first pass; retain `GUARDRAILS_MODE: control` for policy checkpoints. Both modes preserve published APIs, reject introduced findings and halt on incomplete measurement.
- Compare original public/protected API artifacts with pinned japicmp, including external ancestry; fully remeasure each hotspot with rule-and-declaration multiset attribution.
- Preserve consumer dependency closures from actual publication POMs; reject unrecorded version changes, including changes caused solely by Maven dependency ordering. List exact reasoned exceptions in the final report.
- Keep run evidence locally ignored; preserve the original start across same-version resume and produce a maintainer-facing review report.
- Honor test-support scope in assessment costs, require own-module tests and positive coverage floors, and keep inherited compiler exceptions visible in scratch measurement.
- Package sibling test-jar contracts before coverage, bytecode analysis and source-gate plants; preserve original diagnostics and allow a failed baseline to retry from the immutable start.

## 0.3.3 — 2026-10-30

- Handle nonempty single-package modules in ArchUnit measurement and copied gates. Include root packages in package-cycle checks, retain class-cycle checks and the nonempty-import assertion, and fail measurement on missing or empty compiled classes.
- Falsify single-package ArchUnit gates by planting a class cycle in the sole package; add real Maven regressions with disabled-rule and empty-import controls.
- Align `npx skills` install and refresh instructions by scope, recommending `-g` for all-projects use and repeating `add` for the same scope and agents.
- Date the 0.3.2 changelog entry on the default branch and require a valid dated heading for the version being tagged before release publication.

## 0.3.2 — 2026-10-29

- Account for every selected JaCoCo module using compiled bytecode: executable code without a report stops before any floor proposal or validation; non-executable modules are named and need no floor.
- Ask test-support scope in assessment before the first gate, and in direct JaCoCo measurement before building. Gated test support requires its own report; consumer-only execution is not aggregated.
- Detect incomplete recorded coverage floors, preserve existing values and request a completion decision.
- Add real Maven regressions for no tests, opt-in IT only, test support executed by consumers, abstract-only interfaces and default methods, exclusions, saved partial floors and compiler failures.

## 0.3.1 — 2026-10-28

- Build scoped Maven commands with working-tree reactor dependencies, including a compile before source-check goals that resolve sibling artifacts.
- Use one shared Maven policy to isolate each requested checker; compiler checkers are active only for Error Prone and NullAway. A target's disabled gate stays disabled so falsification can reject it.
- Discover Maven source modules through aggregator declarations; ask Maven for the effective reactor when profiles or interpolated module paths require it. Standalone nested projects stay outside the reactor scope.
- Add `EXCLUDED_MODULES` independently of the test-support decision, retaining legacy exclusions without letting unrelated exclusions suppress that decision.
- Show Maven failure output when a measurement fails before tests; handle empty selections without running the reactor.

## 0.3.0 — 2026-10-28

- NullAway package inventory, report-mode measurement, planted-violation proof and adoption playbook.
- Optional NullAway routing from assess; explicit package scope, independent of cleanup of other Error Prone checks.
- NullAway proof compares a green valid control with a red null-return probe; package-default proof explicitly excludes auditing existing declaration opt-outs.
- Preserve same-line diagnostic columns, separate upstream reactor findings, handle ANSI output, and refuse hidden report diagnostics.
- Every falsify gate restores its planted sources and stops the build on Ctrl-C or SIGTERM, preserves edits and replacement files, refuses symbolic-link sources, and refuses to overwrite an existing file at a plant's path.
- Separate the PMD ratchet from CPD's zero-duplication allowance in both verify and direct goals; document migration of previously copied plugin configurations.

## 0.2.0 — 2026-10-27

See `.github/release-notes/0.2.0.md`.

## 0.1.0

See `.github/release-notes/0.1.0.md`.
