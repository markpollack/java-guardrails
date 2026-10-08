# Assess the target

> Status: `assess` implemented 2026-10-08; proven on the MCP Java SDK.

1. **Script** — `./jbang assess <target>` (from elsewhere: `<kit>/jbang <kit>/scripts/Assess.java <target>`).
   Reads files only. Prints the build tool and whether its wrapper is present, the Java level, every
   module with main sources and its test-support signals, the root packages, the quality tooling
   already in the build, which of the kit's gates are installed on this target, the decisions
   recorded so far, and the next step.
2. **AI** — read it. A missing Maven wrapper is added before anything else. A PMD plugin already in
   the build is measured before its configuration is replaced. Gradle targets can be measured but
   not gated yet.
3. The next step it names is the first step of the next gate's playbook; the owner's decisions are
   asked there, by that gate's script, not here.
