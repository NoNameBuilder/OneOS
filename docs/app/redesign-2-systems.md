# App redesign 2: Choose a system and Setting up (full edition)

The spec is `SystemPicker.dc.html` and `SettingUp.dc.html` in the mockup
(`oneos-notes/2026-10-24/design-canvas/project/`). The shared look is in
`redesign-2.md`. Both screens use a plain One UI bar (back button, no
expanded title); the big title is in the content and shows in the bar once
it has scrolled away.

## Choose a system (`ui/SystemsActivity.kt`)

Opened by `FixActions` for Choose a system, Choose another file, Set up
again, Download again without a link, and a Set up or Update with no usable
download. One card per `profile.images()`; two cards side by side when the
screen is 600dp or wider.

| Card part | Source |
|---|---|
| Device glyph | `ic_oneos_system_0..2`, by the system's place in the phone's list (no brand mark) |
| Name, variant, blurb | `ImageRef.name`, `.variant`, `.blurb` |
| Chips | Android release (`sdk`), tested on this phone (`ImageChoice.tested`), "Google apps" (`googleServices`), download size (`gzBytes`) |
| Badge | "Setting up" (PrepareJob runs for it), "Set up" (the stage marker names it; the card gets a blue ring), "File picked" (`FixActions.pickedImage`) |

| State | Buttons |
|---|---|
| Setting up | Show progress |
| Set up | Use this one (back to Home, which offers the switch) |
| File picked | Set up {system}: `FixActions` SET_UP, the same confirm as Home's; off while a job or the check runs |
| Otherwise | Download (opens `downloadUrl` in the browser; hidden when null) and I have the file |

"I have the file" runs the OS image page's picker and size identity check
(`SessionPrefs.setPicked`, source = the file). After a good pick with nothing
set up, the screen goes back to Home, whose button is then "Set up
{system}"; with a system set up, the card shows "Set up {system}". A bad pick
shows the plain message (`Plain.of`) with its own button (choose another
file, or download again). The SHA-256 is still checked by the setup's "Check
the download", or by Check file on the OS image page, which stays reachable
from the last row and from Advanced.

## Setting up (`ui/SetupProgressActivity.kt`)

Opened by Home's Show progress and by every setup `FixActions` starts.

- `SetupRingView`: a 196dp ring, 12dp stroke, from `PrepareJob.State.percent`;
  centre text: percent and time left (`etaSeconds`). Orange when stopped,
  red when it failed.
- Title "Setting up {system}" (the picked download's name in the first
  seconds), then the three `PrepareJob.UserStep`s with done, current,
  pending, stopped or failed icons. The current step shows the bytes hashed
  ("1.2 GB of 2.5 GB") or the job's unpack detail ("2.0 GB of 4.7 GB").
- "Keep OneOS open and your phone plugged in" while it runs; the screen
  stays on while it runs.
- Footer: Stop (as before: `PrepareJob.cancel()`, no confirm); then Done, or
  the plain message's fix button and Close.
- Details: the nine `PrepareJob.Step`s, the job's message, the check that
  stopped it and what was moved aside. The Prepare page stays in Advanced.

`StringsNeutralSystemsTest` checks that these screens' strings, Kotlin and
layouts never name a system.
