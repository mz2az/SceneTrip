# Operations documentation

| Document | Purpose |
| --- | --- |
| `runbooks/<service>.md` | how to operate and recover each service |
| `slo.md` | service level objectives and error budgets |
| `oncall.md` | rotation, escalation, severity definitions |
| `deployment.md` | how a change reaches production, and how to roll it back |
| `incidents/<date>-<slug>.md` | postmortems |

## Runbook requirements

Every service has a runbook before it reaches production, covering: what it does, its
dependencies, its alerts and what each one means, the first three diagnostic steps, how
to roll back, and who to escalate to.

## Postmortems

Blameless, written within 48 hours, with a timeline, contributing factors, and action
items that have owners and dates. Action items without an owner are not action items.

```bash
just tf-plan <env>            # read-only
just deploy <module> <env>    # confirm-gated
just rollback <module> <env>  # confirm-gated
```
