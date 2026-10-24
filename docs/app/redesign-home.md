# App redesign: Home, the Switch sheet and Welcome back

The spec is the mockup: `Main.dc.html`, `HomeInner.dc.html`, `SwitchSheet.dc.html` and `WelcomeBack.dc.html` in `oneos-notes/2026-10-24/design-canvas`. These screens use the shared pieces in `redesign-2.md`. Everything here is in `app-android/app/src/full`, so OneOS Probe keeps its old Home.

## Layout

- **Cover** (`layout/activity_home.xml`, `home_content.xml`, `home_hero.xml`)
  - The bar is a plain ToolbarLayout with no title, because the hero says "OneOS". The menu stays in the bar.
  - Below the bar is one scrolling column: the hero, then the cards in `home_cards`.
  - The footer is pinned. It holds the main button, the line under it, and a text button (Done or Details).
- **Inner screen** (`layout-w600dp-h480dp/`; `R.bool.home_two_pane`)
  - The bar is titled "OneOS".
  - The left pane is a card with the hero and the button.
  - The right pane holds the cards.
- **Cards**, made once in `StatusHomeFragment.onViewCreated` and shown or hidden per state:
  - checks: root, space, battery and screen lock tiles
  - setup progress
  - Welcome back's session
  - rows: Last time, Systems, Advanced, or Session details and Send report

## States (`HomeState.Kind`)

Each text is said once. When a system is in play, the problem title is the hero's line. When none is, the problem title is the headline. The problem's line goes under the button. The technical finding is behind Details.

| Kind | Headline / line | Cards | Button / under it / text button |
|---|---|---|---|
| CHECKING | system, or "Another system next to One UI" / "Checking your phone…" (loader) | rows | disabled "Choose a system" |
| RESTORE, NO_ROOT, CHECK_FAILED, UNSUPPORTED, OLD_SWITCHER, RESTART, UPDATE | problem title / problem line | rows | the fix, if any / – / Details |
| RESTORING | "Restoring One UI's settings…" / "This takes a moment…" (loader) | rows | – |
| SETTING_UP | system / "Setting up · 42%" | progress (bar, time left, keep open), rows | Show progress |
| SWITCHING | system / "Getting ready to switch…", then "One UI is closing…" (loader) | rows | – |
| NOTHING_SET_UP | "Another system next to One UI" / "Setup takes about 15 minutes". With a file picked: the system / "Downloaded · ready to set up" | **checks**, rows (no Systems row while the button is Choose a system) | Choose a system, or Set up X / "About 15 minutes · One UI keeps working" |
| FIX | system / problem title | checks, rows | the fix / problem line / Details |
| WELCOME_BACK | badge, "Welcome back" / "One UI is as you left it" or what happened | session (time in X, how you came back, logs line), Session details (+ Send report after a bad outcome) | Switch again / – / Done |
| READY | system / "Ready to switch · Android 17" (inner screen: + "Your apps and data in it are kept") | checks, rows | Switch to X / "About a minute · nothing is erased" |

### Check tiles

- Each tile is coloured by its gate in the SWITCH report: root, space, battery and screen lock.
- Tapping a tile runs the fix from the gate's plain message. When there is no fix, it opens Details (the technical finding, with All checks).
- When a file is picked before setup and free space is below what that system needs, the space tile is WARN. This is a hint only; Prepare's own gate still decides.
- While a check runs, the card header says "Checking…" with the One UI loader instead of "4 of 4 checks".

## Cold start: the last known Home

- `HomeState.snapshot()` keeps the Home of a finished check with no job running: RESTORE, UNSUPPORTED, OLD_SWITCHER, RESTART, NOTHING_SET_UP, UPDATE, FIX, WELCOME_BACK or READY. It keeps the tiles as text.
- `Readiness.keepHome` writes it to `SessionPrefs` (`home_snapshot`, format `HomeSnapshot.encode`) only when it changed.
- On a cold start, until the first check of that start ends, `Readiness.coldStartHome` passes it as `HomeInput.cached`. `HomeState.decide` then shows that Home instead of CHECKING, with these rules:
  - **The button is off, whatever it does.** This includes actions that need no root.
  - There is no finding, so there is no Details.
  - The tiles cannot be tapped.
  - "Checking…" shows on the tiles or the hero.
- A job, a stopped check, an unfinished session or the check's result always wins over the kept Home. Welcome back is decided again from the last session.
- `HomeStateTest` (`aColdStartShowsTheLastHomeButNeverActsOnIt`, `onlyASettledHomeIsKept`, `aKeptHomeSurvivesItsTextForm`) holds this rule.

## Switch sheet (`SwitchConfirm`, `dialog_switch.xml`)

- A One UI AlertDialog with only a custom view, which is SESL's inset bottom card. It contains:
  - the title and one line, plus the 2G/3G calls note and the hidden-Google-apps note when they apply
  - "Three ways back to One UI" as tiles: Back to One UI in X (only when the set `hasReturnApp`; the header then says "Two ways back"), Volume Up + Volume Down, and Power + Volume Down when stuck on the logo
  - one One UI check box per acknowledgement not ticked before
  - Cancel and Switch
- The logic has not changed: Switch is on only when every box is ticked, a check that is running keeps the dialog open, and `LaunchJob` checks everything again.
