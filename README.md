# Stremio Intro Skip

Auto-skips intros and recaps in Stremio on Android TV, for TV shows, movies and anime.

I made this for my own TV. It just skips the boring bits so playback keeps going.

## Features

* Exact skips from the video file own chapter markers (Opening, OP, Intro, Opening Credits, Main Titles and similar names).
* IntroDB timings for TV shows and movies.
* AniSkip timings for anime.
* Online timings snap to chapter edges when both exist.
* Resuming inside an intro still skips.
* Seeking back on purpose shows a "Skip intro" button instead of skipping again.
* Remote keys are never blocked. OK on the button skips, everything else works as normal.
* Settings in Stremio: on/off, anime on/off, Auto or Manual, recaps on/off.
* Hide spoilers: optional switch in Settings > Interface that hides thumbnails and synopses for unwatched episodes.
* External player progress: Stremio stays alive while an external player is open, so progress saves for Vimu, MX Player, mpv, VLC. The player starts at the saved position.
* Avoid SDH subtitles: picks full dialogue subtitles over hearing-impaired tracks, and strips sound notes when no other track remains. Switches in Settings > Playback.
* Vimu: intro skip with Skip button and Auto/Manual settings, crash fixes, and progress result.

Tested on Stremio 1.6.12 (com.stremio.one, Android TV).
Tested on Vimu 12.00. If your copy doesn't patch cleanly, open an issue.

## Install with Morphe Manager

1. Add this repo as a patch source in Morphe Manager:
   https://morphe.software/add-source?github=tikkamasalla/stremio-intro-skip
2. Pick the Stremio app, enable the "Intro Skip" patch, patch and install.
3. Optional: the "Enable debug mode" patch option opens a log window when playback starts, useful to see the detected title, season, episode and API responses.

## Known limits

* Only the Stremio 1.6.12 TV flow is tested. Other versions may not hook.
* File chapters work offline. IntroDB and AniSkip need internet. No API keys needed.
* Lookup order: file chapters, AniSkip for anime, IntroDB for shows and movies. The IMDb id comes from Stremio's own video id; if that is missing, a keyless Cinemeta name lookup is used and remembered for 60 days.
* Online timings snap to chapter edges within 15 s. Weak "Intro" chapter names only count when AniSkip or IntroDB agrees within 15 s, or when neither has anything for the episode.
* If the exact seek fails it falls back to a normal seek.

## Vimu support

The same bundle also carries an "Intro Skip (Vimu)" patch for Vimu 12.00 (net.gtvbox.videoplayer).

It skips intros and recaps the same way: file chapter markers first, then AniSkip for anime and IntroDB for shows and movies. The IntroDB step needs no API key. It uses an IMDb id from the playing item when one is present, else a keyless Cinemeta title lookup that is remembered for 60 days. Auto mode skips at once, Manual mode shows a Skip button. The Vimu Settings rows cover Skip intros, anime openings, Auto/Manual mode, and recaps. The patch also includes crash fixes from the same mod: deferred prepare for the tunneling crash on cold start, media session release on exit, seek timer cancel, and a 2 byte fix in libmediaengine.so for a native crash on rapid seeks. It also returns position and duration so Stremio can save progress.

Patch your own copy of the Vimu 12.00 APK. Copies that were already modified by someone else may carry extra checks that this patch does not remove, so test on your own copy.

## Build locally

```bash
./gradlew :patches:build
```

The bundle lands in `patches/build/libs/patches-*.mpp`. Apply it with Morphe Desktop.

<!-- PATCHES_START -->
> **[v1.0.0](https://github.com/tikkamasalla/stremio-intro-skip/releases/tag/v1.0.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 Stremio&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Intro Skip](#intro-skip) | Adds intro and recap skip controls for TV shows, movies and anime in Stremio.<br><br>Intro Skip v1.0.0<br>Repository: https://github.com/tikkamasalla/stremio-intro-skip | • Enable debug mode |

</details>

<!-- PATCHES_END -->

## Credits

Started from aniskip-stremio by AzukiSensei (https://github.com/AzukiSensei/aniskip-stremio), GPLv3. Rewritten with chapter-based skipping, IntroDB support and new skip logic.

## License

GPLv3, see [LICENSE](LICENSE).
