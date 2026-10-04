"""End-to-end test of the Kodi add-on against a running OpenBased server, using stand-in Kodi modules.

    OPENBASED_URL=http://localhost:8080 OPENBASED_ADMIN_TOKEN=<token with profile scope> \
        python3 clients/kodi/tests/test_addon.py

The server needs a MOVIES library containing "Interstellar (2014)" and a TV library containing
"The Expanse" episodes S01E01, S01E02 and S02E01 (see clients/kodi/README.md).
"""

import json
import os
import re
import sys
import unittest
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "fake_kodi"))
sys.path.insert(0, os.path.join(HERE, "..", "plugin.video.openbased"))

import xbmc  # noqa: E402
import xbmcaddon  # noqa: E402
import xbmcgui  # noqa: E402
import xbmcplugin  # noqa: E402

from resources.lib.openbased import plugin, service  # noqa: E402

SERVER = os.environ.get("OPENBASED_URL", "http://localhost:8080")
ADMIN_TOKEN = os.environ["OPENBASED_ADMIN_TOKEN"]
BASE = "plugin://plugin.video.openbased/"


def api(method, path, body=None, token=ADMIN_TOKEN):
    request = urllib.request.Request(SERVER + "/api/v1" + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def browse(query=""):
    xbmcplugin.reset()
    plugin.run([BASE, "1", query])
    return list(xbmcplugin.directory)


def browse_params(entry):
    return "?" + entry["url"].split("?", 1)[1]


def kodi_fetch(kodi_url, extra_headers=None):
    """Fetches a URL the way Kodi does, honouring the '|Header=value' suffix."""
    url, _, options = kodi_url.partition("|")
    headers = dict(urllib.parse.parse_qsl(options))
    headers.update(extra_headers or {})
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers)) as response:
        return response.status, response.read()


