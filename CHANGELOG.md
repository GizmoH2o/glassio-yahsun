# Changelog

What changed in each Glassio Yahsun release, newest first. `tools/release.sh` publishes a build with its section here as the release notes (the in-app update banner shows them), and it stops if the version has no section: every release gets one.

## 3.6.0 — 2026-11-08

**Home**
- **Match cards**: on the Live TV page, a channel showing a match is drawn in the two teams' colours, with their crests and the score and clock (or when it starts). Football, NFL, NBA, NHL and MLB. Settings › Home › Match cards switches back to the show's picture.
- **OK on Continue watching resumes in its app**: Stremio plays the episode where you left off, Nuvio opens on it. Settings › Home › OK on Continue watching.

**Live TV**
- **Several guide sources**: Live TV › Settings › Guide › Guide sources has your provider's guide and XMLTV links of your own, each on or off, in order, with how its last update went. They make one guide; a channel in more than one takes the first. A link that can't be reached is left out, and one that breaks partway keeps the guide you have.
- **Choose a channel's guide**: hold OK on a channel (or on a show in the TV guide) › Guide… opens a panel with the guide in use, suggestions with your channel's country first, and Search, Type an ID and Automatic. It shows at once, wins over the playlist's and matching by name, and syncs to your other TVs. Settings › Guide › Guides chosen by hand resets them; Guide panel and Guide search switch back to the plain list and box.
- **Full-screen guide search** with the keyboard on the left and the guide's channels narrowing with every letter, what's on now on the one you're on.
- **Moments where they happened**: goals and red cards sit at the moment they were scored, not minutes off. Live TV › Settings › Moments timing moves them later if your stream runs behind.
- **No alerts for the match you're watching**: a followed team's goal and close-game cards stay away while you're on that match.
- **The More info trailer** asks YouTube for a quality to fit, and card trailers play sharper.

**Fixes**
- Live TV no longer freezes for a moment as its channel list loads.
- Sync with other TVs finds the TVs on your network reliably, and a pairing can't be undone by another device.
- Stremio resume plays anime episodes.
- A team whose colour is black shows its crest on its match card.
- The pause buffer and the guide's times take less work, and Multiview draws more lightly.

## 3.5.0 — 2026-11-08

**Home**
- **The big picture changes title as the streaming apps do**: a smooth crossfade of under a second, the words fading out with it and the new ones fading in as it ends.
- **The slow zoom is steady**: the big picture and an open card slowly grow from the moment they appear, towards their subject. Slow zoom now shows on both by default.
- **Home keeps its own animation speed**: some TVs run animations twice as fast, and Home's title change and cards now move as designed on them. Settings › Home › Follow the TV's animation speed puts them back on the TV's.
- **An open card's trailer** fades in more quickly, and moving along a row opens the next card sooner.

**Live TV**
- **Resolution badges sit beside the channel**, after its country, in the guide and the channel list, instead of on the logo.
- **In the player's Channels**, the badge sits under the channel number.
- **In Live TV search**, the badge is at the card's corner, and the country shows as a tag again.
- **About this show** shows the channel's resolution badge.
- **Stream badges as pictures**: the player shows the picture's resolution, codec, HDR and sound as badges. Turn them off in Live TV › Settings › Channels.

## 3.4.0 — 2026-11-07

**Home**
- **The big picture changes title like the streaming apps**: the words fade out as the picture changes, and the new ones rise in, in about half a second. Settings › Home › Changing title also has In steps and All at once.
- **Going down the page, the big picture scrolls away** with it, and the rows sit on a plain dark page. Settings › Home › Going down the page: Scrolls away or Stays.
- **Poster cards open into a wide card** with the picture, rating, year and genres, quickly and all at once. Along a row with a card open, the next one opens straight away. Settings › Home has Card opens after, Card opening, Keep the row open and Card style.
- **Slow zoom**: the still pictures slowly zoom in while you rest on them (the open card, the big picture, or both).
- **Trailer in the open card**: an open card can play its trailer, muted, after 2 to 10 seconds. It plays smoothly on the TV's own video hardware. Off at first.
- **Big picture shows** everything or the recommendations only.
- **The Today page**: your day on one page, with the weather, your matches with live scores, new episodes, new recordings and what's set for today. It can open by itself in the morning.
- **For You**: a row of titles picked on the TV from what you watch and save. Nothing is sent anywhere.
- **Your own collections**: hold OK on a title to add it to a row of your own. Because you watched and Quick watches rows fill themselves.
- **Continue watching** puts the next episode in place of a finished one, adds Live TV's past shows left partway, and shows a channel's resolution badge.
- **Title pages** list where a title is on (Live TV, your recordings, its streaming apps) and the cast, with their other titles.
- **Search** covers the TV guide, your recordings, your library, channels and apps in one box, with mood and length filters when nothing is typed.
- **Smoother title changes**: the big picture no longer stalls the page as it changes.

