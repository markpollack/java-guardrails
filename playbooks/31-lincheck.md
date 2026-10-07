# Lincheck for shared mutable state

> Status: not written. The procedure below is the outline; each numbered step will say whether it is a script, an AI task or a stop for the owner.

- AI: find shared mutable state behind the public API.
- Stop: owner decides whether it is worth extracting into a model-checkable class. Often no.
- AI: extract, write the sequential specification, falsify by reintroducing the bug.
