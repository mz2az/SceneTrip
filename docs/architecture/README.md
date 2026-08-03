# Architecture documentation

How the system is put together and why.

| Document | Purpose |
| --- | --- |
| `system-overview.md` | the whole system on one page, with a context diagram |
| `service-map.md` | every service, its responsibility, and its dependencies |
| `data-model.md` | ownership boundaries — which service owns which data |
| `tech-stack.md` | chosen technologies and the reasoning |
| [`adr/`](adr/) | decision records |

## Rules

- Diagrams are Mermaid, in Markdown. Binary image exports are not reviewable.
- Every service appears in `service-map.md` — an undocumented service does not exist.
- Data ownership is explicit: exactly one service owns each table or collection.
- A decision with lasting consequences gets an ADR, not a paragraph buried in a page.
