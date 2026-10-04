settings = {}


class Addon:
    def __init__(self, id=None):
        pass

    def getSetting(self, key):
        return settings.get(key, "")

    def setSetting(self, key, value):
        settings[key] = value

    def getAddonInfo(self, key):
        return {"name": "OpenBased", "icon": "icon.png", "id": "plugin.video.openbased"}.get(key, "")

    def openSettings(self):
        settings["_opened"] = True
