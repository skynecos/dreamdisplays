"""Reuse the exact upstream native payload without Windows case-folding license names.

Usage: python tools/restore_release_natives.py upstream-fabric.jar built-fabric.jar
Only dreamdisplays-natives/ entries are replaced; all application bytes stay intact.
"""
import hashlib
import os
from pathlib import Path
import sys
import zipfile

source, target = map(Path, sys.argv[1:3])
temporary = target.with_suffix(".native-tmp.jar")
prefix = "dreamdisplays-natives/"
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target) as built:
    payload = [(entry, original.read(entry)) for entry in original.infolist() if entry.filename.startswith(prefix)]
    if not payload:
        raise SystemExit("The upstream archive has no native payload.")
    unchanged = {entry.filename: hashlib.sha256(built.read(entry)).digest()
                 for entry in built.infolist() if not entry.filename.startswith(prefix)}
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as output:
        for entry in built.infolist():
            if not entry.filename.startswith(prefix):
                output.writestr(entry, built.read(entry))
        for entry, data in payload:
            output.writestr(entry, data)
with zipfile.ZipFile(temporary) as result:
    assert result.testzip() is None
    for name, digest in unchanged.items():
        assert hashlib.sha256(result.read(name)).digest() == digest, name
    for entry, data in payload:
        assert result.read(entry.filename) == data, entry.filename
os.replace(temporary, target)
print(f"Preserved {len(unchanged)} application entries; restored {len(payload)} upstream native entries.")
