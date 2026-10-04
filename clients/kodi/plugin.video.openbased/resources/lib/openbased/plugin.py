"""Directory listings, playback and device linking for the OpenBased Kodi add-on."""

import sys
import urllib.parse

import xbmc
import xbmcaddon
import xbmcgui
import xbmcplugin

from .client import ApiError, Client, normalize_server_url

ADDON = xbmcaddon.Addon()
ADDON_NAME = ADDON.getAddonInfo("name")
ICON = ADDON.getAddonInfo("icon")


class Plugin:
    def __init__(self, argv):
        self.base_url = argv[0]
        self.handle = int(argv[1])
        self.params = dict(urllib.parse.parse_qsl(argv[2].lstrip("?"))) if len(argv) > 2 else {}

    # ------------------------------------------------------------------ helpers

    def url(self, **params):
        return self.base_url + "?" + urllib.parse.urlencode(params)

    def client(self):
        return Client(ADDON.getSetting("server_url"), ADDON.getSetting("token"))

    def is_linked(self):
        return bool(ADDON.getSetting("server_url") and ADDON.getSetting("token"))

    def add_folder(self, label, url, art=None, plot=None):
        item = xbmcgui.ListItem(label)
        item.setArt(art or {"icon": "DefaultFolder.png"})
        if plot:
            set_video_info(item, {"title": label, "plot": plot})
        xbmcplugin.addDirectoryItem(self.handle, url, item, isFolder=True)

    def end(self, content=None, sort_methods=(), cache=True):
        if content:
            xbmcplugin.setContent(self.handle, content)
        for method in sort_methods:
            xbmcplugin.addSortMethod(self.handle, method)
        xbmcplugin.endOfDirectory(self.handle, cacheToDisc=cache)

    # ------------------------------------------------------------------ routing

    def run(self):
        action = self.params.get("action", "root")
        handler = getattr(self, "action_" + action, None)
        if handler is None:
            xbmc.log("[OpenBased] unknown action %s" % action, xbmc.LOGWARNING)
            return
        try:
            handler()
        except ApiError as e:
            xbmc.log("[OpenBased] %s: %s" % (e.error, e), xbmc.LOGERROR)
            if e.status == 401:
                notify("Access was revoked or has expired. Link this device again.")
            else:
                notify(str(e))
            if self.handle >= 0 and action not in ("play", "link", "signout", "watched", "unwatched"):
                xbmcplugin.endOfDirectory(self.handle, succeeded=False)
            elif action == "play":
                xbmcplugin.setResolvedUrl(self.handle, False, xbmcgui.ListItem())

    # ------------------------------------------------------------------ menus

    def action_root(self):
        if not self.is_linked():
            self.add_folder("Link with OpenBased", self.url(action="link"),
                            plot="Show a code to enter in the OpenBased web UI.")
            self.add_folder("Settings", self.url(action="settings"))
            self.end(cache=False)
            return
        client = self.client()
        self.add_folder("Continue watching", self.url(action="continue"), {"icon": "DefaultInProgressShows.png"})
        for library in client.libraries():
            icon = {"MOVIES": "DefaultMovies.png", "TV": "DefaultTVShows.png",
                    "MUSIC": "DefaultMusicSongs.png"}.get(library["type"], "DefaultFolder.png")
            self.add_folder(library["name"], self.url(action="library", id=library["id"], type=library["type"]),
                            {"icon": icon})
        self.add_folder("Search", self.url(action="search"), {"icon": "DefaultAddonsSearch.png"})
        self.add_folder("Settings", self.url(action="settings"), {"icon": "DefaultAddonService.png"})
        self.end(cache=False)

    def action_settings(self):
        ADDON.openSettings()

    def action_library(self):
        client = self.client()
        items = client.all_media(self.params["id"])
        if self.params.get("type") == "TV" or any(i.get("type") == "EPISODE" for i in items):
            self.list_shows(items)
        else:
            self.list_media(client, items, content=content_for(items))

    def list_shows(self, items):
        shows = {}
        for item in items:
            key = item.get("seriesTitle") or item["title"]
            shows.setdefault(key, []).append(item)
        client = self.client()
        for title in sorted(shows, key=sort_key):
            episodes = shows[title]
            art = artwork(client, next((e for e in episodes if e.get("poster")), episodes[0]))
            item = xbmcgui.ListItem(title)
            item.setArt(art)
            watched = sum(1 for e in episodes if (e.get("progress") or {}).get("completed"))
            set_video_info(item, {"title": title, "tvshowtitle": title, "mediatype": "tvshow"})
            item.setProperty("TotalEpisodes", str(len(episodes)))
            item.setProperty("WatchedEpisodes", str(watched))
            item.setProperty("UnWatchedEpisodes", str(len(episodes) - watched))
            url = self.url(action="show", library=self.params["id"], title=title)
            xbmcplugin.addDirectoryItem(self.handle, url, item, isFolder=True)
        self.end("tvshows", (xbmcplugin.SORT_METHOD_LABEL_IGNORE_THE,))

    def action_show(self):
        client = self.client()
        title = self.params["title"]
        episodes = [i for i in client.all_media(self.params["library"])
                    if (i.get("seriesTitle") or i["title"]) == title]
        seasons = sorted({e.get("seasonNumber") or 0 for e in episodes})
        if len(seasons) > 1:
            for season in seasons:
                label = "Specials" if season == 0 else "Season %d" % season
                item = xbmcgui.ListItem(label)
                in_season = [e for e in episodes if (e.get("seasonNumber") or 0) == season]
                item.setArt(artwork(client, in_season[0]))
                set_video_info(item, {"title": label, "tvshowtitle": title, "season": season, "mediatype": "season"})
                url = self.url(action="season", library=self.params["library"], title=title, season=season)
                xbmcplugin.addDirectoryItem(self.handle, url, item, isFolder=True)
            self.end("seasons", (xbmcplugin.SORT_METHOD_UNSORTED,))
        else:
            self.list_media(client, episodes, content="episodes")

    def action_season(self):
        client = self.client()
        season = int(self.params["season"])
        episodes = [i for i in client.all_media(self.params["library"])
                    if (i.get("seriesTitle") or i["title"]) == self.params["title"]
                    and (i.get("seasonNumber") or 0) == season]
        self.list_media(client, episodes, content="episodes")

    def action_continue(self):
        client = self.client()
        entries = client.continue_watching()
        items = []
        for entry in entries:
            try:
                items.append(client.media(entry["mediaId"]))
            except ApiError:
                continue
        self.list_media(client, items, content="videos", sort=False, cache=False)

    def action_search(self):
        query = self.params.get("q")
        if not query:
            keyboard = xbmc.Keyboard("", "Search OpenBased")
            keyboard.doModal()
            if not keyboard.isConfirmed() or not keyboard.getText().strip():
                xbmcplugin.endOfDirectory(self.handle, succeeded=False)
                return
            query = keyboard.getText().strip()
        client = self.client()
        self.list_media(client, client.search(query), content="videos", sort=False)

    def list_media(self, client, items, content="videos", sort=True, cache=False):
        if content == "episodes":
            items = sorted(items, key=lambda i: (i.get("seasonNumber") or 0, i.get("episodeNumber") or 0, i["title"]))
        for media in items:
            item = media_list_item(client, media)
            url = self.url(action="play", id=media["id"])
            item.addContextMenuItems(watched_menu(self, media))
            xbmcplugin.addDirectoryItem(self.handle, url, item, isFolder=False)
        methods = (xbmcplugin.SORT_METHOD_EPISODE,) if content == "episodes" else (
            (xbmcplugin.SORT_METHOD_LABEL_IGNORE_THE, xbmcplugin.SORT_METHOD_VIDEO_YEAR,
             xbmcplugin.SORT_METHOD_DATEADDED) if sort else (xbmcplugin.SORT_METHOD_UNSORTED,))
        self.end(content, methods, cache=cache)

    # ------------------------------------------------------------------ playback

    def action_play(self):
        client = self.client()
        media = client.media(self.params["id"])
        item = media_list_item(client, media)
        item.setPath(client.stream_url(media["id"]))
        # Lets the progress service match the playing file to the media item.
        item.setProperty("openbased.mediaId", media["id"])
        xbmcplugin.setResolvedUrl(self.handle, True, item)

    def action_watched(self):
        self.client().save_progress(self.params["id"], float(self.params.get("duration") or 0),
                                    float(self.params.get("duration") or 0) or None, completed=True)
        xbmc.executebuiltin("Container.Refresh")

    def action_unwatched(self):
        self.client().save_progress(self.params["id"], 0, None, completed=False)
        xbmc.executebuiltin("Container.Refresh")

    # ------------------------------------------------------------------ account

    def action_link(self):
        server = normalize_server_url(ADDON.getSetting("server_url"))
        if not server:
            server = normalize_server_url(xbmcgui.Dialog().input("OpenBased server address (e.g. http://bigbox:8080)"))
            if not server:
                return
        client = Client(server)
        device = "Kodi on %s" % (xbmc.getInfoLabel("System.FriendlyName") or "this device")
        try:
            link = client.start_link(device)
        except ApiError as e:
            xbmcgui.Dialog().ok(ADDON_NAME, "Could not start linking: %s" % e)
            return
        progress = xbmcgui.DialogProgress()
        lines = ("On your phone or computer, open:[CR][B]%s[/B][CR]and enter the code [B]%s[/B]"
                 % (link["verificationUri"], link["userCode"]))
        progress.create(ADDON_NAME, lines)
        monitor = xbmc.Monitor()
        waited, limit, interval = 0, int(link["expiresIn"]), int(link.get("interval") or 5)
        try:
            while waited < limit:
                progress.update(int(100 * waited / limit), lines)
                if progress.iscanceled() or monitor.waitForAbort(interval):
                    return
                waited += interval
                try:
                    result = client.poll_link(link["deviceCode"])
                except ApiError as e:
                    if e.status in (403, 404):
                        xbmcgui.Dialog().ok(ADDON_NAME, str(e))
                        return
                    continue
                if result.get("status") == "APPROVED":
                    ADDON.setSetting("server_url", server)
                    ADDON.setSetting("token", result["token"])
                    ADDON.setSetting("linked_as", result.get("user") or "")
                    progress.close()
                    notify("Linked as %s" % (result.get("user") or "your account"))
                    xbmc.executebuiltin("Container.Refresh")
                    return
            xbmcgui.Dialog().ok(ADDON_NAME, "The code expired. Please try again.")
        finally:
            progress.close()

    def action_signout(self):
        if not xbmcgui.Dialog().yesno(ADDON_NAME, "Sign out of OpenBased on this device?[CR]"
                                      "To revoke its access completely, also remove its token in the "
                                      "OpenBased web UI (API tokens)."):
            return
        ADDON.setSetting("token", "")
        ADDON.setSetting("linked_as", "")
        notify("Signed out")
        xbmc.executebuiltin("Container.Refresh")


