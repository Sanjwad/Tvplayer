# TV Player

A personal Android TV player for Stalker (MAC-based) IPTV portals. Works on
NVIDIA Shield, Fire TV Stick and Onn (Google TV) devices.

## Install or update

Every push to `main` builds a new APK. The latest one is always at:

    https://github.com/sanjwad/tvplayer/releases/latest/download/tvplayer.apk

1. Install **Downloader** (by AFTVnews) from the device's app store.
2. Allow Downloader to install unknown apps:
   - Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → Downloader → On
     (if Developer options is hidden: My Fire TV → About → click the device name 7 times).
   - Shield / Onn (Google TV): Settings → Apps → Security & restrictions → Unknown sources → Downloader → On
     (on Google TV you may first need Settings → System → About → click *Android TV OS build* 7 times).
3. Open Downloader, type the address above, and install.
4. Open **TV Player**, enter your provider's portal URL and the MAC address registered with them.

Updates install over the top and keep your settings.

## What's in v0.1

- Portal login with MAC address (handshake + profile, automatic re-login)
- Live TV by category with logos; up/down or channel +/- to change channel while watching
- Movies and series browsing with posters, descriptions, seasons and episodes
- Playback with Media3/ExoPlayer (TS and HLS)

## Planned

- TMDB: episode descriptions, cast, backdrops, "Trending now" rows matched to your portal
- TV guide (EPG), favourites, search, continue watching
