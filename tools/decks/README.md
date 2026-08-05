# Deck generation

The five decks in `docs/decks/` are generated, not hand-edited. Editing a `.pptx`
directly means the next regeneration silently discards your change — edit the
script instead.

## Regenerate

```bash
python3 -m venv .venv && ./.venv/bin/pip install python-pptx
./.venv/bin/python tools/decks/build_blueprint.py    # Product Blueprint   25 slides
./.venv/bin/python tools/decks/build_plan.py         # Implementation Plan 14 slides
./.venv/bin/python tools/decks/build_deck.py         # Team Plan           16 slides
./.venv/bin/python tools/decks/build_deps.py         # Dependencies        11 slides
```

Each writes into `docs/decks/`, overwriting the existing file.

## Layout

`deck_lib.py` holds the shared visual system — the blueprint §12.1 colour
tokens, the stream accents (A indigo · B cyan · C violet · D teal), and helpers
for slides, cards, tables, bullets and code blocks. All four build scripts
import it, which is what keeps the decks looking like one set.

Change `deck_lib.py` and every deck changes together. Change a build script and
only that deck does.

## Verifying

There is no automated visual check. After regenerating, open the file — the
structural check below catches shapes that fall off the slide, but not text
overflowing inside its own box:

```python
from pptx import Presentation
p = Presentation("docs/decks/EduTrack-Dependencies.pptx")
W, H = p.slide_width, p.slide_height
for i, s in enumerate(p.slides, 1):
    for sh in s.shapes:
        if sh.left is None or (sh.width == W and sh.height == H):
            continue
        if sh.left + sh.width > W or sh.top + sh.height > H:
            print(f"slide {i}: shape overflows")
```
