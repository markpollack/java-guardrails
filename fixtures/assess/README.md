# Assess fixtures

Tiny Maven projects with a known answer. CI runs `assess --quick` on each and requires every line of
`expected-quick.txt` to appear in the output, and runs `assess` (PMD measured) on `plain` against
`expected-measured.txt`. They prove the analysis the way `falsify` proves a gate: a planted case
must produce the order and the state the rules say.

| fixture | what it plants | what must read |
|---|---|---|
| plain | tests, no tooling, no concurrency | base order; PMD measured at 0 moves it ahead of the bug finders |
| no-tests | no test sources | coverage last |
| concurrent | a synchronized method | SpotBugs ahead of Error Prone |
| jacoco-reporting | JaCoCo with no check goal | JaCoCo `reporting`, still first |
| jacoco-gating-pmd-reporting | JaCoCo check; PMD with failOnViolation false | JaCoCo gating (skipped), PMD `reporting` (stays) |

Run locally from the kit root:

```
for f in fixtures/assess/*/; do ./jbang scripts/Assess.java "$f" --quick > /tmp/a.out; while read -r l; do grep -qF -- "$l" /tmp/a.out || echo "MISSING in $f: $l"; done < "$f/expected-quick.txt"; done
```