class AddonTest(unittest.TestCase):
    token = None

    @classmethod
    def setUpClass(cls):
        xbmcaddon.settings.clear()
        xbmcaddon.settings["server_url"] = SERVER
        approved = []

        def approve(message):
            code = re.search(r"[A-Z]{4}-[A-Z]{4}", message).group(0)
            if not approved:
                approved.append(code)
                api("POST", "/device-links/%s/approve" % code)

        xbmcgui.DialogProgress.on_update = approve
        plugin.run([BASE, "-1", "?action=link"])
        xbmcgui.DialogProgress.on_update = None
        cls.token = xbmcaddon.settings.get("token")

    def setUp(self):
        xbmcgui.KODI_MAJOR = 21

    def entry(self, entries, label):
        for e in entries:
            if e["item"].label == label:
                return e
        self.fail("%r not in %r" % (label, [e["item"].label for e in entries]))

    def movie(self):
        movies = browse(browse_params(self.entry(browse(), "Movies")))
        return self.entry(movies, "Interstellar (2014)")

    # ------------------------------------------------------------------ tests

    def test_linking_stored_a_device_token(self):
        self.assertTrue(self.token.startswith("ob_pat_"))
        self.assertTrue(xbmcaddon.settings.get("linked_as"))
        self.assertIn("http", xbmcgui.progress_messages[-1])
        names = [t["name"] for t in api("GET", "/tokens")["items"]]
        self.assertIn("Kodi on TestBox", names)

    def test_root_menu(self):
        labels = [e["item"].label for e in browse()]
        self.assertEqual(labels[0], "Continue watching")
        self.assertIn("Movies", labels)
        self.assertIn("Shows", labels)
        self.assertEqual(labels[-2:], ["Search", "Settings"])

    def test_movies_have_metadata_artwork_headers_and_play(self):
        entry = self.movie()
        item = entry["item"]
        self.assertFalse(entry["folder"])
        self.assertEqual(item.props.get("IsPlayable"), "true")
        self.assertEqual(item.video()["mediatype"], "movie")
        self.assertEqual(item.video()["year"], 2014)
        self.assertEqual(xbmcplugin.state["content"], "movies")

        xbmcplugin.reset()
        plugin.run([BASE, "1", browse_params(entry)])
        succeeded, resolved = xbmcplugin.state["resolved"]
        self.assertTrue(succeeded)
        self.assertIn("|Authorization=Bearer%20ob_pat_", resolved.path)
        status, body = kodi_fetch(resolved.path, {"Range": "bytes=0-99"})
        self.assertEqual(status, 206)
        self.assertEqual(len(body), 100)

    def test_shows_are_grouped_by_series_and_season(self):
        shows = browse(browse_params(self.entry(browse(), "Shows")))
        self.assertEqual(xbmcplugin.state["content"], "tvshows")
        show = self.entry(shows, "The Expanse")
        self.assertEqual(show["item"].props["TotalEpisodes"], "3")
        seasons = browse(browse_params(show))
        self.assertEqual([s["item"].label for s in seasons], ["Season 1", "Season 2"])
        episodes = browse(browse_params(seasons[0]))
        self.assertEqual(xbmcplugin.state["content"], "episodes")
        self.assertEqual([e["item"].video()["episode"] for e in episodes], [1, 2])
        self.assertEqual(episodes[0]["item"].video()["tvshowtitle"], "The Expanse")

    def test_search(self):
        xbmc.keyboard_text = "interstellar"
        results = browse("?action=search")
        self.assertEqual([e["item"].label for e in results], ["Interstellar (2014)"])

    def test_progress_service_resume_and_watched(self):
        movie = self.movie()
        media_id = urllib.parse.parse_qs(movie["url"].split("?", 1)[1])["id"][0]
        api("PUT", "/media/%s/progress" % media_id, {"position": 0, "completed": False})

        tracker = service.Tracker()
        xbmc.player_state.update(file=plugin.Client(SERVER, self.token).stream_url(media_id),
                                 time=60.0, total=180.0, playing=True)
        tracker.onAVStarted()
        self.assertEqual(tracker.media_id, media_id)
        tracker.report()
        self.assertEqual(api("GET", "/media/%s/progress" % media_id)["position"], 60.0)

        # The listing now offers to resume.
        self.assertEqual(self.movie()["item"].resume_point(), (60.0, 180.0))
        xbmcgui.KODI_MAJOR = 19
        old = self.movie()["item"]
        self.assertEqual(old.resume_point(), (60.0, 180.0))
        self.assertEqual(old.info["mediatype"], "movie")
        xbmcgui.KODI_MAJOR = 21

        # Continue watching lists it.
        self.assertIn("Interstellar (2014)", [e["item"].label for e in browse("?action=continue")])

        # Playing to the end marks it watched and refreshes the listing.
        xbmc.player_state.update(time=180.0)
        tracker.sample()
        xbmc.builtins.clear()
        tracker.onPlayBackEnded()
        self.assertTrue(api("GET", "/media/%s/progress" % media_id)["completed"])
        self.assertIn("Container.Refresh", xbmc.builtins)
        watched = self.movie()["item"]
        self.assertEqual(watched.video()["playcount"], 1)
        self.assertIsNone(watched.resume_point())
        self.assertEqual(watched.context[0][0], "Mark as unwatched")

        # Context menu: mark unwatched, then watched again.
        plugin.run([BASE, "-1", "?" + watched.context[0][1].split("?", 1)[1].rstrip(")")])
        self.assertFalse(api("GET", "/media/%s/progress" % media_id)["completed"])
        unwatched = self.movie()["item"]
        self.assertEqual(unwatched.context[0][0], "Mark as watched")
        plugin.run([BASE, "-1", "?" + unwatched.context[0][1].split("?", 1)[1].rstrip(")")])
        self.assertTrue(api("GET", "/media/%s/progress" % media_id)["completed"])
        xbmc.player_state.update(playing=False)

    def test_zz_revoked_token_and_sign_out(self):
        # Revoking the device in the web UI makes the add-on ask to link again.
        token_id = [t["id"] for t in api("GET", "/tokens")["items"] if t["name"] == "Kodi on TestBox"][0]
        api("DELETE", "/tokens/" + token_id)
        xbmcgui.notifications.clear()
        browse()
        self.assertIn("Link this device again", xbmcgui.notifications[-1])
        self.assertFalse(xbmcplugin.state["ended"])

        plugin.run([BASE, "-1", "?action=signout"])
        self.assertEqual(xbmcaddon.settings["token"], "")
        self.assertEqual([e["item"].label for e in browse()], ["Link with OpenBased", "Settings"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
