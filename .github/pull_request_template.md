## What changed

<!-- One paragraph. What does this do that the repo could not do before? -->

## Why

<!-- The problem, or a link to the PRD / issue / ADR. -->

## Blast radius

<!-- Which modules and environments are affected? `just rdeps <target>` if unsure. -->

## Verification

- [ ] `just check` passes locally
- [ ] Tests added or updated in the correct lane
- [ ] `just gen` produces no diff
- [ ] Contracts updated before the implementation (if the wire format changed)
- [ ] Module README and affected docs updated
- [ ] No secrets, absolute paths, or debug output

## Rollback

<!-- How to undo this if it goes wrong in production. -->
