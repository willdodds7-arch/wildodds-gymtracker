# Phase 1A — Progression-picker bug, auto-weight on weeks 2+, exit guard

Three fixes in the session-logging loop, all additive and behind the existing patterns.

## BUG 1 — Progression picker locked / mis-targeted on weeks 2+
**Root cause (two parts):**
1. `SessionScreen` passed the **pager page index** (`currentPage`) to
   `vm.setExerciseProgressionChoice(...)`, which expects the **exercise index** in
   `state.exercises`. Once a superset merges two exercises into one page, the page index
   diverges from the exercise index, so the picker displayed one exercise's choice but wrote
   to another's log — appearing locked/unchangeable.
2. `setExerciseProgressionChoice` only persisted a value when non-null (`if (newChoice != null)`),
   so a **deselect could never be saved** — a choice couldn't be cleared.

**Fix:** pass the real exercise index (`currentPageIdxs.firstOrNull()`); always persist the new
choice — including `null` — to the **current week's** workout log. `WorkoutLogDao.updateProgressionChoice`
and `GymRepository.updateProgressionChoice` now accept `String?`.

**Test:** `ProgressionChoiceTest` — set a choice on week 2, re-edit it, clear it, and assert week 1
is never touched by any week-2 write.

## BUG 2 — Auto weight-fill only applied on week 1
**Root cause:** `updateSetWeight` propagated set 1's weight only to sets that were `isBlank() ||
isWeightAutoFilled`. On weeks 2+, later sets carry the previous week's **prefill** (non-blank,
`isPrefilled = true`), so they were skipped.

**Fix:** extracted the pure `autofillWeights(sets, firstWeight)` with explicit precedence —
**manual > carry-forward**. A new non-blank set-1 weight now overwrites blank, previously
auto-filled, **and** carried-forward prefill sets; a set the user manually edited is never
clobbered; clearing set 1 leaves carry-forward prefills intact. Still gated by `feat_weight_autofill`.

**Test:** `SessionLogicTest` — week-1 blank fill, week-3 prefill overwrite, manual-set preservation,
and clear-doesn't-nuke-prefill.

## GUARD 3 — Exit-session confirmation
`BackHandler` (system/gesture back) and the top-bar back arrow now show a Material 3 dialog
("Leave this session? Your logged sets are saved, but the session isn't marked complete." ·
Stay / Leave) **only** when the user has logged something this visit and the session isn't already
complete (`shouldGuardExit`). Tracked via `SessionViewModel.hasLoggedThisVisit` (set on any set-data
edit; reset on complete/reset-day). Registered as **"Confirm before leaving a session"**
(group Session, key `feat_session_exit_guard`, **default ON**, searchable in Settings).

**Test:** `SessionLogicTest.exitGuard_*` (precedence) + `SettingsRegistryTest` (registered, default
ON, searchable by "leave"/"exit").

## Manual test steps
**Progression picker (BUG 1)**
1. Start a multi-week program; on Week 1 complete all sets of an exercise → pick a progression
   option. Advance to **Week 2**, complete its sets.
2. Pick a progression option on Week 2, then pick a **different** one → it changes and stays
   changed. Tap the selected one again → it **deselects**. Leave and re-open Week 2 → the last
   state persisted.
3. With a program that has a **superset**, repeat on the superset page → the choice applies to the
   correct exercise (previously wrote to the wrong one).

**Weight autofill (BUG 2)** — requires Weight Auto-fill ON in Settings
1. Week 1: type a weight into set 1 → sets 2..n fill with it.
2. Reach **Week 3** of the same day (so sets carry forward from prior weeks; they show greyed
   prefill). Type a **new** weight into set 1 → sets 2..n update to the new value (previously they
   stayed on the carried-forward number).
3. Manually edit set 3 to a different number, then change set 1 again → sets 2 follows set 1, set 3
   keeps your manual value.

**Exit guard (GUARD 3)**
1. Open a session, enter at least one set value, press **system/gesture back** → confirm dialog;
   "Stay" keeps you in, "Leave" exits. The top-bar back arrow behaves the same.
2. Open a session and enter **nothing** → back exits immediately (no dialog).
3. Complete the session → back exits immediately (no dialog).
4. Settings → search "leave" → toggle **Confirm before leaving a session** OFF → back now exits
   immediately even with unsaved progress.

## Verification
`:app:testDebugUnitTest` — 23 tests, 0 failures. `:app:assembleDebug` — green.
