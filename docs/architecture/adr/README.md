# Architecture Decision Records

One file per decision, numbered sequentially, named `NNNN-kebab-title.md`.

```bash
just adr-new "adopt bazel remote cache"
just adr-list
```

## Rules

- ADRs are **append-only**. To change a decision, write a new ADR that supersedes the old
  one and update the old one's `status` and `superseded-by`. Never rewrite history.
- Status: `proposed` → `accepted` → (`superseded` | `deprecated`).
- Record the alternatives you rejected and why. That is the part future readers need.
- Include a verification section: how you will know the decision was right.

`template.md` is the starting point rendered by `just adr-new`.
