---
timestamp: 2026-08-14T05-30-00Z
slug: in-com-dropsync-feature-player-nowplayingscreen-kt
---
# Design Critique: Now-Playing + Library + Settings (FlowRep/DropSync)

## Design Specificity Verdict
Two-thirds template, one-third authored. Dark theme is genuinely product-specific (Poweramp-style #1F1F1F ramp, lime locked to dark grounds, Raleway with tabular figures). The Waveform canvas is the most authored artifact (played/remaining split, reflection, 240ms glide, flat-array rendering, progressBarRangeInfo semantics). Now-Playing stops at *look* (round cover, big waveform, lime button) and never delivers Poweramp's *function* (transport row, up-next, repeat/shuffle, favorites). Library home is category-interchangeable; Settings is stock M3 scaffolding contradicting the product's own beginner/pro plan.

## Heuristic Scores
| # | Heuristic | Score | Key Issue |
|---|-----------|:---:|---|
| 1 | Visibility of System Status | 3 | Analysis has no progress/%; failure silently downgrades to slider |
| 2 | Match System / Real World | 3 | "Folders" vs "Folders Hierarchy" indistinguishable |
| 3 | User Control and Freedom | 2 | No prev/next/repeat/shuffle/favorites; swipe-only skip |
| 4 | Consistency and Standards | 2 | Round cover vs rounded-square covers elsewhere; lime title breaks brand rule |
| 5 | Error Prevention | 3 | Marker confirm dialogs good; accidental swipe-track-change unguarded |
| 6 | Recognition Rather Than Recall | 2 | 5-item invisible gesture grammar (tap/drag/long-press/drag-tick/long-press-near-tick) |
| 7 | Flexibility and Efficiency | 3 | Alphabet scroller, search, sort exist; no power transport on Now-Playing |
| 8 | Aesthetic and Minimalist Design | 2 | Lime overload; Settings mega-list; empty TopAppBar |
| 9 | Error Recovery | 2 | Waveform-analysis failure silent; empty Now-Playing no CTA |
| 10 | Help and Documentation | 1 | Zero discoverability for markers, the flagship feature |
| **Total** | | **23/40** | **Acceptable** |

## Cognitive Load
- FAIL: single focus (mini-player + bottom nav visible on Now-Playing, duplicate play buttons)
- PASS (weak): chunking
- FAIL: visual hierarchy (3 lime elements compete)
- FAIL: one thing at a time (6 elements + duplicate controls)
- FAIL: minimal choices (10-chip preset, 9-12 category rows)
- FAIL: working memory (5-gesture marker grammar)
- FAIL: progressive disclosure (design doc §28 planned beginner/pro split, not shipped)

## Priority Issues
- **P0** Now-Playing not immersive: inherits shell (mini-player + bottom nav) → cover ~40-45% width, high, small; duplicate transport. Fix: chrome-less full-screen route, cover 70-75% width centered in true viewport, Poweramp transport row.
- **P0** Light theme illegible: lime on white ≈1.1:1 (title, progress, line). Fix: title/artist in onSurface hierarchy, neutral progress on light, lime only for button/dark-ground progress; extend ThemeColorSnapshotTest.
- **P1** Marker system invisible: 5-gesture grammar, TalkBack cannot seek (no setProgress action). Fix: "Markers" in overflow menu, first-run hint, semantics seek action.
- **P1** 74dp play button on seek surface (dead zone at 50%); fallback state lies (150dp band holds 48dp slider). Fix: transport below waveform; collapse zone when analysis missing.
- **P2** Settings flat mega-list (7 sections, ~15 controls, 3 decision rows 6-10 options; dB chip row clips on narrow screens). Fix: FlowRow, collapsible sections, beginner/pro split.
- **P2** Library home zero info: "Folders"/"Folders Hierarchy" same icon/tint, no track counts, no chevrons, tint reuse (Recently Added = Folders orange).

## Persona Red Flags
- **Alex:** no prev/next/repeat/shuffle/favorites on the screen he'll live in; queue editing only via mini-player; marker feature buried; Settings dumps pro tooling into consumer list.
- **Jordan:** nothing signals rows are tappable; two identical Folders rows; lime title/off-center cover/duplicate play buttons read as noise; long-press markers undiscoverable; "Verlauf" stub looks broken.
- **Casey:** cover small and high; tap-to-seek at middle hits play button; accidental long-press opens marker dialog; swipe changes song while fumbling; 12sp labels; dB chip row clipped.
- **Sam:** light mode lime-on-white unreadable (≈1.1:1); marker ticks lime = progress color in dark; TalkBack cannot seek; mini-player title tap-target is text column only; accent swatches don't announce selection.

## Minor Observations
- Marker ticks lime in dark (indistinguishable from progress), black in light.
- Mini-player cover dead tap target; only title column opens Now-Playing.
- No haptics on seek/marker-commit.
- categoryTint duplicates hues.
- Empty Now-Playing has no CTA.
- 3D carousel (-28° rotationY) can read as gimmick.
- WaveformPlaceholder 64dp in 150dp zone = unexplained band.

## Questions
1. If Poweramp is the reference, why is Now-Playing the LEAST like it (no transport, no up-next, no repeat/shuffle)?
2. Where is FlowRep in the Music tab? Big numbers, progress rings, editorial type appear nowhere.
3. Two play buttons: which is the user's?
4. If lime is reserved for actions, why is the title - not an action - the limeest element?
5. Round cover contradicts Poweramp (squares) AND this app's own list covers (rounded squares). Signature or accident?
