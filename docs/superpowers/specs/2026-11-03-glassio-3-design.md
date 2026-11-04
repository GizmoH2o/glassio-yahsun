# Glassio 3.0: tvOS behaviour, Netflix hierarchy

Approved from the mockups (private canvas "Glassio 3.0 Mockups") on 3 Nov 2026.

## Design system (`ui/Design.kt`)
- One near-black background, one surface, three corner sizes, one text scale.
- Selected: lift, soft shadow, white plate with dark text on buttons and rows, white outline on pictures.
- Red only for LIVE, NEW and progress. No purple. Switches green.
- Reduce motion (no lift or slide) and High contrast (stronger selection, solid backing), in Settings.

## Structure
- Top tabs on Home, Live TV, Library and Search. Hidden in Kids mode. The ticker sits under them.
- Settings button's menu keeps what the tabs don't cover.
- Title page: Resume or Play, Trailer with sound, My Library, Not interested, streaming app link, seasons and episodes, More like this. Setting "OK on a card": title page (default) or play straight away. Live TV cards always tune.
- Big picture: Play, More info, streaming app, My Library.
- Search: titles, Live TV, apps, with an on-screen keyboard.
- Settings pages: Home, Look & motion, Apps & icons, Catalogs, Continue Watching, Accounts & Home Assistant, Screensaver, Manage from phone, System, About; opaque background, search box.

## Recommendations without an account
Setting "Recommendations": Auto (when neither Nuvio nor Stremio is signed in and no add-on is set), Always, Off.
- Source: TMDB (the token in Settings, else one built in with `TMDB_READ_TOKEN` in local.properties). No token at all: Cinemeta's free charts, so Home is never empty.
- Fills the big picture when there are no catalogs, and adds rows: Trending, Popular films, Popular series.

## Production features
- Welcome flow on first run; What's new after an update (the changelog is bundled).
- Trailer protection: a web view renderer crash doesn't take Glassio down; two failures with Trailers on Auto turn them off.
- Report a problem on the phone page, with tokens, passwords, links and addresses removed.
- Home scrolling profiled and improved.

## Out of scope
Bundling other companies' logos or artwork as Glassio's own look (a streaming app's button shows the icon the TV has installed for it); translating the phone page and What's new; rewriting Live TV's player and guide, whose Glassio design already uses these colours.