**Live TV**
- **Channel preview in the guide**: resting on a row plays that channel silently in the guide's window.
- **Smart channels**: guide rows made from rules, like your teams live, new episodes tonight, or words of your own.
- **Match Centre**: a Match tab in the player with the timeline, stats, lineups and table.
- **Sports alerts beyond kick-off**: a goal or a close game on another channel offers OK to watch, on Home too.
- **Multiview 2.0**: every box on the TV's video hardware, plus picture in picture, saved views and a wall of your teams' matches.
- **Voice commands**: "Put the Arsenal game on", "What's on tonight?", "Record this series" and more, in seven languages.
- **Family Mode 2.0**: a daily Live TV limit, a bedtime and an age rating for Kids profiles, and channels allowed or hidden per profile.
- **Resolution badges**: 4K, 2K, FHD, HD or SD on each channel, in the guide, the player and Home.
- **Country codes everywhere a channel is named**, as a small tag, for nearly 800 more channels.
- **Ad breaks in recordings** are found, and can be skipped with OK or by themselves.
- **Recordings can go to a USB drive**, and the Recordings page shows the free space and what won't fit.
- **Sources that keep failing** are passed over when a channel has another.
- **Following** has its own tab in the top bar.
- **Designs to keep and share**: save your design, share it with a QR code, and try community designs.
- **Accessibility**: TalkBack reads Live TV, plus Larger text and High contrast in Settings › Design.

**Phone page and home**
- **Live TV on the phone page**: what's on, find a show or team in the next 7 days, your schedule and recordings. Watch on TV, Remind and Record from the phone.
- **Calendar**: add a show to your phone's calendar, or subscribe to what's set and your teams' matches.
- **Home Assistant 2.0**: more events (pause, goals, kick-off, recordings, bedtime), and control the TV from Home Assistant.
- **Sync with other TVs at home**: favorites, profiles, followed teams and reminders, the same on every paired TV.
- **Backup to WebDAV** (Nextcloud, Synology and others), with the last 4 kept.
- **Problem reports** include Live TV, and can be saved to Downloads.

**Fixes**
- **Live TV no longer freezes** loading a very large channel list sorted by name.
- **Recordings play past 49 seconds again.**
- **The guide is more honest**: past shows follow each channel's own days, and a channel with no guide takes the one with exactly its name.
- **The player's info bar** shows the resolution badge next to the channel name, not over its logo.
- **Settings › Weather city** shows the city it found straight away.

## 3.3.0 — 2026-11-06

**A redesigned Live TV player**
- **New player controls.** A clearer layout with the channel, the time, the show and how far through it you are, and solid text with a soft shadow that reads over any picture. The fade behind it is the same on every screen.
- **Info** is now a full screen: the show, what you can do with it, and what's later on the channel. Everything from the old Options tab lives here, so that tab is gone.
- **Moments** is a new screen with a mark on the bar at each goal and red card, and a row of tiles to jump to them. It asks first when it would spoil the match.
- **Catch-up has its own controls**: skip a minute back or ahead, Moments, Audio & Subtitles and a red Back to live. Press up from the bar for **Score hidden**, and OK shows or hides the score.
- **Stacked skips show where you'll land**, with the stretch they cover and the time.
- **Audio & Subtitles** is redrawn, with subtitle size buttons, a **Night mode** switch that makes Live TV quieter straight away, and **Remember for this channel**.
- **Up next** now shows what else starts at the same time, with **Stay here** and **What else is on**. While it's up, ◀ ▶ and OK work on the card.

