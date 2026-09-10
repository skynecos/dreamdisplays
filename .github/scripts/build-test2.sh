#!/usr/bin/env bash
set -euo pipefail

BASE_SCRIPT=".github/scripts/build-testoptimize.sh"
RUNTIME_SCRIPT="${RUNNER_TEMP:?RUNNER_TEMP is required}/build-test2-runtime.sh"

test -s "$BASE_SCRIPT"
cp "$BASE_SCRIPT" "$RUNTIME_SCRIPT"

python3 - "$RUNTIME_SCRIPT" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
text = p.read_text()

anchor = "python3 .github/scripts/apply-testoptimize.py\n"
if text.count(anchor) != 1:
    raise SystemExit(f"TEST2: expected one apply-testoptimize anchor, found {text.count(anchor)}")
text = text.replace(
    anchor,
    anchor
    + "python3 .github/scripts/apply-test1-syncgate.py\n"
    + "python3 .github/scripts/apply-test2-mobile720p30.py\n",
    1,
)

source_gate_anchor = "python3 .github/scripts/apply-test2-mobile720p30.py\n"
source_guards = r'''grep -Fq 'TEST1 initial timeline gate armed' "$CONTROLLER"
grep -Fq 'initialStartPositionNanos: Long = -1L' "$PLAYER"
grep -Fq 'releaseInitialTimelineLoad(positionNanos: Long)' platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt
grep -Fq 'if (screen.releaseInitialTimelineLoad(projectedStartNanos)) return' platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/TimelineFollower.kt
grep -Fq 'private val mobile720p30: Boolean' media/source/src/main/kotlin/com/dreamdisplays/media/source/DefaultStreamSelector.kt
grep -Fq 'private val mobileMaxHeight = 720' media/source/src/main/kotlin/com/dreamdisplays/media/source/DefaultStreamSelector.kt
grep -Fq 'private val mobileMaxFps = 30.5' media/source/src/main/kotlin/com/dreamdisplays/media/source/DefaultStreamSelector.kt
grep -Fq 'TEST2 mobile profile selected={}p fps={} target={}p constrained={}' media/source/src/main/kotlin/com/dreamdisplays/media/source/DefaultStreamSelector.kt
'''
if text.count(source_gate_anchor) != 1:
    raise SystemExit("TEST2: mobile profile apply anchor missing after insertion")
text = text.replace(source_gate_anchor, source_gate_anchor + source_guards, 1)

# Sync-gate changes duplicate-load handling; align the original stallfix regression guard exactly
# as TEST1 does, while leaving every other proven production guard untouched.
old_dedupe_guard = '''grep -Fq 'current != null && screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored' "$CONTROLLER"\n'''
new_dedupe_guard = '''grep -Fq 'val pending = pendingInitialLoad' "$CONTROLLER"\ngrep -Fq '(current != null || pending != null) &&' "$CONTROLLER"\ngrep -Fq 'screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored' "$CONTROLLER"\n'''
if text.count(old_dedupe_guard) != 1:
    raise SystemExit(f"TEST2: expected one production duplicate-load guard, found {text.count(old_dedupe_guard)}")
text = text.replace(old_dedupe_guard, new_dedupe_guard, 1)

old_version = "1.9.5-kirazium-android-stallfix1"
new_version = "1.9.5-kirazium-android-syncgate1-mobile720p30"
version_hits = text.count(old_version)
if version_hits < 3:
    raise SystemExit(f"TEST2: suspicious stallfix1 version anchor count: {version_hits}")
text = text.replace(old_version, new_version)

output_branch_hits = text.count("testoptimize-build")
if output_branch_hits != 2:
    raise SystemExit(f"TEST2: expected exactly two testoptimize-build output refs, found {output_branch_hits}")
text = text.replace("testoptimize-build", "test2-build")

if "source_branch=testoptimize" not in text or "workflow=testoptimize" not in text:
    raise SystemExit("TEST2: BUILD_INFO source/workflow anchors missing")
text = text.replace("source_branch=testoptimize", "source_branch=test2")
text = text.replace("workflow=testoptimize", "workflow=test2")
text = text.replace(
    "optimizations=duplicate-load-dedupe,android-init-cap,audio-warm-pool-off,bounded-discard-workers,decoder-progress-watchdog,pre-roll-safe-startup",
    "optimizations=duplicate-load-dedupe,android-init-cap,audio-warm-pool-off,bounded-discard-workers,decoder-progress-watchdog,pre-roll-safe-startup,initial-authoritative-sync-gate,mobile-720p30-stream-selection",
)
text = text.replace(
    "commit -m 'build: testoptimize Android software JAR'",
    "commit -m 'build: test2 sync-gate plus Android 720p30 stream profile JAR'",
)

jar_copy_anchor = 'cp "$JAR" out/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-syncgate1-mobile720p30.jar\n'
compiled_guards = r'''mkdir -p verify-jar-test2
javap -classpath "$JAR" -p com.dreamdisplays.platform.client.displays.DisplayMediaController > verify-jar-test2/DisplayMediaController.txt
javap -classpath "$JAR" -c -p com.dreamdisplays.platform.client.displays.TimelineFollower > verify-jar-test2/TimelineFollower.txt
javap -classpath "$JAR" -p com.dreamdisplays.platform.client.displays.DisplayScreen > verify-jar-test2/DisplayScreen.txt
javap -classpath "$JAR" -c -p com.dreamdisplays.media.source.DefaultStreamSelector > verify-jar-test2/DefaultStreamSelector.txt
grep -F 'releaseInitialTimeline' verify-jar-test2/DisplayMediaController.txt >/dev/null
grep -F 'releaseInitialTimelineLoad' verify-jar-test2/DisplayScreen.txt >/dev/null
grep -F 'releaseInitialTimelineLoad' verify-jar-test2/TimelineFollower.txt >/dev/null
grep -F 'mobile720p30' verify-jar-test2/DefaultStreamSelector.txt >/dev/null
grep -F 'TEST2 mobile profile selected=' verify-jar-test2/DefaultStreamSelector.txt >/dev/null
'''
if text.count(jar_copy_anchor) != 1:
    raise SystemExit(f"TEST2: built-JAR copy anchor mismatch: {text.count(jar_copy_anchor)}")
text = text.replace(jar_copy_anchor, compiled_guards + jar_copy_anchor, 1)

p.write_text(text)
PY

chmod +x "$RUNTIME_SCRIPT"

echo "TEST2: using production stallfix1 chain + proven TEST1 sync-gate + isolated Android 720p30 stream profile"
echo "TEST2: main, TEST1 source, and production build scripts remain untouched"
bash "$RUNTIME_SCRIPT"
