"""Background service: reports playback position of OpenBased media back to the server."""

import re

import xbmc
import xbmcaddon

from .client import ApiError, Client

STREAM_URL = re.compile(r"/api/v1/media/([^/?|]+)/stream")
REPORT_EVERY = 15  # seconds between progress updates while playing


class Tracker(xbmc.Player):
    """Follows the player. Kodi calls the on* methods from its own thread; the main loop polls."""

    def __init__(self):
        super().__init__()
        self.media_id = None
        self.position = 0.0
        self.duration = 0.0

    def onAVStarted(self):
        self.media_id = None
        try:
            playing = self.getPlayingFile()
        except RuntimeError:
            return
        match = STREAM_URL.search(playing or "")
        if match:
            self.media_id = match.group(1)
            self.sample()
            xbmc.log("[OpenBased] tracking %s" % self.media_id, xbmc.LOGINFO)

    def sample(self):
        """Remembers the current position; the player cannot be queried after playback stops."""
        if self.media_id and self.isPlaying():
            try:
                self.position = self.getTime()
                self.duration = self.getTotalTime() or self.duration
            except RuntimeError:
                pass

    def onPlayBackPaused(self):
        self.report()

    def onPlayBackSeek(self, time, seek_offset):
        self.sample()

    def onPlayBackStopped(self):
        self.finish(ended=False)

    def onPlayBackEnded(self):
        self.finish(ended=True)

    def onPlayBackError(self):
        self.media_id = None

    def finish(self, ended):
        if self.media_id:
            self.report(completed=True if ended else None)
            self.media_id = None
            # Refresh resume points and watched marks in the open listing.
            xbmc.executebuiltin("Container.Refresh")

    def report(self, completed=None):
        if not self.media_id:
            return
        self.sample()
        addon = xbmcaddon.Addon()
        client = Client(addon.getSetting("server_url"), addon.getSetting("token"))
        if not client.server_url or not client.token:
            return
        position = self.duration if completed else self.position
        try:
            # Without an explicit flag the server marks media completed once 95% is reached.
            client.save_progress(self.media_id, position, self.duration or None, completed)
        except ApiError as e:
            xbmc.log("[OpenBased] could not save progress: %s" % e, xbmc.LOGWARNING)


def run():
    monitor = xbmc.Monitor()
    tracker = Tracker()
    elapsed = 0
    while not monitor.abortRequested():
        if monitor.waitForAbort(1):
            break
        if tracker.media_id and tracker.isPlaying():
            tracker.sample()
            elapsed += 1
            if elapsed >= REPORT_EVERY:
                elapsed = 0
                tracker.report()
        else:
            elapsed = 0