**Live TV settings, rebuilt**
- **A new home screen** with your connection, guide and subscription at a glance, nine sections, and search that also finds settings by their group.
- **A live video window** beside each section, so you can keep watching while you change things.
- **Connection check** watches what's playing for twenty seconds and suggests what to change.
- **Guide health** shows how many channels have a guide, whether it's on time, and which are missing.
- **A design gallery** in Look, to browse and try the designs on the real picture.

**The guide keeps its past**
- **Past days to keep now really keeps them.** Each update used to replace the guide with the provider's latest file, so you only ever had about a day. Finished shows now stay until your setting's limit, building up with each update. What was already gone can't come back.
- **Catch-up channels reach further back straight away**, by adding older shows from your provider's catch-up table where it has them. How far depends on the provider and the channel.

**Catch-up fixes**
- **Shows still airing now play.** On some channels catch-up would stay on "Still trying this channel" forever.
- **Catch-up no longer freezes when you skip.** A past show now moves by the minute, the bar says where it's going, and a part that won't play is skipped.
- **M3U playlists get catch-up** when they carry catch-up details.

**Removed**
- **Upscaling.** It only sharpened the picture, and doing so took the video off your TV's own video hardware, so 4K TVs got a 1080p picture. Without it, SD and HD channels go straight to the TV, which enlarges them itself.

**Home**
- **Colour from the picture**: Home's dark page picks up a little colour from the selected title. Turn it off in Settings › Look & motion.

**Other fixes**
- **The small channel card** at the top left no longer gets stuck on screen.
- **Catalogs**: when an add-on leaves your list, Home no longer empties.
- Channels in adult categories no longer appear by name in the Guide health list.
- Teams are recognised in matches written "A / B".
- Reminders show their full times in Settings.

## 3.2.0 — 2026-11-05

**Every Live TV design, up to date**
- **All the designs now use the new guide, channel list, show page and Multiview**, in their own colors, focus, corners and lettering. Each keeps its own player and info bar.
- The settings for the old guide layouts are gone: Logos and names in the guide, and Custom's guide.

**Fixes**
- **Show pages no longer borrow another show's details.** BBC Breakfast was showing a horror series' year and genres. Smaller shows may now show fewer details when TMDB's entry can't be confirmed.
- **Country badges are back on show pages**, beside the channel at the top and on the cards.
- A focused channel's logo stays visible on any focus color.

## 3.1.0 — 2026-11-05

**Two more languages**
- **French and Italian**, for Glassio and Live TV (Settings › Look & motion › Language; Auto follows the TV).

**A new Live TV**
- **Glassio 3**, a new design for the player, guide, channel list, show page, Multiview and settings, with text you can read over any picture. New installs start with it.
- **The guide updates by itself**, even with Live TV closed.
- **More NEW and LIVE badges.** When your guide marks a show on one channel and not another, the mark now carries to the same show elsewhere, and narrow tiles keep their badge.
- **Channel logos in the guide and channel list**, in place of the number most remotes no longer have. Prefer the number, or both? Live TV › Settings › Channels and categories › Beside the channel's name.
- **Choose your favorite channels** from the Live TV page, and **make your own groups**: hold OK on a channel, then Groups.
- **A channel that won't play tries another copy** of itself, and says so.
- **Each channel remembers** its audio and subtitle languages. **Stream info** is under About.
- **New settings**: hide channels with no guide, lock adult categories in one go, delete old recordings, keep only the newest of each show.
- **Sort categories by name** keeps the same name together by country: Sports UK, then Sports US.

**Following**
- **Teams under any name**: Manchester United also finds Man Utd, for the big European clubs, the NBA and the NFL. Add your own names with **Also called** on a team's page. Live TV's search finds them too.
- **Spoiler-free**: hide a team's scores for 24 hours, 3 days or until you show them. Its goal alerts, descriptions and results in titles stay hidden too.
- **A team's page** has its league table, results, fixtures and, during a match, stats and lineups. Chips show crests.
- **Today in sport**: the guide's live matches for the rest of today.
- **Record every match** for a team, and lines for half-time, full-time and red cards, each with its own switch.

