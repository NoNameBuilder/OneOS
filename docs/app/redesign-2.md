# App redesign 2: the shared look (full edition)

The building blocks for the redesigned screens. The spec is the mockup
(`oneos-notes/2026-10-24/design-canvas/project/*.dc.html`, 1 CSS px = 1 dp).
Everything is in `app-android/app/src/full`, so OneOS Probe keeps the stock One
UI look. Dark mode comes from `values-night`. `OneUITheme` switches to its dark
parent by itself.

Rules for screens: put cards on the window colour (`OneUITheme` already sets it).
Never use MaterialCardView or Material buttons, because they crash under
`OneUITheme`. Fixed text never names a system: the name comes from
`ImageRef.name`. Icon-only buttons get a `contentDescription`, and decorative
images get `importantForAccessibility="no"`.

| Name | Kind | Use |
|---|---|---|
| `Theme.OneOS` | theme (`values/themes.xml`) | Replaces main's theme for this flavor. `colorPrimary` = `oneos_primary`; `colorPrimaryDark` and `colorControlActivated` = `oneos_primary_text` |
| `ThemeOverlay.OneOS.Dialog`, `ThemeOverlay.OneOS.Dialog.Alert` | theme overlays (light, plus dark in `values-night`) | Set as `dialogTheme` and `alertDialogTheme`, so AlertDialogs keep OneOS blue (SESL's overlays reset it) |
| `oneos_blue` #1C5FD6, `oneos_purple` #5B3FCB | colour, the same in both modes | Brand. Purple is only for the guest side of the hero picture |
| `oneos_primary` (#1C5FD6, dark #2F6FE0) | colour | Filled button under white text (5.7:1 light, 4.7:1 dark) |
| `oneos_primary_text` (#1C5FD6, dark #6E9BFF) | colour | Blue text, links, overline, switches: 5.3:1 or more on every surface |
| `oneos_text_secondary` (#5E5E5E, dark #A6A6A6) | colour | Secondary text: 5.9:1 or more on window, card and tile. SESL's grey is 3.3:1 |
| `oneos_icon` (#3D3D3D, dark #D0D0D0) | colour | Line icons in rows |
| `oneos_tile_bg` (#F2F5FB, dark #23272F), `oneos_tile_bg_warn`, `oneos_tile_bg_block` | colour | Check tile fill per level |
| `oneos_ok`, `oneos_warn`, `oneos_block` (= `oui_functional_*`), `oneos_info`, `oneos_on_status` | colour | Status fills (badges) and the glyph drawn on them |
| `oneos_ok_text`, `oneos_warn_text`, `oneos_block_text` | colour | Status text and icons on cards and tiles, 4.6:1 or more. The light functional colours are only about 3:1 |
| `oneos_blue_on_black` #8FB4FF | colour | "OS" of the splash wordmark |
| `oneos_card_radius` 26, `oneos_tile_radius` 16, `oneos_button_height` 52, `oneos_screen_gutter` 24, `oneos_card_inset` 10, `oneos_card_gap` 12, `oneos_tile_gap` 8, `oneos_row_min_height` 56 (dp) | dimens | Mockup spacing |
| `OneOS.Card` / `OneOS.Card.List` | style | 26dp card on `?android:colorBackground`, clipped to its corners. `.List` drops the side padding, for rows |
| `OneOS.Tile` | style | 16dp check tile, 12dp padding |
| `OneOS.Row` | style | Whole-row tap target, 56dp or more, ripple |
| `OneOS.Button.Primary` | style | 52dp pill in `?attr/colorPrimary`, white bold 17sp. Use on `<Button>` |
| `OneOS.Button.Secondary` | style | The same pill in grey, with primary text |
| `OneOS.Button.Text` | style | Bold 15sp blue text button, 48dp tall |
| `OneOS.Text.Hero` (32sp light), `.Hero.Large` (36sp) | text style | The system name under the hero picture |
| `OneOS.Text.Overline` | text style | 13sp medium in blue ("OneOS" above the hero) |
| `OneOS.Text.Title` (15sp medium), `.Title.Tile` (14sp) | text style | Card headers; tile names |
| `OneOS.Text.Subtitle` (15sp), `OneOS.Text.Body` (16sp), `OneOS.Text.Caption` (13sp), `.Caption.Strong` | text style | The line under the hero; row titles; summaries and notes; a header's trailing status. All are textAppearance-safe |
| `ic_oneos_hero` | drawable 236x132 | One UI ⇄ guest two-device picture. Show it at 236x132dp on the cover and 284x158dp on the inner screen. Decorative |
| `ic_oneos_mark` | drawable 108 | The launcher mark (two phones on a blue rounded square), for the splash |
| `oneos_badge_ok` / `_warn` / `_block` / `_info` | drawable 24 | Filled status badges |
| `ic_oneos_check_root` / `_space` / `_battery` / `_lock` | drawable 24 | Check tile line icons from the mockup, tinted by level |
| `ic_oneos_row_last_time` / `_systems` / `_advanced` | drawable 24 | Row line icons from the mockup |
| `oneos_card_bg`, `oneos_tile_bg`, `oneos_button_primary_bg`, `oneos_button_secondary_bg` | drawable | Backgrounds behind the styles above |
| `oneos_check_tile.xml` | layout | `oneos_tile_icon`, `oneos_tile_title`, `oneos_tile_value`. TalkBack reads it as one item |
| `oneos_row.xml` | layout | `oneos_row_icon`, `oneos_row_title`, `oneos_row_summary` (gone when empty), `oneos_row_end` (badge or chevron) |
| `oneos_card_section.xml` | layout | A card with `oneos_section_header`, `oneos_section_title` (a heading for accessibility) and `oneos_section_trailing`. Content is added below the header |
| `Cards.section(parent, title, trailing?, trailingLevel, rows = false)` → card | Kotlin (`ui/Cards.kt`) | Adds a card. A null title and trailing hide the header. `rows = true` makes a list card. Adds the 12dp gap above it when the parent already has children |
| `Cards.bindSection(card, title, trailing, level)` | Kotlin | Updates the header |
| `Cards.tileGrid(parent, tiles, columns = 2)` → grid; `Cards.setTiles(grid, tiles)` | Kotlin | Builds a grid of `Cards.Tile(icon, title, value, level)` with 8dp gaps and equal heights in each line. `setTiles` refills it |
| `Cards.checkTile(parent, tile)`, `Cards.bindCheckTile(view, tile)` | Kotlin | Builds or updates one tile. The icon and the tile are tinted by `Level` |
| `Cards.row(parent, icon, title, summary?, onClick?)`, `Cards.bindRow(...)`, `Cards.setRowEnd(row, drawable)` | Kotlin | Builds a row. A null `onClick` makes it a plain row, and icon 0 hides the icon |
| `Cards.levelColor(ctx, level)`, `Cards.tileColor(ctx, level)`, `Cards.badge(level)`, `Cards.worst(levels)` | Kotlin | Level → text/icon colour, tile fill and badge, plus the worst level of a set |
| `oneos_cards_screen.xml`, `CardsFragment` (`ui/CardScreen.kt`) | layout, Kotlin | A card screen behind Advanced: one scrolling column under the screen's collapsing bar. `build(column)` adds the cards, `redraw()` builds them again, `details(card, summary) { … }` is the shared Details row (open from For the OneOS team) |
| `CardRows.text`, `.row(…, enabled)`, `.status(card, level, …)`, `.finding`, `.tint`, `.tick`, `.toggle`, `.stats`, `.storageBar`, `.expandable` | Kotlin | A paragraph, a row that can be off, a row with a status badge, a gate's finding as it is, a row icon in a level's colour, a checkbox or switch row (the whole row is the tap target; TalkBack reads it as the box), value tiles (`home_stat_tile`), the storage bar (`oneos_storage_bar.xml`), a chevron row |

Example (Home cover, "Your phone is ready" card plus the More card):

```kotlin
val ready = Cards.section(column, getString(R.string.home_ready_title), "4 of 4", Cards.worst(levels))
Cards.tileGrid(ready, listOf(Cards.Tile(R.drawable.ic_oneos_check_root, rootTitle, rootValue, rootLevel) /* ... */))
val more = Cards.section(column, title = null, rows = true)
Cards.row(more, R.drawable.ic_oneos_row_advanced, advancedTitle, advancedSummary) { openAdvanced() }
```

The string names in the example are placeholders; the screens add their own.

## Advanced and the screens it opens

`strings_advanced.xml` holds their texts (StringsNeutralTest reads all of
it). Every row is named like the screen it opens (the row uses the screen's
own title string):

| Card | Row | Screen |
|---|---|---|
| Your system | Set up again | `PrepareActivity` (checks, keep or start fresh, Set up again; the progress opens Setting up) |
| | Use a downloaded file | `ImageActivity` (pick, check, the system already on this phone, the systems for this phone) |
| | Free up space | `OldStagesActivity` (storage bar, the system set up now, old copies and what deleting them frees) |
| Sessions | Last session | `LastSessionActivity` (how it went first, then the logs, then Restore One UI's settings) |
| | Session logs (once collected) | `FindingsActivity` with the log zip |
| | Restore One UI's settings (only when offered) | the same confirm as Last session (`SessionRestore`) |
| Help | Before you start, About OneOS | `BeforeYouStartActivity`, `AboutActivity` |
| (one entry) | For the OneOS team | `TeamActivity`: All checks and Test run (`SwitchActivity`), Phone details, Run tests, Setup details (`PrepareActivity` with `EXTRA_TEAM`: Details open, the nine steps on the page), File details and checks (`ImageActivity` with `EXTRA_TEAM`) |

The technical words (paths, SHA-256, installd, the stage folder, root's
reasons, session ids) are only under Details and on the team's screens.

