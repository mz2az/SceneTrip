# Project documentation

How the work is organized and what has been decided.

| Document | Purpose |
| --- | --- |
| `plans/<feature>.md` | implementation plans written before the code |
| `status.md` | current state: in flight, blocked, next |
| `decision-log.md` | lightweight decisions that do not warrant an ADR |
| `retrospectives/<date>.md` | what we learned |

## Why plans are files

Any change spanning more than one module, altering a contract, adding a dependency, or
exceeding roughly 100 lines gets a written plan here first. A plan in the repository can
be reviewed, revised, and picked up by a different contributor — human or AI — weeks
later. A plan in a chat window cannot.

Decisions with lasting architectural consequences graduate to an
[ADR](../architecture/adr/README.md).
