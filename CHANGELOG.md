# Changelog

What changed in each Glassio Yahsun release, newest first. `tools/release.sh` publishes a build with its section here as the release notes (the in-app update banner shows them), and it stops if the version has no section: every release gets one.

## 2.1.0 — 2026-10-26

The first release of Glassio Yahsun: it also brings everything in 2.0.0 (Live TV, Following, Continue Watching in its own row; see [CHANGELOG.md](https://github.com/GizmoH2o/glassio-yahsun/blob/main/CHANGELOG.md)).

**Live TV on the home screen**
- **Live TV in Continue Watching**: the channel you watched last leads the row, with what's on it now (its TMDB picture, the channel logo, a red LIVE tag and how far into the show). OK opens Live TV on it.
- **Followed matches in Continue Watching**: while a team you follow is playing, its match comes first in the row, with the score. OK goes straight to the channel.
- **Recordings in Continue Watching**: your three newest finished recordings (from the last 14 days). OK plays one in Live TV. Hold OK on any Live TV card to take it out of the row.
- **Watch on Home** (Live TV's menu): the channel keeps playing in a small glass window under the weather while you browse the home screen. Back closes it; opening Live TV again goes on full screen. The screensaver waits while it plays.

**Live TV**
- Menus and dialogs in Glassio's style are Glassio's dark glass with its light edge and the white focus (the other styles — Apple TV, YouTube TV, NOW, Sky, Sling, Hulu, Custom — keep their own look).
- Typing (signing in, the guide's link, Multiview's search) uses a Glassio card: a frosted field, a white caret, OK and Cancel on the white plate, kept above the TV keyboard. Passwords stay hidden as you type.
- The sign-in screen's rows are glass, like the rest of Live TV.
- Fixed: adding a playlist from Settings went to a black screen instead of the sign-in screen.
- Removed the "What's new in Live TV" card (Free My TV's own notes).

**Updates**
- Updates now come from this repository's releases (github.com/GizmoH2o/glassio-yahsun).

## 2.0.0 — Glassio Yahsun

The first version under the new name (never published on its own; 2.1.0 is the first release).

- **Live TV**: an IPTV player for Xtream Codes, M3U links and MAG portals, with a full guide, favorites, pause and rewind, catch-up, recordings, Multiview, profiles (with a Kids one), reminders and live scores, in its own process. Its default look is Glassio's own. Ported from [Zero](https://github.com/GizmoH2o/zero-tv) (MIT).
- **Live TV page** and **Following** in the menu: follow a team or a show; its next match shows in a spotlight with the live score, then what's coming up. TMDB pictures for the shows.
- **Continue Watching from the official Nuvio app** (signed in to your Nuvio account).
- **Continue Watching in its own row**, as Apple TV's Up Next, along the bottom of the page; the apps scroll up under it.
- **Followed matches on the screensaver**, in Glassio's screensaver and the system one.
- **Back to Glassio after a restart** (Settings → System → *Open Glassio when the TV starts*).
- New name, icon and banner.
- Faster home screen: slide changes no longer redraw the whole screen, a pre-painted shade under the row, less work for accessibility when no screen reader is on.
- Needs Android 9 or newer.

## Before 2.0

Glassio Yahsun is a fork of [Glassio](https://github.com/mohammadmrh12/glassio) 1.9.8; its README lists what came before.
