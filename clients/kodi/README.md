# OpenBased for Kodi

A Kodi video add-on (`plugin.video.openbased`) for Kodi 19 "Matrix" and newer, on any platform
(LibreELEC, Raspberry Pi OS, Android TV, Windows, macOS, Linux).

- Browse every library you can see on the server: movies, shows (grouped by series and season) and
  other videos, with posters, backdrops, plots and genres
- Continue watching and search
- Plays the original file directly (Kodi handles practically every format), with seeking
- Resume points and watched status are synced with the server, so they are shared with the web UI and
  other clients; "Mark as watched/unwatched" in the context menu
- Linked to your account with a short code — no typing of passwords or tokens on the TV

## Install

1. Build the zip (on the machine with this repository):

   ```sh
   clients/kodi/build.sh        # creates clients/kodi/dist/plugin.video.openbased-0.1.0.zip
   ```

2. Get the zip to the Kodi device, e.g. a USB stick or a network share. If Kodi runs on the same
   machine as the repository, it can open the file directly.
3. In Kodi: **Settings → System → Add-ons**, enable **Unknown sources**.
4. **Settings → Add-ons → Install from zip file**, and pick the zip.

## Link it to your account

1. Open **Add-ons → Video add-ons → OpenBased** and choose **Link with OpenBased**.
2. Enter the server address once, exactly as you open OpenBased in a browser (e.g. `http://bigbox:8080`).
3. Kodi shows a code like `BKMQ-TRWZ`. On your phone or computer, open the address Kodi shows
   (`http://bigbox:8080/#/link`), sign in, enter the code and approve.

Kodi receives a personal access token that can only browse, play and update watch progress. It appears
as "Kodi on <device name>" on the web UI's **API tokens** page, where you can revoke it. The add-on's
settings also accept a manually created `ob_pat_…` token.

## Development

`tests/test_addon.py` runs the add-on unmodified against a live server, with stand-in Kodi modules in
`tests/fake_kodi` that record what it does (including both the Kodi 19 and Kodi 20+ metadata APIs):

```sh
OPENBASED_URL=http://localhost:8080 OPENBASED_ADMIN_TOKEN=<token with the profile scope> \
    python3 clients/kodi/tests/test_addon.py
```

The server needs a library named "Movies" (type MOVIES) containing `Interstellar (2014).mkv` and one
named "Shows" (type TV) containing `The Expanse` episodes S01E01, S01E02 and S02E01.