# ---------------------------------------------------------------------- list items

def media_list_item(client, media):
    item = xbmcgui.ListItem(label_for(media))
    item.setArt(artwork(client, media))
    item.setProperty("IsPlayable", "true")
    duration = seconds(media)
    mediatype = {"MOVIE": "movie", "EPISODE": "episode", "TRACK": "song"}.get(media.get("type"), "video")
    info = {
        "title": media["title"] if mediatype != "episode" else episode_title(media),
        "year": media.get("year"),
        "plot": media.get("overview"),
        "genre": media.get("genres") or [],
        "duration": duration,
        "mediatype": mediatype,
        "originaltitle": media.get("originalTitle"),
    }
    if mediatype == "episode":
        info.update({"tvshowtitle": media.get("seriesTitle"), "season": media.get("seasonNumber"),
                     "episode": media.get("episodeNumber")})
    progress = media.get("progress") or {}
    info["playcount"] = 1 if progress.get("completed") else 0
    set_video_info(item, info)
    if progress and not progress.get("completed") and progress.get("position", 0) > 0:
        set_resume_point(item, progress["position"], progress.get("duration") or duration)
    return item


def watched_menu(plugin, media):
    duration = seconds(media) or 0
    if (media.get("progress") or {}).get("completed"):
        return [("Mark as unwatched", "RunPlugin(%s)" % plugin.url(action="unwatched", id=media["id"]))]
    return [("Mark as watched", "RunPlugin(%s)" % plugin.url(action="watched", id=media["id"], duration=duration))]


