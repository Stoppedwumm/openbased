#!/usr/bin/env bash
# Builds an installable Kodi add-on zip: dist/plugin.video.openbased-<version>.zip
set -euo pipefail
cd "$(dirname "$0")"
ID=plugin.video.openbased
VERSION="$(sed -n 's/.*<addon[^>]* version="\([^"]*\)".*/\1/p' "$ID/addon.xml" | head -n1)"
mkdir -p dist
OUT="dist/$ID-$VERSION.zip"
rm -f "$OUT"
# Kodi expects the add-on folder at the top level of the zip.
python3 - "$ID" "$OUT" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if d != "__pycache__"]
        for name in sorted(files):
            if not name.endswith(".pyc"):
                path = os.path.join(root, name)
                z.write(path, path)
PY
echo "$OUT"
