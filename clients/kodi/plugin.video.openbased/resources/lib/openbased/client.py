"""Minimal OpenBased API client using only the Python standard library (Kodi 19+ ships Python 3.8)."""

import json
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "OpenBased-Kodi/0.1.0"


class ApiError(Exception):
    def __init__(self, status, error, message):
        super().__init__(message)
        self.status = status
        self.error = error


class Client:
    def __init__(self, server_url, token=None, timeout=20):
        self.server_url = normalize_server_url(server_url)
        self.token = token
        self.timeout = timeout

    # -- transport

    def request(self, method, path, body=None, query=None, auth=True):
        url = self.server_url + "/api/v1" + path
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        data = None
        headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if auth and self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                return json.loads(raw.decode("utf-8")) if raw else None
        except urllib.error.HTTPError as e:
            try:
                payload = json.loads(e.read().decode("utf-8"))
            except ValueError:
                payload = {}
            raise ApiError(e.code, payload.get("error", "HTTP_%d" % e.code),
                           payload.get("message", "The server answered with HTTP %d." % e.code))
        except (urllib.error.URLError, OSError) as e:
            reason = getattr(e, "reason", e)
            raise ApiError(0, "UNREACHABLE", "Cannot reach %s (%s)." % (self.server_url, reason))

    # -- device linking

    def start_link(self, device_name):
        return self.request("POST", "/device-links", {"name": device_name}, auth=False)

    def poll_link(self, device_code):
        return self.request("POST", "/device-links/token", {"deviceCode": device_code}, auth=False)

    # -- browsing

    def libraries(self):
        return self.request("GET", "/libraries")["items"]

    def media_page(self, library_id=None, page=0, page_size=200, sort=None, query=None):
        return self.request("GET", "/media", query={
            "library": library_id, "page": page, "pageSize": page_size, "sort": sort, "query": query})

    def all_media(self, library_id):
        items, page = [], 0
        while True:
            result = self.media_page(library_id, page=page)
            items.extend(result["items"])
            if (page + 1) * result["pageSize"] >= result["total"]:
                return items
            page += 1

    def media(self, media_id):
        return self.request("GET", "/media/" + quote(media_id))

    def search(self, q):
        return self.request("GET", "/search", query={"q": q, "pageSize": 200})["results"]

    def continue_watching(self):
        return self.request("GET", "/continue-watching", query={"pageSize": 50})["items"]

    def history(self, page_size=200):
        return self.request("GET", "/history", query={"pageSize": page_size})["items"]

    # -- progress (seconds)

    def progress(self, media_id):
        try:
            return self.request("GET", "/media/%s/progress" % quote(media_id))
        except ApiError as e:
            if e.status == 404:
                return None
            raise

    def save_progress(self, media_id, position, duration=None, completed=None):
        body = {"position": max(0.0, float(position))}
        if duration:
            body["duration"] = float(duration)
            body["position"] = min(body["position"], body["duration"])
        if completed is not None:
            body["completed"] = bool(completed)
        return self.request("PUT", "/media/%s/progress" % quote(media_id), body)

    # -- URLs Kodi fetches itself

    def kodi_url(self, path):
        """An absolute URL with the bearer token attached in Kodi's '|Header=value' syntax."""
        url = self.server_url + path
        if self.token:
            url += "|Authorization=" + quote("Bearer " + self.token) + "&User-Agent=" + quote(USER_AGENT)
        return url

    def stream_url(self, media_id):
        return self.kodi_url("/api/v1/media/%s/stream" % quote(media_id))


def normalize_server_url(url):
    url = (url or "").strip().rstrip("/")
    if url and "://" not in url:
        url = "http://" + url
    return url


def quote(value):
    return urllib.parse.quote(str(value), safe="")
