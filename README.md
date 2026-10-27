<p align="center"><img src="docs/brand/icon_512.png" width="140" alt="Glassio Yahsun icon"></p>

# Glassio Yahsun

**An Apple TV–style home screen for Google TV / Android TV**, with real liquid glass, a carousel fed by your
Nuvio add-on catalogs, a Live TV (IPTV) player with a full guide, Following for your teams and shows, Continue Watching from Nuvio Legacy and the official Nuvio app, tvOS-style icons, weather, screensavers and
Home Assistant hooks. Built with Kotlin and Jetpack Compose. Tested on the Google TV Streamer (4K).

[العربية — دليل الاستخدام الكامل](docs/README.ar.md) · [Discussions](https://github.com/GizmoH2o/glassio-yahsun/discussions) · [Report a bug](https://github.com/GizmoH2o/glassio-yahsun/issues/new/choose)

![Home](docs/screenshots/01-home.jpg)

| | |
|---|---|
| ![Carousel](docs/screenshots/02-carousel.jpg) | ![Dock](docs/screenshots/03-dock-focus.jpg) |
| ![Apps open](docs/screenshots/04-apps-open.jpg) | ![Apps folder](docs/screenshots/05-apps-folder.jpg) |
| ![App menu](docs/screenshots/06-app-menu.jpg) | ![Icon picker](docs/screenshots/07-icon-picker-appletv.jpg) |
| ![Performance](docs/screenshots/10-settings-performance.jpg) | ![Carousel settings](docs/screenshots/11-settings-carousel.jpg) |
| ![Dock & icons](docs/screenshots/13-settings-dock-icons.jpg) | ![Catalogs](docs/screenshots/14-settings-catalogs.jpg) |
| ![Continue Watching](docs/screenshots/15-settings-continue-watching.jpg) | ![Nuvio & Home Assistant](docs/screenshots/16-settings-nuvio-ha.jpg) |
| ![Screensaver](docs/screenshots/17-settings-screensaver.jpg) | ![About](docs/screenshots/20-settings-about.jpg) |
| ![Menu](docs/screenshots/40-menu.jpg) | ![Themes](docs/screenshots/41-menu-themes.jpg) |
| ![Background](docs/screenshots/42-menu-background.jpg) | ![Library](docs/screenshots/43-library.jpg) |
| ![Screensaver as background](docs/screenshots/44-screensaver-background.jpg) | ![Black on white](docs/screenshots/45-theme-black-on-white.jpg) |

**Live TV, Following and backup (Glassio Yahsun)**

| | |
|---|---|
| ![Continue Watching with Live TV](docs/screenshots/50-live-continue-watching.jpg) | ![Record from the home screen](docs/screenshots/51-live-card-record.jpg) |
| ![Watch on Home](docs/screenshots/52-watch-on-home.jpg) | ![Live TV page](docs/screenshots/53-live-tv-page.jpg) |
| ![Following](docs/screenshots/54-following.jpg) | ![TV guide with channel numbers](docs/screenshots/55-guide-channel-numbers.jpg) |
| ![Guide in the Glassio design](docs/screenshots/56-guide-style-glassio.jpg) | ![Guide in the Netflix design](docs/screenshots/57-guide-style-netflix.jpg) |
| ![Guide in the Max design](docs/screenshots/58-guide-style-max.jpg) | ![Live TV designs](docs/screenshots/59-live-style-picker.jpg) |
| ![Live TV settings](docs/screenshots/60-live-settings-theme.jpg) | ![Live TV menu](docs/screenshots/61-live-menu.jpg) |
| ![Backup in Settings](docs/screenshots/62-settings-backup.jpg) | ![Setup checklist](docs/screenshots/63-setup-checklist.jpg) |

---

## Features

What changed in each release: [CHANGELOG.md](CHANGELOG.md).

**Live TV**
- An IPTV player for Xtream Codes, M3U links and MAG portals, with a full guide, favorites, pause and rewind live TV, catch-up, recordings, Multiview, profiles (with a Kids one), reminders and live scores. Its default design is Glassio's own: clear glass over graphite, a white focus, round corners. **Designs** (Live TV › Settings › Design) change the whole look to match Apple TV, YouTube TV, NOW, Sky, Sling, Hulu, Netflix, Prime Video, Disney+, Max, Plex or Pluto TV: the colors, the focus, the corners, the guide's layout, the info bar and the lettering. **Custom** takes each of those parts from any design. It runs in its own process, so video never weighs on the home screen.
- **Live TV page** (menu → Live TV): the channel you watched last, the TV guide, search, then *On now* on your favorite and recent channels, *Sports live now* and *Starting soon*.
- **TV guide**: each channel's number before its name; Back goes straight back to the channel, full screen.
- **Country tags** beside channel names (US, UK, CA, ARABIC…; in the guide at the bottom right, under the catch-up mark) and **channels by voice** ("Play TLC USA on Glassio Yahsun"; inside Live TV just "TLC USA"), each with its own switch.
- **Favorites by voice**: "Add TLC to favourites", "Remove TLC from favourites".
- **Channel logos** from your provider, the TV guide or the public iptv-org database, or a **logo per channel** (hold OK › Logo); the home screen's Live TV cards show the same logos.
- Live TV is ported from [Zero](https://github.com/GizmoH2o/zero-tv) (MIT, notice kept in `app/src/main/assets/licenses/Zero-MIT.txt`).

**Live TV on the home screen**
- **Continue Watching** starts with Live TV: a followed match on now (with the score), the channel you watched last (what's on, with a LIVE tag) and your newest recordings. OK opens them in Live TV; hold OK offers **Record this show** or takes the card out of the row.
- **Watch on Home** (Live TV's menu): the channel keeps playing in a small glass window on the home screen while you browse. OK goes full screen, Up / Down change the channel, Back selects it and closes it.
- A **kick-off alert** when a team you follow starts playing: Watch, Small window or Not now.

**Following**
- **Following** (menu → Following): follow a team or a show; its next match shows in a spotlight with the live score, then what's coming up, the same match on other channels, replays and shows. A team's page lists its next 7 days with Remind and Record.
- **Followed matches on the screensaver**: your team's next match (within 18 hours) on a glass card under the clock, with LIVE and the score near kick-off, in Glassio's screensaver and as the system screensaver (Settings → Screensaver → *Followed matches*).

**Backup and restore**
- **Back up settings** and **Restore a backup** (Settings › System, and the phone page): everything, sign-ins included, in one file locked with a code you choose. It goes to the TV's Downloads and to your phone; restoring brings it all back and restarts Glassio. Settings marks a backup "Due" when there's none or it's over a month old.
- A setup checklist on a fresh install (restore a backup, catalogs, Nuvio, TV access, the Home button, Live TV).

**Menu, Library, themes and backgrounds**
- **Menu** at the settings button, like the Apple TV Control Center: clock, date, weather (high / low, feels like, humidity, wind, city), then Library, Glassio settings, Android settings, Wi-Fi, Themes and Background. It opens when you go up to the button (or with OK, or never: Settings → Menu, Library & themes), and every part can be hidden.
- **Library**: the **+** button between Play and Next saves the title. Grey page, five posters a row; OK opens the title, hold OK removes it. It also shows your **Nuvio account library** (Nuvio Legacy, official Nuvio and the forks that sync with the account; sign in with a QR code) and your **Trakt watchlist**; + can save to both.
- **Themes**: Black on white, Black AMOLED, Liquid, Clear, Glass still, Glass is glass — for the dock, the icons and the menu (black ones keep a clear matte dock). *Colour on focus* and the **focus plate** (White, Dark, Glass or Black AMOLED behind the selected icon) are in the same page.
- **Background**: each title's backdrop, **your picture** or **your video** (a link, or a file in the backgrounds folder shown in Settings → Clock & glass, or sent from your phone through Manage from phone), or **the screensaver's aerials**. With your own background the page is just that (no title or buttons); the glass follows the video.

**System**
- **Back to Glassio after a restart** (Settings → System → *Open Glassio when the TV starts*): Google TV opens its own home screen after a restart; Glassio comes up over it through the Home-button service, or TV access (ADB).
- **Turn the accessibility service on with ADB** from Settings → System, for TVs that grey it out (Google TV Streamer).
- The TMDB token and the Home Assistant webhook show a green check when they work (red when TMDB refuses the token, or the API Key was pasted instead of the API Read Access Token).

**Carousel**
- Endless hero carousel from your Nuvio or Stremio add-on catalogs (Cinemeta, AIOMetadata, TMDB lists …), shuffled across all catalogs if you like. Add-ons from your Nuvio account and your Stremio account join the TV's own (Settings → Catalogs), never twice.
- Settings → Carousel → **Slide animation**: **Apple TV** (default: the next picture opens up from the right with parallax, from the left going back, glass included), *Slide* (pushed in) or *Fade*.
- **Buttons**: **Play + Next** (default, like Apple TV: Right from Play selects the round Next button, Right or OK on it shows the next title, Left on Play the previous one; Play stays in place whatever the synopsis length), *Play only* or *Hidden*.
- **Dots**: *Dots*, **Liquid glass dots** (clear glass drops on a glass capsule that stretch, join and part like water as you move), *Progress bar* or *Hidden*.
- Title logo (sizes evened out), rating / year / platform chips, genres, synopsis in Apple's grey, Play button when selected.
- **Backdrop gallery** like the Apple TV app: each title shows several of its pictures before the carousel moves on.
- **Continue Watching** from Nuvio Legacy: at the start of the carousel (1–5 slides or all) or mixed in, with progress
  (`Continue · S1 · E3 · 42%`). Back from Nuvio, the carousel opens on what you just watched. These titles are kept in
  memory, so going back to them — even after browsing far or pressing Home — never reloads.
- **Continue Watching from the official Nuvio app**: signed in to your Nuvio account (Settings → Nuvio, Stremio & Home Assistant), what you're watching there joins Continue Watching (Settings → Continue Watching → *Nuvio (official app)*). Removing a title here removes it on the account too.
- **Stremio**: signed in to your Stremio account (Settings → Nuvio, Stremio & Home Assistant → *Connect Stremio account*: a QR code, you sign in to Stremio on your phone and confirm; no password on the TV), what you're watching in Stremio joins Continue Watching (*Stremio*), its add-ons can fill the catalogs (*Add-ons from my Stremio account*), its library shows in the Library and the + button can save to it. Removing a title here removes it in Stremio too.
- Home pressed on the home screen jumps back to the first slide.
- Opening a title brings Nuvio forward and opens it directly (no restart, no splash when Nuvio is running).
  Works with **Nuvio Legacy**, the **official Nuvio TV** app, **Nuvio Arc**, **Nuvio Re-Shaped** and **Stremio**
  (Settings → Nuvio, Stremio & Home Assistant → *Open titles in*; **Auto** picks the first installed in that order).
  A title from Continue Watching or the Library's Nuvio and Stremio tabs opens in the app it came from instead.

**Glass**
- Real refraction (AGSL shader), blurred backdrop shared by every glass surface, "Glass like glass" (with or without ripple), clear, AMOLED black, black-on-white.
- The page blurs and dims when you move down into the apps, glass included.
- Backdrop and glass always change in the same frame.

**Dock & icons**
- 6 apps per row, Apps folder with every app (hidden ones too), move / hide / change icon / app info (hold OK).
- tvOS behaviour: the selected icon lifts, **colour on focus** (icons white at rest, coloured on a light or dark plate),
  rubber-band **bounce at the edges**.
- Icons from icon packs (Projectivy, Blueprint, Nova, ADW), a link, the TV's photos, your phone, or the **Apple TV icon
  library** (default, dark, white and clear looks, see [tools](#tools)).

**Top bar**
- Clock and date on the left (Apple TV) or next to Settings; weather (Open-Meteo, no key) with condition and city, each part optional; auto-hide.

**Screensaver**
- Your TV's own screensaver (for example Aerial Views) started instantly, Apple aerial videos, or posters with logos.
  Back on the home screen can start it, like Apple TV.
- **Aerial Views as the TV screensaver** on / off and its start time (20 s, 30 s, 1 min, 2 min) through ADB, in Settings → Screensaver. Glassio's own screensaver can start after 10 s or 30 s.

**Home Assistant**
- Webhook when a title opens (title, type, id, app). See [Home Assistant](#home-assistant).

**Updates**
- Checks GitHub Releases, shows a banner and a pill next to the clock; Settings → About → Install update downloads it
  and installs it (silently through the TV's adb, or with Android's installer).

**Performance**
- Performance / Balanced / Quality modes; animations read in the draw and layout phases (no recomposition per frame),
  one cached GPU layer for the backdrop, shader early-outs, background-priority blur, Compose baseline profiles, R8.

---

## Install

1. Download `GlassioYahsun-x.y.z.apk` from [Releases](https://github.com/GizmoH2o/glassio-yahsun/releases).
2. Install it on the TV: with the **Downloader** app (enter the release link), a USB stick, or from a computer:
   ```sh
   adb connect <tv-ip>:5555
   adb install -r GlassioYahsun-x.y.z.apk
   ```
3. Open **Glassio** from the TV's apps.

Glassio needs Android 9 or newer.

Glassio Yahsun installs as **com.glassioyahsun.tv**, its own app beside the original Glassio (com.glasslauncher.tv), so
both can be on one TV. Versions up to 2.3 used the original's name: moving from one of those, make a backup there
(Settings → System → *Back up settings*), install this one, restore the backup in it (Settings → System → *Restore a
backup*), then uninstall the old one.

### Make it your home screen (Home button)

Google TV always sends the Home button to its own launcher, so Glass uses an accessibility service to catch it.

1. Settings → **System** → *Turn on the accessibility service* → choose **Glassio** → On.
2. Recommended — so Android keeps the service on after updates, and so Glass can start the TV screensaver and read Nuvio's
   progress, run once from a computer:
   ```sh
   adb shell pm grant com.glassioyahsun.tv android.permission.WRITE_SECURE_SETTINGS
   ```
3. The first time Glass uses the TV's own adb (TV screensaver, Continue Watching, silent updates) the TV asks
   **"Allow USB debugging?"** for Glassio: tick **Always allow** and choose **Allow**. *Network debugging* must be
   on (Settings → System → Developer options).

---

## Setup

### Catalogs (Nuvio and Stremio add-ons)
- **Settings → Catalogs → Add add-ons from my phone (QR)**: scan the code with your phone (same Wi-Fi), paste manifest
  links (`https://…/manifest.json`) and, optionally, your TMDB token.
- Or **Connect to Nuvio**: in Nuvio open *Add-ons → Manage from phone* first, then press Connect.
- Or turn on **Add-ons from my Nuvio account** / **Add-ons from my Stremio account** (signed in to that account): its add-ons join the list; one already here isn't added twice.
- Choose the catalogs you want; *Refresh catalogs* reloads them.

### TMDB token (logos, full-size art, synopsis language, ratings, gallery)
1. Create a free account at [themoviedb.org](https://www.themoviedb.org/) → Settings → API → copy the **API Read Access Token**.
2. Settings → Catalogs → **TMDB token** (or paste it on the phone page).
3. Settings → Carousel → **Synopsis language**: 24 languages (English, Arabic, French, Spanish, German, Italian,
   Portuguese, Turkish, Russian, Persian, Hindi, Japanese, Korean, Chinese …). English is used when a title has no text in that language.

### Continue Watching
- Works with **Nuvio Legacy**: Nuvio Legacy writes what you play (title, episode, how far) to the TV's log, and Glassio
  reads that log through the TV's own debugging connection (ADB, see below). Nothing leaves the TV.
- **Trakt**: Settings → Continue Watching → *Connect Trakt account* shows a short code; enter it at trakt.tv/activate on
  your phone (no password on the TV). What you pause in any app that scrobbles to Trakt — the official Nuvio app,
  Nuvio Arc, Re-Shaped, Stremio, Kodi… — then shows up too (synced every few minutes and when you come back to Glassio).
  Removing a Trakt title here removes it on Trakt as well.
- **The official Nuvio app** and **Stremio**: sign in to the same account here (Settings → Nuvio, Stremio & Home Assistant) and what you're watching there shows up, read from the account every few minutes and when you come back to Glassio.
- Nuvio Arc and Re-Shaped don't write the Legacy log, so for them use Trakt. Other apps (Netflix, YouTube…) keep their Continue Watching private on Google TV.
- Settings → **Continue Watching**: at the start / mixed / off, how many slides lead, open on what you just watched, progress bar, clear the list.
- Each card is tagged **Nuvio** or **Stremio**, and OK opens it in that app. A series shows the furthest episode you've
  reached, as Nuvio resumes it.
- Hold OK on a card to open it in your other app, mark it watched (on the Nuvio account, on Trakt, or a film in
  Stremio) or take it out of the row.
- A title leaves the list when you finish it (past 92 %) or remove it.

### TV access (ADB) — one-time setup
Continue Watching, *Make Aerial Views the TV screensaver*, starting the screensaver and silent updates use the TV's own
debugging connection (Glassio talks to it on the TV itself, like Nuvio TV Relay). Settings → **System** walks you through it:
1. **Open About** → press OK on *Android TV OS build* (or *Build*) **7 times** until it says you are a developer.
2. **Open Developer options** → turn on **USB debugging** (on some TVs also *Network debugging* / *ADB over network*).
3. **Allow Glassio** → the TV asks *Allow USB debugging?* → tick **Always allow from this computer** → OK. Asked only once.
The rows show what is already done (Developer options on, USB debugging on, Glassio allowed).

### Icons
**One app**
- On the home screen, hold **OK** on the app → **Change icon**, then pick from:
  Suggested, the Apple TV icons (default / dark / white / clear), any installed icon pack,
  a photo saved on the TV, a link to a PNG, your phone (Settings → Manage from phone), or back to the original.

**Icon packs (all apps at once)**
1. Install an Android TV / Projectivy icon pack from Google Play
   (Settings → Dock & icons → *Find icon packs on Google Play* opens the search). ADW / Nova / Blueprint packs work too.
2. Settings → Dock & icons → **Icon pack** → choose it. Every app takes the pack's icon; single apps can still be changed.

**Many icons from files**
- Copy `<package>.png` files (for example `com.netflix.ninja.png`) into the icon folder shown in
  Settings → Dock & icons, then press **Import icons from the icon folder**.

**Apps folder**: Settings → Dock & icons → *Show Apps folder* (or hold OK on the folder → *Hide from the dock*).

Look: Settings → Dock & icons (dock size, dock and settings background, icon style) and
Settings → Clock & glass (colour on focus, plate light/dark, bounce, lift).

### Weather
Settings → Clock & glass → **Weather** (everything / temperature + sky / temperature + city / temperature / off),
**Weather city** (English or Arabic name; the place found is shown next to it), Fahrenheit.

### Screensaver
**Recommended: [Aerial Views](https://play.google.com/store/apps/details?id=com.neilturner.aerialviews)**, the best
screensaver with Glassio: Apple, Amazon and your own 4K videos, clock and info overlays, free.

1. Settings → **Screensaver** → **Install Aerial Views** (opens Google Play).
2. Back in Glassio: **Make Aerial Views the TV screensaver**. The first time the TV asks to allow USB debugging
   for Glassio: choose *Always allow*. (By hand: `adb shell settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity`.)
3. *Type* is set to **TV screensaver**. *Open Aerial Views settings* picks videos, clock and overlays.
4. *Start after* (idle time on the launcher) and **Back on the home screen starts it**.

Without Aerial Views: *Type* → Apple aerial videos (streamed by Glassio) or posters with logos, and *Add video link* for your own .mp4 / .mov.

### Performance
Settings → **Performance** → *Mode*: **Auto** (default: picked for the TV from its memory and graphics chip,
e.g. a 2 GB TCL gets Performance, a Google TV Streamer gets Balanced), *Performance*, *Balanced*, *Quality*.
Each setting can be changed on its own (the mode then shows *Custom*):
- **Glass detail**: resolution of the picture the glass bends (480 px, or 720 px for crisper glass).
- Picture quality, prepare ahead, slide transition, glass refraction, backdrop gallery.

The clear dock and tiles show the page picture itself (half screen resolution), so the glass lines up with the
backdrop and stays sharp at any *Glass detail*.

In Settings, rows changed with ◀ ▶ (and app order) only change after you press **OK** on them; OK or Back
again when done. Moving down the list never changes a value by accident.

### Artwork
Settings → Carousel → **Artwork**: Main (TMDB), Second picture, Highest resolution, or the add-on's own picture.
For one title: on the carousel **hold OK** (or press Menu) to switch to its next picture; Glassio remembers it
(*Reset pictures picked per title* undoes all of them).

---

## Home Assistant

Glass posts JSON to a Home Assistant **webhook** every time you open a title from the carousel:

```json
{ "event": "launch", "title": "Severance", "type": "series", "id": "tt11280740", "app": "space.nuvio.nativelegacy" }
```

**1. Create the automation** (Settings → Automations → Create → Edit in YAML):

```yaml
alias: Glassio – title opened
mode: queued
triggers:
  - trigger: webhook
    webhook_id: glass_launcher_CHANGE_ME   # pick a long random id
    allowed_methods: [POST]
    local_only: true
actions:
  # Re-fire as an event other automations can use
  - event: glass_launcher
    event_data:
      title: "{{ trigger.json.title }}"
      type: "{{ trigger.json.type }}"
      id: "{{ trigger.json.id }}"
  # Example: movie time
  - if: "{{ trigger.json.event == 'launch' }}"
    then:
      - action: light.turn_off
        target: { area_id: living_room }
```

**2. Give Glass the URL**: `http://<home-assistant-ip>:8123/api/webhook/glass_launcher_CHANGE_ME`
- Settings → **Nuvio, Stremio & Home Assistant → Home Assistant webhook**, or paste it on the phone page (Catalogs → *Add add-ons from my phone*).
- Turn on **Tell Home Assistant when a title opens**, then **Send a test event** (sends `{"event":"test"}`).

Use the IP address of Home Assistant (the TV may not resolve `homeassistant.local`). `local_only: true` keeps the webhook
on your home network.

---

## Updates
Glass checks this repository's latest release at start and every 12 hours. An **Update x.y.z** pill appears next to the
clock with a small banner of what's new: go up to it and press **OK** to download and install right there (or Settings →
**About → Install update**). With "Allow USB debugging" accepted, the update is
silent and Glass reopens by itself; otherwise Android asks you to confirm (allow *Install unknown apps* for Glassio).

Releases from 2.1.1 on are signed with the same key, so they install over each other (2.1.0 used another: uninstall it once).

---

## Troubleshooting
- Everything that fails quietly (network, Trakt, the TV's adb, opening a title in Nuvio) is logged under one tag:
  `adb logcat -s Glassio`.
- Settings snapshot: `adb shell am start -n com.glassioyahsun.tv/com.glasslauncher.tv.MainActivity --ez dump_settings true`, then `adb logcat -s GlassSettings`.
- Continue Watching from Nuvio Legacy, the Aerial Views button and silent updates need TV access (ADB): Settings → System.

## Tools
- `tools/appletv_icons.py <out>` — builds the Apple TV icon library from Apple's public iTunes Search API (the tvOS
  home-screen icon of each app, 1280×768) and renders dark / white / clear looks. The icons belong to their owners and are
  not included in this repository.
- `TV=<tv-ip>:5555 tools/push_appletv_icons.sh <out>` — copies the library to the TV
  (`Android/data/com.glassioyahsun.tv/files/appletv/`).
- `tools/release.sh [--dry-run]` — builds the release APK and publishes it on GitHub with its CHANGELOG.md section as
  the release notes (it stops when the version has none). See [Build](#build).
- `tools/framestats.py` — summarises `adb shell dumpsys gfxinfo com.glassioyahsun.tv framestats` per frame stage.

Terminal helpers (`adb shell am start -n com.glassioyahsun.tv/com.glasslauncher.tv.MainActivity …`): `--ez open_folder true`,
`--ei open_settings <tab>`, `--es icon_pack <pkg|none>`, `--es tile_style …`, `--es dock_style …`, `--ei blur <n>`,
`--es saver_mode aerial|slideshow|system`, `--ez start_saver true`, `--ez show_setup true|false`, `--es continue_mode first|mixed|off`, `--ez dump_settings true`.

## Build
```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

To release: bump `versionCode` / `versionName` in `app/build.gradle.kts`, add the version's section at the top of
[CHANGELOG.md](CHANGELOG.md), commit and push, then run `tools/release.sh` (needs the GitHub CLI, signed in). Releases are
signed with Glassio Yahsun's release key, set in `local.properties` (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; never committed); the script checks the APK's certificate. Without that
key a release build is signed with the debug key, which installs fine but can't update a published release.

## Privacy & security
- No accounts, analytics or ads. Settings stay on the TV (`allowBackup=false`); a backup you make is locked with your code (AES-256-GCM, the key from PBKDF2) and goes only where you take it.
- The phone page only runs while its screen is open and every request needs the one-time token in its QR code.
- Webhook, TMDB token and add-on links are only sent where you point them.
- The TV's adb is used only for: starting the screensaver, reading Nuvio's playback log, installing Glass updates.

## Credits
Glassio Yahsun is a fork of [Glassio](https://github.com/mohammadmrh12/glassio) (GPL-3.0); Live TV comes from
[Zero](https://github.com/GizmoH2o/zero-tv) (MIT). ·
Inter typeface (SIL Open Font License) · Weather by [Open-Meteo](https://open-meteo.com) · Catalog data from your Nuvio add-ons ·
This product uses the TMDB API but is not endorsed or certified by TMDB. · Not affiliated with Apple, Google or Nuvio.

## License
Glassio is free software under the [GNU General Public License v3.0](LICENSE) (GPL-3.0), the same license as Nuvio.
You may use, study, change and share it; changed versions you publish must stay open source under GPL-3.0.
