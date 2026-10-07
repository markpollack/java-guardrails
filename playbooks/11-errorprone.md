# Error Prone defaults at ERROR

> Status: not written. The procedure below is the outline; each numbered step will say whether it is a script, an AI task or a stop for the owner.

- AI: add `maven/errorprone.profile.xml` and `maven/jvm.config` (JDK 21+ profile).
- AI: triage every finding: bug (fix with a red-first test) or style (disable by name with a reason in `disabled-checks.txt`).
- NullAway is a separate playbook (30).
