# JaCoCo floors

> Status: not written. The procedure below is the outline; each numbered step will say whether it is a script, an AI task or a stop for the owner.

- AI: add `maven/jacoco.plugin.xml`; run `verify`.
- Script: `./jbang floors` prints measured minus 2 per module.
- AI: declare the floors in each module pom; parent default stays 1.00 so a new module must declare its own.
