#!/usr/bin/env bash
set -euo pipefail

BASE_SCRIPT=".github/scripts/build-testoptimize.sh"
RUNTIME_SCRIPT="${RUNNER_TEMP:?RUNNER_TEMP is required}/build-test1-runtime.sh"

test -s "$BASE_SCRIPT"
cp "$BASE_SCRIPT" "$RUNTIME_SCRIPT"

python3 - "$RUNTIME_SCRIPT" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
text = p.read_text()

anchor = "python3 .github/scripts/apply-testoptimize.py\n"
if text.count(anchor) != 1:
    raise SystemExit(f"TEST1: expected one apply-testoptimize anchor, found {text.count(anchor)}")
text = text.replace(anchor, anchor + "python3 .github/scripts/apply-test1-syncgate.py\n", 1)

source_gate_anchor = "python3 .github/scripts/apply-test1-syncgate.py\n"
source_guards = r'''grep -Fq 'TEST1 initial timeline gate armed' "$CONTROLLER"
grep -Fq 'initialStartPositionNanos: Long = -1L' "$PLAYER"
grep -Fq 'releaseInitialTimelineLoad(positionNanos: Long)' platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt
grep -Fq 'if (screen.releaseInitialTimelineLoad(projectedStartNanos)) return' platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/TimelineFollower.kt
'''
if text.count(source_gate_anchor) != 1:
    raise SystemExit("TEST1: sync-gate apply anchor missing after insertion")
text = text.replace(source_gate_anchor, source_gate_anchor + source_guards, 1)

old_version = "1.9.5-kirazium-android-stallfix1"
new_version = "1.9.5-kirazium-android-syncgate1"
version_hits = text.count(old_version)
if version_hits < 3:
    raise SystemExit(f"TEST1: suspicious stallfix1 version anchor count: {version_hits}")
text = text.replace(old_version, new_version)

if text.count("testoptimize-build") != 1:
    raise SystemExit("TEST1: expected exactly one testoptimize-build output ref")
text = text.replace("testoptimize-build", "test1-build", 1)

if "source_branch=testoptimize" not in text or "workflow=testoptimize" not in text:
    raise SystemExit("TEST1: BUILD_INFO source/workflow anchors missing")
text = text.replace("source_branch=testoptimize", "source_branch=test1")
text = text.replace("workflow=testoptimize", "workflow=test1")
text = text.replace(
    "optimizations=duplicate-load-dedupe,android-init-cap,audio-warm-pool-off,bounded-discard-workers,decoder-progress-watchdog,pre-roll-safe-startup",
    "optimizations=duplicate-load-dedupe,android-init-cap,audio-warm-pool-off,bounded-discard-workers,decoder-progress-watchdog,pre-roll-safe-startup,initial-authoritative-sync-gate",
)
text = text.replace(
    "commit -m 'build: testoptimize Android software JAR'",
    "commit -m 'build: test1 authoritative sync-gate Android software JAR'",
)

jar_copy_anchor = 'cp "$JAR" out/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-syncgate1.jar\n'
compiled_guards = r'''mkdir -p verify-jar-test1
javap -classpath "$JAR" -p com.dreamdisplays.platform.client.displays.DisplayMediaController > verify-jar-test1/DisplayMediaController.txt
javap -classpath "$JAR" -c -p com.dreamdisplays.platform.client.displays.TimelineFollower > verify-jar-test1/TimelineFollower.txt
javap -classpath "$JAR" -p com.dreamdisplays.platform.client.displays.DisplayScreen > verify-jar-test1/DisplayScreen.txt
grep -F 'releaseInitialTimeline' verify-jar-test1/DisplayMediaController.txt >/dev/null
grep -F 'releaseInitialTimelineLoad' verify-jar-test1/DisplayScreen.txt >/dev/null
grep -F 'releaseInitialTimelineLoad' verify-jar-test1/TimelineFollower.txt >/dev/null
'''
if text.count(jar_copy_anchor) != 1:
    raise SystemExit(f"TEST1: built-JAR copy anchor mismatch: {text.count(jar_copy_anchor)}")
text = text.replace(jar_copy_anchor, compiled_guards + jar_copy_anchor, 1)

p.write_text(text)
PY

chmod +x "$RUNTIME_SCRIPT"

echo "TEST1: using production stallfix1 build chain plus isolated authoritative-sync gate"
echo "TEST1: production source files and testoptimize scripts are not modified by this wrapper"
bash "$RUNTIME_SCRIPT"
