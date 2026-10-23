# Glass Launcher

**An Apple TV–style home screen for Google TV / Android TV**, with real liquid glass, a carousel fed by your
Nuvio add-on catalogs, Continue Watching from Nuvio Legacy, tvOS-style icons, weather, screensavers and
Home Assistant hooks. Built with Kotlin and Jetpack Compose. Tested on the Google TV Streamer (4K).

[العربية — دليل الاستخدام الكامل](docs/README.ar.md)

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

---

## Features

**Carousel**
- Endless hero carousel from your Nuvio add-on catalogs (Cinemeta, AIOMetadata, TMDB lists …), shuffled across all catalogs if you like.
- Title logo (sizes evened out), rating / year / platform chips, genres, synopsis in Apple's grey, Play button when selected.
- **Backdrop gallery** like the Apple TV app: each title shows several of its pictures before the carousel moves on.
- **Continue Watching** from Nuvio Legacy: at the start of the carousel (1–5 slides or all) or mixed in, with progress
  (`Continue · S1 · E3 · 42%`). Back from Nuvio, the carousel opens on what you just watched. These titles are kept in
  memory, so going back to them — even after browsing far or pressing Home — never reloads.
- Home pressed on the home screen jumps back to the first slide.
- Opening a title brings Nuvio forward and opens it directly (no restart, no splash when Nuvio is running).

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

1. Download `GlassLauncher-x.y.z.apk` from [Releases](https://github.com/mohammadmrh12/glass-launcher/releases).
2. Install it on the TV: with the **Downloader** app (enter the release link), a USB stick, or from a computer:
   ```sh
   adb connect <tv-ip>:5555
   adb install -r GlassLauncher-x.y.z.apk
   ```
3. Open **Glass Launcher** from the TV's apps.

### Make it your home screen (Home button)

Google TV always sends the Home button to its own launcher, so Glass uses an accessibility service to catch it.

1. Settings → **System** → *Turn on the accessibility service* → choose **Glass Launcher** → On.
2. Recommended — so Android keeps the service on after updates, and so Glass can start the TV screensaver and read Nuvio's
   progress, run once from a computer:
   ```sh
   adb shell pm grant com.glasslauncher.tv android.permission.WRITE_SECURE_SETTINGS
   ```
3. The first time Glass uses the TV's own adb (TV screensaver, Continue Watching, silent updates) the TV asks
   **"Allow USB debugging?"** for Glass Launcher: tick **Always allow** and choose **Allow**. *Network debugging* must be
   on (Settings → System → Developer options).

---

## Setup

### Catalogs (Nuvio add-ons)
- **Settings → Catalogs → Add add-ons from my phone (QR)**: scan the code with your phone (same Wi-Fi), paste manifest
  links (`https://…/manifest.json`) and, optionally, your TMDB token.
- Or **Connect to Nuvio**: in Nuvio open *Add-ons → Manage from phone* first, then press Connect.
- Choose the catalogs you want; *Refresh catalogs* reloads them.

### TMDB token (logos, full-size art, synopsis language, ratings, gallery)
1. Create a free account at [themoviedb.org](https://www.themoviedb.org/) → Settings → API → copy the **API Read Access Token**.
2. Settings → Catalogs → **TMDB token** (or paste it on the phone page).

### Continue Watching
- Works with **Nuvio Legacy**: Glass reads what Nuvio Legacy writes to the system log while you watch (through the TV's
  adb, after "Allow USB debugging"). Other apps (Netflix, YouTube…) keep their Continue Watching private on Google TV.
- Settings → **Continue Watching**: at the start / mixed / off, how many slides lead, open on what you just watched, progress bar, clear the list.
- A title leaves the list when you finish it (past 92 %) or remove it.

### Icons
- Hold **OK** on an app → **Change icon** → Suggested, Apple TV icons (default / dark / white / clear), any installed
  icon pack, photos on the TV, a link, your phone, or back to the original.
- Settings → Dock & icons: icon pack, dock size, dock and settings background, icon style.
- Settings → Clock & glass: colour on focus, plate light/dark, bounce, lift.

### Weather
Settings → Clock & glass → **Weather** (everything / temperature + sky / temperature + city / temperature / off),
**Weather city** (English or Arabic name; the place found is shown next to it), Fahrenheit.

### Screensaver
Settings → **Screensaver** → *Type*: TV screensaver (whatever is chosen in Google TV settings, e.g. Aerial Views),
Apple aerial videos, or posters with logos. *Start after* (idle time on the launcher), clock, Back starts it.

### Performance
Settings → **Performance**: *Performance* (1280 px pictures, quick fades, flat glass, no gallery), *Balanced*, *Quality*
(original pictures, full glass). Each setting can be changed on its own (the mode then shows *Custom*).

---

## Home Assistant

Glass posts JSON to a Home Assistant **webhook** every time you open a title from the carousel:

```json
{ "event": "launch", "title": "Severance", "type": "series", "id": "tt11280740", "app": "space.nuvio.nativelegacy" }
```

**1. Create the automation** (Settings → Automations → Create → Edit in YAML):

```yaml
alias: Glass Launcher – title opened
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
- Settings → **Nuvio & Home Assistant → Home Assistant webhook**, or paste it on the phone page (Catalogs → *Add add-ons from my phone*).
- Turn on **Tell Home Assistant when a title opens**, then **Send a test event** (sends `{"event":"test"}`).

Use the IP address of Home Assistant (the TV may not resolve `homeassistant.local`). `local_only: true` keeps the webhook
on your home network.

---

## Updates
Glass checks this repository's latest release at start and every 12 hours. A banner and an **Update x.y.z** pill appear;
Settings → **About → Install update** downloads the APK and installs it. With "Allow USB debugging" accepted, the update is
silent and Glass reopens by itself; otherwise Android asks you to confirm (allow *Install unknown apps* for Glass Launcher).

Releases are signed with the same key, so they install over each other.

---

## Tools
- `tools/appletv_icons.py <out>` — builds the Apple TV icon library from Apple's public iTunes Search API (the tvOS
  home-screen icon of each app, 1280×768) and renders dark / white / clear looks. The icons belong to their owners and are
  not included in this repository.
- `TV=<tv-ip>:5555 tools/push_appletv_icons.sh <out>` — copies the library to the TV
  (`Android/data/com.glasslauncher.tv/files/appletv/`).
- `tools/framestats.py` — summarises `adb shell dumpsys gfxinfo com.glasslauncher.tv framestats` per frame stage.

Terminal helpers (`adb shell am start -n com.glasslauncher.tv/.MainActivity …`): `--ez open_folder true`,
`--ei open_settings <tab>`, `--es icon_pack <pkg|none>`, `--es tile_style …`, `--es dock_style …`, `--ei blur <n>`,
`--es saver_mode aerial|slideshow|system`, `--ez start_saver true`, `--es continue_mode first|mixed|off`, `--ez dump_settings true`.

## Build
```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Privacy & security
- No accounts, analytics or ads. Settings stay on the TV (`allowBackup=false`).
- The phone page only runs while its screen is open and every request needs the one-time token in its QR code.
- Webhook, TMDB token and add-on links are only sent where you point them.
- The TV's adb is used only for: starting the screensaver, reading Nuvio's playback log, installing Glass updates.

## Credits
Inter typeface (SIL Open Font License) · Weather by [Open-Meteo](https://open-meteo.com) · Catalog data from your Nuvio add-ons ·
This product uses the TMDB API but is not endorsed or certified by TMDB. · Not affiliated with Apple, Google or Nuvio.
