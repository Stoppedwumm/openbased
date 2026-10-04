SORT_METHOD_UNSORTED, SORT_METHOD_LABEL_IGNORE_THE, SORT_METHOD_VIDEO_YEAR, SORT_METHOD_DATEADDED, SORT_METHOD_EPISODE = range(5)
directory = []
state = {}


def reset():
    directory.clear()
    state.clear()


def addDirectoryItem(handle, url, listitem, isFolder=False, totalItems=0):
    directory.append({"url": url, "item": listitem, "folder": isFolder})
    return True


def endOfDirectory(handle, succeeded=True, updateListing=False, cacheToDisc=True):
    state["ended"] = succeeded


def setContent(handle, content):
    state["content"] = content


def addSortMethod(handle, sortMethod, label2Mask=""):
    state.setdefault("sort", []).append(sortMethod)


def setResolvedUrl(handle, succeeded, listitem):
    state["resolved"] = (succeeded, listitem)
