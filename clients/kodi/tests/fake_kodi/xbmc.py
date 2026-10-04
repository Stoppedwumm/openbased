"""Stand-in for Kodi's xbmc module, recording calls for tests."""
import time

LOGDEBUG, LOGINFO, LOGWARNING, LOGERROR = 0, 1, 2, 3
log_lines = []
builtins = []
info_labels = {"System.FriendlyName": "TestBox"}
keyboard_text = ""
player_state = {"file": None, "time": 0.0, "total": 0.0, "playing": False}


def log(message, level=LOGDEBUG):
    log_lines.append((level, message))


def executebuiltin(command, wait=False):
    builtins.append(command)


def getInfoLabel(label):
    return info_labels.get(label, "")


class Monitor:
    def abortRequested(self):
        return False

    def waitForAbort(self, timeout=0):
        time.sleep(min(timeout, 0.2))
        return False


class Keyboard:
    def __init__(self, default="", heading=""):
        self.text = default

    def doModal(self):
        self.text = keyboard_text

    def isConfirmed(self):
        return bool(keyboard_text)

    def getText(self):
        return self.text


class Player:
    def isPlaying(self):
        return player_state["playing"]

    def getPlayingFile(self):
        return player_state["file"]

    def getTime(self):
        return player_state["time"]

    def getTotalTime(self):
        return player_state["total"]