**Home and Library**
- **Home rows**: put the rows in your order, hide them or rename them (Settings › Home). Each profile has its own.
- **Collections**: rows of Pixar, Studio Ghibli and Marvel Studios films, off until you turn them on.
- **Library**: an All tab, **Surprise me**, and NEW on series with an episode out.
- Where to watch goes by your TV's country first.

**Settings**
- **Set up Live TV from your phone**: the phone page has a sign-in form.
- **Weekly backup**, **Storage** with a clear button, and **Test the connection** to tell your internet from your provider (Settings › System).
- **Beta updates**, for test builds.
- **Night mode** dims Glassio and Live TV from the hour you choose until 7, and lowers Live TV's volume. **Remote guide** lists what the buttons do (Settings › Look & motion).

**Fixes**
- Following a team no longer catches a match of two other teams that happen to share its words.
- A live score that has stopped updating is no longer shown as live.
- US local channels no longer get another country's tag: AL, AR or CA in a name is the state, not Albania, Arabic or Canada.
- Faster searches of the guide.

## 3.0.0 — 2026-11-03

**A new look**
- **One design everywhere**: a near-black page, what's selected lifts and turns white, red only for what's live, new or in progress.
- **Tabs in the top bar**: Home, Live TV, Library and Search.
- **A page for every title**: Resume or Play, its trailer with sound, seasons and episodes, More like this. *OK on a card* in Settings › Home can play straight away instead.
- **Search everything**: titles, Live TV channels and apps from one box, with voice search where the TV has a recogniser.
- **Five languages**: Glassio and Live TV in English, Portuguese, Spanish, German or Russian (Settings › Look & motion › Language; Auto follows the TV).
- **Streaming apps show their icon** on their buttons.
- **Settings redone**: clearer rows, pages merged, and a search box.

**For everyone, with or without an account**
- **Recommendations**: with no Nuvio or Stremio account signed in, Home fills with what's trending and popular, from TMDB (Settings › Home › Recommendations: Auto, Always or Off).
- **A welcome** on the first run, and this page after an update.

**New on Home**
- **Trailers** behind the big picture (Auto, On or Off); on Auto they turn themselves off if they keep failing on a TV.
- **Because you watched** picks, **Not interested**, and **seasonal picks** on the big picture.
- **Profiles and Kids mode**, with a PIN.
- **Quick panel**: TV inputs, sleep timer, volume.
- **Live match ticker** under the tabs.
- **At a glance**: weather forecast, your calendars and Home Assistant readings.

**Accessibility and support**
- **Reduce motion** and **High contrast** (Settings › Look & motion).
- **Report a problem** on the phone page: a file with tokens, links and addresses taken out.

**Fixes**
- **The phone page's buttons work again**: the remote, typing and search did nothing since 2.2.0.
- **The Home button** keeps opening Glassio right after an update.
- Settings › Continue Watching lists titles by name.
- The match ticker, screensaver and kick-off alert keep a match after kick-off.

## 2.5.0 — 2026-10-31

