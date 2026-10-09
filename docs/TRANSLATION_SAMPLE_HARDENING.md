# Translation reliability follow-up — 8 October 2026

Base: `446857e50dae51641e85ee1dde87b2284ecb9fc1`, PR #12. This is 48
commits ahead of the mature production baseline; legacy main is not the base.

The supplied English/Hindi page exposed untranslated `BEATEN UP` and a lost
anger idiom. Existing normalization already addresses the idioms, but literal
spaces prevented matches across OCR line breaks. Whitespace is now normalized
before those rules. Short OCR fragments receive reliable script/English-dialogue hints;
Han-only text still uses language identification rather than assuming Chinese.

The quality policy previously checked only refinement, then accepted any draft
or even the source. It now validates both candidates, rejects copied English
phrases in Devanagari output, and applies the same check to translation memory.
Invalid output cannot be stored or painted over the source. Successful bubbles
publish progressively; rejected bubbles remain original and flag the page for
retry. This is a script/leakage gate, not a semantic translation evaluator.

The adjacent OCR word helper now preserves both words and all bounds without
mutating input. Two-pass rendering releases each reconstruction bitmap after
painting its background and retains only text placement metadata. Renderer
failure recycles its owned output. Existing reader, library, navigation, media,
download and Orez systems are preserved.

Regression coverage includes OCR-wrapped idioms, invalid drafts, cached source
leakage, short fragments, excessive draft length, punctuation, preserved names,
and adjacent-word geometry. Android merge tests require device execution;
compiling AndroidTest is not runtime evidence.

Device acceptance: import the supplied original English page, translate to Hindi,
check that `BEATEN UP` becomes Hindi within the same bubble, verify the anger
meaning manually, confirm no residual source glyphs or clipped Devanagari, retry
a deliberately invalid result, and navigate/scroll during a long chapter. Repeat
after restarting the app. Translation durability across process death remains
outstanding; these changes do not claim to implement the full Orez blueprint.
