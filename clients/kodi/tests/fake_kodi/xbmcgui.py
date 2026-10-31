# KODI_MAJOR selects which InfoTag API ListItem exposes (19: setInfo only, 20+: InfoTagVideo setters).
KODI_MAJOR = 21
notifications = []
dialogs = []
progress_messages = []


class InfoTagVideo:
    def __init__(self):
        self.values = {}
        self.resume = None

    def __getattr__(self, name):
        # Setters only exist on Kodi 20+; Kodi 19's InfoTagVideo is read-only.
        if name == "setResumePoint" and KODI_MAJOR >= 20:
            return lambda time, total=0.0: setattr(self, "resume", (time, total))
        if name.startswith("set") and KODI_MAJOR >= 20:
            return lambda value: self.values.__setitem__(name[3:].lower(), value)
        raise AttributeError(name)


class ListItem:
    def __init__(self, label="", path=""):
        self.label = label
        self.path = path
        self.art = {}
        self.props = {}
        self.info = {}
        self.context = []
        self.tag = InfoTagVideo()

    def setArt(self, art):
        self.art.update(art)

    def setProperty(self, key, value):
        self.props[key] = value

    def setInfo(self, type, info):
        self.info.update(info)

    def getVideoInfoTag(self):
        return self.tag

    def setPath(self, path):
        self.path = path

    def addContextMenuItems(self, items):
        self.context.extend(items)

    # Unified view for assertions regardless of the Kodi version simulated.
    def video(self):
        return dict(self.info, **self.tag.values)

    def resume_point(self):
        if self.tag.resume:
            return self.tag.resume
        if "ResumeTime" in self.props:
            return (float(self.props["ResumeTime"]), float(self.props["TotalTime"]))
        return None


class Dialog:
    answers = {"yesno": True, "input": ""}

    def notification(self, heading, message, icon="", time=0):
        notifications.append(message)

    def ok(self, heading, message):
        dialogs.append(message)
        return True

    def yesno(self, heading, message, *args, **kwargs):
        return Dialog.answers["yesno"]

    def input(self, heading, *args, **kwargs):
        return Dialog.answers["input"]


class DialogProgress:
    on_update = None

    def create(self, heading, message=""):
        progress_messages.append(message)

    def update(self, percent, message=""):
        if DialogProgress.on_update:
            DialogProgress.on_update(message)

    def iscanceled(self):
        return False

    def close(self):
        pass