**A new home screen**
- **One page instead of the carousel and dock**: the big picture (a title's backdrop, logo, IMDb rating, year, runtime, genres and synopsis, with Play and My Library), then your rows, then your apps.
- **Rows**: Airing this week (new episodes of the series in your library), Continue watching, Movies and Series in your library (from Nuvio, Stremio or both, Settings › Home), then your apps.
- **Apps in a grid**, 5 to a row by default (Settings › Apps & icons › *Apps per row*, 3 to 8); moving an app: ◀ ▶ one place, ▲ ▼ a row.
- **Home is there at once**: the titles and Continue Watching's cards as last shown, while the add-ons and accounts are asked again; with no connection, last time's titles stay.
- The big picture follows the title you stop on; each row keeps its place; Back goes to the first row, then the big picture; hold OK on any card or app for its menu.
- Settings: *Carousel* becomes *Home* and *Dock & icons* becomes *Apps & icons*; options for the old page (slide animations, dots, dock size, your own background) are gone.

**Continue Watching**
- **What each card is, under it**: the title and how much is left, then the episode (S2 · E5 and its title), a Live TV channel with its country, or a film's year and length.
- **Keep** (Settings › Continue Watching): everything, or only what you watched in the last 1–2 weeks or 30–90 days.
- **New episodes first**: a series with an episode out since you watched moves to the front.
- Lighter account syncs: an unchanged list isn't rebuilt, and while you're in another app the accounts are read every 15 minutes instead of 3.
- Rows no longer show a card cut off at the edge, and a removed card or row no longer loses the focus.

**Live TV**
- **Catch-up skipping that works**: ◀ ▶ presses add up and go once you stop (held, bigger steps); streams that can't seek are asked for again from the new minute.
- **Rewind from live** (◀ on the bar, or ⏪): about a minute back into Catch-up, where the provider has it; ⏮ still starts the show over.
- **Rewind any channel** (Live TV › Settings › Playback, off by default): every channel plays through the TV's own buffer of its last 15 minutes, so ◀ and ⏪ go back even without Catch-up.
- **Resume Catch-up**: a past show you left partway says where you stopped in the guide; OK asks *Resume from …* or *From the start*.
- **In the guide, hold ◀ for Catch-up**; a single ◀ goes to the groups. When the guide has nothing before now, the provider's past shows are asked for.
- **A slow connection steps down**: a stream that freezes switches to a lighter copy of a merged channel (SD after HD), and later channels start lighter.
- **Voice tunes only with "watch"**: "Watch TLC" tunes; "TLC" on its own is a search, in Live TV and from Google TV's search.
- **Live TV page**: a *Recently watched* row; *On now* becomes *Favorites on now*; program cards look like Continue Watching's.
- **Guide button** (Settings › System): opens Live TV's guide instead of the TV maker's app, on TVs that pass the remote's buttons to apps.
- Later shows' menu shows the channel's country tag; More airings lists one on now as *On now* and leaves out the ones already over; the info bar on a later show stays up 20 seconds; start times read "Starts in 25 min" or "Tomorrow 1:00 AM".

**Screensaver**
- **Turn the TV's own screensaver off** (Settings › Screensaver › *TV screensaver starts after*: Off), so Glassio's comes on instead; 5 to 30 minutes besides the short times.
- On TVs that refuse the usual way of starting the TV's screensaver, Glassio tries SystemUI's *Start now*.

## 2.4.0 — 2026-10-27

**Glassio Yahsun is now its own app**
- It installs as **com.glassioyahsun.tv**, beside the original Glassio (com.glasslauncher.tv), so both can be on one TV.
- **Moving from 2.3 or earlier:** updating installs Glassio Yahsun as a second app, without your settings, and the old one stays. To move everything over: in the old one, Settings › System › *Back up settings*; open the new one and restore that backup (Settings › System › *Restore a backup*); set the new one as the Home app if the TV asks; then uninstall the old one.

**Stremio**
- **Sign in to your Stremio account** (Settings › Nuvio, Stremio & Home Assistant › *Connect Stremio account*): a QR code, you sign in to Stremio on your phone and confirm; no password on the TV.
- **Its add-ons** can fill the catalogs (*Add-ons from my Stremio account*), alongside the TV's and the Nuvio account's.
- **Continue Watching** includes what's in progress in Stremio; removing a title here rewinds it in Stremio too.
- **The Library** shows your Stremio library, and the + button can save to it.
- **Open titles in Stremio**: a choice under *Open titles in*.

**Continue Watching**
- Each card is tagged **Nuvio** or **Stremio**, and OK opens it in that app, whatever *Open titles in* says.
- **Hold OK on a card**: open it in your other app, mark it watched where it came from (your Nuvio account, Trakt, or a film in Stremio), or take it out of the row.
- A Nuvio series shows the furthest episode you've reached, as Nuvio resumes it, not an old episode you happened to start again.

**Library**
- **A tab for each list** (Saved, Nuvio, Stremio, Trakt), only for the lists you use; it reopens on the tab you last had.
- Titles in the Nuvio and Stremio tabs open in the app they came from.

**Live TV**
- **Designs instead of themes**: Apple TV, YouTube TV, NOW, Sky, Sling, Hulu, Netflix, Prime Video, Disney+, Max, Plex and Pluto TV each bring their whole look: colors, focus, corners, guide, info bar and lettering. Netflix, Prime Video, Disney+, Max, Plex and Pluto TV have guides of their own.
- **Custom design**: pick the colors, focus, corners, guide, info bar and lettering from any design, with a picture of each choice. Your old Custom settings become the closest designs; design codes are 8 digits now.
- The channel list and search take their look from the design too.
- Design pickers show three a row, and **Design** and **Start-up** are separate sections in Settings.
- The guide's country tag sits at the bottom right of the channel, under the catch-up mark.
- **Episodes as a season and number chip** ("S5 · E8") with the title beside it, in the guide, its details, the info bar and the show's page; the synopsis no longer repeats the line.
- **Episode numbers from TMDB** for shows the guide sends only by title (matched by the episode's name, allowing for small differences in spelling), and Up next in the info bar shows the next episode's chip.
- **Public logos load faster**: each channel's match is kept, and the weekly check downloads the database again only when it has changed. Logo pictures are kept when the TV clears its caches (up to 80 MB), and backups leave them out.

## 2.3.0 — 2026-10-27

**Country tags and voice**
- **Country tags**: a channel's country (US, UK, CA…) on a small glass pill beside its name — in the guide, the channel list, the info bar, the categories and the home screen's cards — read from the name ("US: TLC", "TLC (US)", "TLC USA", a flag) or else its category ("UK | Sky Sports"), and the name shown without it. On or off in Live TV › Settings › Channels and categories.
- **Channels by voice**: "Play TLC USA on Glassio Yahsun", "TLC US", "channel 2340" open Live TV on that channel; a country at either end picks that country's channel, and without one the channels you watch win. Live TV's channels can also show in the TV's own search (when Google TV lists apps' results there). On or off in the same section: off, Glassio isn't offered to the TV for this at all.
- **Voice inside Live TV** (the remote's mic button, or the mic in Search): "TLC USA" or "302" tunes that channel at once (with Channels by voice on), and "watch …" always does; a country said picks that country's channel.
- **Favorites by voice**: "Add TLC to favourites", "Remove TLC from my favorites", "favourite Sky News", "unfavorite TLC" (and in Portuguese, "adicionar Globo aos favoritos").
- **Arabic channels**: providers mark them AR, which is Argentina's code; they're now tagged ARABIC (also from ARB, ARAB, ARABIC), unless the name says Argentina. Saying "Arabic" picks them by voice.
- Every setting in Live TV that's on or off is now a switch, as in Glassio's own settings (purple when on), in the sections and in their preview.

**Logos**
- **Channel logos** (Live TV › Settings › Channels and categories): from your provider, the TV guide (EPG), or the **public database** (iptv-org: sharp logos for over 30,000 channels, found by each channel's name and country; about 14 MB fetched once a week). The other sources fill in what the chosen one doesn't have, and a logo that can't be loaded falls back to the provider's.
- **A logo per channel**: hold OK on a channel (in the guide, or in the channel list) › Logo: as in Settings, the provider's, the TV guide's or the public database's, for that channel alone.
- **Better public matches**: the database's other names for each channel, and a guide that uses iptv-org's channel ids (TLC.us) gets that exact channel's logo.
- **The home screen's Live TV cards** show the same logos as Live TV (public and guide logos included). Pictures from Wikimedia, where most public logos live, now load on the home screen (it refused Glassio's picture loader before, leaving some cards without a logo).
- **Logos and names in the guide** (Live TV › Settings › Guide): lined up at the left of the channel column (the new default), or in the middle as before.
- **Logo in the player** (Live TV › Settings › Theme and start-up): steady, centered in its own space, so it no longer moves when a show has little or no synopsis (the new default), or centered on the text beside it as before.

**Good to know**
- Google TV's own voice search ("Watch TLC" said to Google) treats a channel name as one of the TV's tuner channels and doesn't ask apps. Naming Glassio ("Play TLC USA on Glassio Yahsun") asks Google to pass the request to Glassio, which then tunes the channel; inside Live TV, the remote's mic button always reaches Glassio.

## 2.2.0 — 2026-10-26

**Backup and restore**
- **Back up settings** (Settings › System): everything in one file, sign-ins included (Nuvio, Trakt, TMDB, Live TV's playlists and logins), your app icons, Live TV's favorites, Following, profiles and reminders, and Glassio's TV access, so no new "Allow USB debugging" after a restore. Locked with a code you choose (at least 6 characters); without the code the file can't be opened.
- The backup goes to the TV's Downloads, which an uninstall leaves alone, and to your phone from Manage from phone (Download a backup).
- **Restore a backup** (Settings › System, or Restore on the TV from the phone page): Glassio's settings are replaced and it restarts by itself. After a reinstall it reads the backup from Downloads through TV access, or you send it from your phone.
- Recordings and the TV guide stay out of the backup (Live TV reads the guide again).

**Watch on Home**
- The small window is selected as it opens: OK goes full screen in Live TV, Up / Down (or the remote's channel buttons) change the channel, Left goes back to the home screen. Back selects the window again, and Back on it closes it.
- Its video has rounded corners, like its frame.

**Live TV**
- The TV guide shows each channel's number before its name ("2340  TLC").
- Back in the guide goes straight back to the channel, full screen (before anything plays, it still opens the categories). Back in the categories goes back to the guide.
- A channel changed in the small window (Watch on Home) is the one watched last from then on: the home screen's Live TV card, History, and Live TV's next start follow it.
- **Record from the home screen**: hold OK on a Live TV card for Record this show (or Stop recording) and Remove from Continue Watching.
- **Kick-off alert**: when a team you follow starts playing while the home screen is in front, a glass card under the weather offers Watch, Small window or Not now. Once per match; it goes after half a minute.

**Backup**
- Settings › System says when the last backup was made, and marks Back up settings "Due" when there's none or it's over a month old.

**For developers**
- Tests for the backup's lock (right code, wrong code, a changed or cut-short file) and the kick-off timing run on every release; `tools/release.sh` stops when one fails.
- A GitHub Actions workflow builds, tests, signs and publishes a release when a version tag is pushed (it needs the release key as repository secrets).
- Screenshots of Live TV, Following, the styles and backup in the README.

**Live TV styles**
- Six new styles (Live TV › Settings › Theme and start-up › Style): **Netflix** (near black, a red focus, nearly square corners, a big picture), **Prime Video** (deep navy, a bright blue focus), **Disney+** (royal blue into night, glass with a light edge, a blue focus), **Max** (black into deep blue, the large picture at the right), **Plex** (dark grey, an amber focus) and **Pluto TV** (black, a yellow focus, a guide with many rows). Each also works for the morning, night and late-night styles.
- The style picker shows the 14 styles in two full rows (Custom last), so every tile is large enough to read.

**Polish**
- After a fresh install the home screen lists the steps to set Glassio up, each with where it stands (restore a backup, catalogs, Nuvio, TV access, the Home button, Live TV) and OK to go there.
- Live TV's lists: the selected row is a rounded plate inside the card, as in Glassio's own lists.
- Codes typed in Glassio's own dialogs (a backup's) show as dots.
- Fixed: a page asked for as Glassio started (after an update, say) could close again by itself. Google TV's own home screen shows for a moment while Glassio starts, and the Home-button service's fallback answered it with Home a split second later.

## 2.1.1 — 2026-10-26

**Install once by hand**: from this version, Glassio Yahsun is signed with its own release key instead of a development one. If you have 2.1.0, uninstall it and install 2.1.1 (updates work again after that). Uninstalling clears Glassio's settings and Live TV's sign-in, so note your playlist details first.

- Releases are signed with Glassio Yahsun's own key, and the release script refuses any APK signed with another one.
- Tidied what was left from porting Live TV: its developer test commands are documented as they work in Glassio, and unused code is gone.

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
