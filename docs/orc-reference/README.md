# orc reference docs (upstream snapshots — do not edit)

Verbatim copies of selected docs from the **orc** repo, pinned to the same SHA
this project depends on:

> orc `83c315ad53449cb3e8ceba6a8b824291cf4378ae`
> (`https://github.com/ObneyAI/orc`)

Kept here so we have orc's own words at the exact version on our classpath, even
offline. **Treat these as read-only** — write our own learnings into
[`../orc-guide.md`](../orc-guide.md) instead, and refresh these if we bump the
orc SHA in `deps.edn`.

| File | Upstream path |
|------|---------------|
| `orc-README.md` | `README.md` |
| `RLM-GUIDE.md` | `docs/RLM-GUIDE.md` |
| `pattern-compendium.md` | `docs/pattern-compendium.md` |
| `ARCHITECTURE.md` | `docs/ARCHITECTURE.md` |

To refresh after bumping the SHA:
```bash
GL=~/.gitlibs/libs/obneyai/orc/<new-sha>
cp "$GL/README.md"                docs/orc-reference/orc-README.md
cp "$GL/docs/RLM-GUIDE.md"        docs/orc-reference/RLM-GUIDE.md
cp "$GL/docs/pattern-compendium.md" docs/orc-reference/pattern-compendium.md
cp "$GL/docs/ARCHITECTURE.md"     docs/orc-reference/ARCHITECTURE.md
```
