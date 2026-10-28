# Changelog

## 0.3.1 — Unreleased

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