def artwork(client, media):
    art = {}
    if media.get("poster"):
        poster = client.kodi_url(media["poster"])
        art.update({"poster": poster, "thumb": poster})
    if media.get("backdrop"):
        art["fanart"] = client.kodi_url(media["backdrop"])
    if not art:
        art["icon"] = "DefaultVideo.png"
    return art


def label_for(media):
    if media.get("type") == "EPISODE":
        return episode_title(media)
    if media.get("year"):
        return "%s (%s)" % (media["title"], media["year"])
    return media["title"]


def episode_title(media):
    if media.get("seasonNumber") is not None and media.get("episodeNumber") is not None:
        return "%dx%02d. %s" % (media["seasonNumber"], media["episodeNumber"], media["title"])
    return media["title"]


def seconds(media):
    if media.get("duration"):
        return int(media["duration"] // 1000)
    if media.get("runtime"):
        return int(media["runtime"]) * 60
    return None


def content_for(items):
    types = {i.get("type") for i in items}
    if types == {"MOVIE"}:
        return "movies"
    if types == {"TRACK"}:
        return "songs"
    return "videos"


def sort_key(title):
    t = title.lower()
    for article in ("the ", "a ", "an "):
        if t.startswith(article):
            return t[len(article):]
    return t


# ---------------------------------------------------------------------- Kodi version compatibility

def set_video_info(item, info):
    """Kodi 20+ uses InfoTagVideo setters; Kodi 19 only has ListItem.setInfo."""
    info = {k: v for k, v in info.items() if v not in (None, "", [])}
    tag = item.getVideoInfoTag() if hasattr(item, "getVideoInfoTag") else None
    if tag is None or not hasattr(tag, "setTitle"):
        item.setInfo("video", info)
        return
    setters = {
        "title": tag.setTitle, "year": tag.setYear, "plot": tag.setPlot, "genre": tag.setGenres,
        "duration": tag.setDuration, "mediatype": tag.setMediaType, "originaltitle": tag.setOriginalTitle,
        "tvshowtitle": tag.setTvShowTitle, "season": tag.setSeason, "episode": tag.setEpisode,
        "playcount": tag.setPlaycount,
    }
    for key, value in info.items():
        if key in setters:
            setters[key](value)


def set_resume_point(item, position, total):
    tag = item.getVideoInfoTag() if hasattr(item, "getVideoInfoTag") else None
    if tag is not None and hasattr(tag, "setResumePoint"):
        tag.setResumePoint(float(position), float(total or 0))
    else:
        item.setProperty("ResumeTime", str(position))
        item.setProperty("TotalTime", str(total or 0))


def notify(message):
    xbmcgui.Dialog().notification(ADDON_NAME, message, ICON, 5000)


def run(argv=None):
    Plugin(argv or sys.argv).run()
