# Notation

Everything `LineGrammar` reads from one written exercise line. Every row has a named test in
`LineGrammarTest` or `SetNotationParserTest`, and most have a corpus document under
`reader/corpus/notation/`.

The 15 tests that pinned the previous parser are kept **verbatim** as a conformance suite: the
rewrite had to prove it read everything the old one read before it was allowed to read anything
more.

---

## Sets and reps

| Written | Read as |
|---|---|
| `3x8`, `3X8`, `3*8`, `3×8` | 3 sets of 8 |
| `3 sets of 8`, `3 sets x 8 reps` | 3 sets of 8 |
| `3x8-12` | 3 sets, 8–12 |
| `8 reps`, `8-12 reps` | reps stated, **set count null** |
| `3 sets to failure` | 3 sets, no rep count, `to_failure` |
| `50 total reps` | 50 reps, `total_reps` |
| `AMRAP` | as many as possible — **no invented set count** |

## Per-set schemes

| Written | Read as |
|---|---|
| `5/3/1` | 3 sets: 5, 3, 1 |
| `6, 8, 10` | 3 sets: 6, 8, 10 (a comma pair is *not* a list — that was a real bug) |
| `12/10/8/6` | pyramid |
| `21-15-9` | for-time triple (a two-number dash is still a rep *range*) |
| `1,2,3,4,5 x 5 rounds` | ladder, 25 sets |
| `1x8+2+2` | rest-pause: 8, 2, 2 — total 12 |
| `3x(3.3.3) rest 15s` | cluster: 3 sets of 3+3+3 |
| `3x10 drop set` | drop set |
| `1x15 myo reps` | myo-reps |

## 5/3/1 dialects

| Written | Read as |
|---|---|
| `1x5+ @85% TM` | final set AMRAP, min 5, % of training max |
| `65/75/85%` | three per-set loads |
| `70/80/90% TM` | three per-set **training-max** loads |
| `FSL 5x5`, `BBB 5x10 @ 50%` | modifier recorded, prescription read |

**One TM per line governs every percentage on it.** Coaches write `65%, 75%, 85% TM`, never
`65% TM, 75% TM, 85% TM`. Getting this wrong makes every 5/3/1 import wrong by the ratio of training
max to true max. Named test.

## Intervals

| Written | Read as |
|---|---|
| `EMOM 12`, `E2MOM 16`, `OTM 10` | every-minute work, 12 / 16 / 10 |
| `AMRAP 12 min` | timed AMRAP, 720 s |
| `for time` | modifier |

## Load

| Written | Read as |
|---|---|
| `@100kg`, `@ 225lb`, `@ 225#`, `@ 100 kilos` | absolute, with unit |
| `@ 100` | absolute, **unit unknown** — review asks, never assumes kg |
| `@ 60-70kg` | absolute range |
| `@75%` | % of 1RM |
| `@80% TM`, `@ 85% e1RM`, `@ 20% BW` | % of training max / estimated 1RM / bodyweight |
| `@ 70-80%` | percentage range |
| `@RPE8`, `@RPE 7-8` | RPE, and RPE range |
| `2 RIR`, `RIR 2` | reps in reserve (an RPE on the same line wins — it is more specific) |
| `BW+25kg` | bodyweight plus |
| `BW-20kg`, `assisted` | bodyweight minus |
| `+ red band`, `+ 20kg chain` | band / chain, label kept |

## Rest

| Written | Read as |
|---|---|
| `rest 3 min`, `rest 90s` | fixed |
| `rest 2-3 min`, `rest 90-120s` | range |
| `rest 1:30` | 90 s |
| `rest as needed`, `walk back rest` | as-needed — not a number |
| `no rest`, `superset` | superset-none |

## Time, distance, calories

| Written | Read as |
|---|---|
| `3x45s`, `3 x 2:00` | duration |
| `60s each side` | duration + `each_side` |
| `5x400m`, `2000m`, `5km` | distance |
| `3x2m` | **two minutes** — a bare `m` under 15 is minutes; 5–30 is flagged ambiguous |
| `15 cal` | calories |

## Tempo

| Written | Read as |
|---|---|
| `31X0` | tempo |
| `3-1-2-0` | tempo |
| `tempo 3010` | tempo — a bare four-digit number is **not** one on its own |
| `paused 2s` | `paused` modifier, not a tempo |
| `10x8` | **ten sets of eight.** Only an uppercase `X` makes a tempo. Named test. |

## Unilateral and qualifiers

`each side`, `per leg`, `e/s`, `ea`, `alternating`, `paused`, `cluster`, `drop set`, `rest-pause`,
`myo reps`, `to failure`, `total reps`, `band`, `chain`, `optional`, `if time` — all canonicalised
into `Exercise.modifiers` and kept out of the exercise name.

## Supersets

| Written | Read as |
|---|---|
| `A1) Bench Press 3x10` | group A, order 1 |
| `B2. Cable Row 3x12` | group B, order 2 |
| `C - Face Pull 3x15` | group C, **no order** (the source did not number it) |

## What the parser will not do

`Some Weird Conditioning Finisher` → the movement name is kept, the prescription is `UNKNOWN`, the
set count is `null`, the verbatim line is preserved, and the slot is flagged. **Nothing is invented.**

Coverage — the fraction of a line's characters no token consumed — is recorded on every slot. Below
70%, the import raises a `REVIEW` flag naming the line. That one metric catches more silent misreads
than anything else in the subsystem.
