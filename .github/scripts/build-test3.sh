#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_REF:?GITHUB_REF is required}"

if [[ "$GITHUB_REF" != "refs/heads/test3" ]]; then
  echo "TEST3: refusing to build from $GITHUB_REF (expected refs/heads/test3)." >&2
  exit 1
fi
if [[ "$(git rev-parse HEAD)" != "$GITHUB_SHA" ]]; then
  echo "TEST3: checkout SHA does not match GITHUB_SHA." >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "TEST3: source checkout is not pristine before build." >&2
  exit 1
fi

BASE_SCRIPT=".github/scripts/build-testoptimize.sh"
RUNTIME_SCRIPT="${RUNNER_TEMP:?RUNNER_TEMP is required}/build-test3-runtime.sh"

test -s "$BASE_SCRIPT"
test -s ".github/scripts/apply-test1-syncgate.py"
test -s ".github/scripts/apply-test3-latedrop.py"
cp "$BASE_SCRIPT" "$RUNTIME_SCRIPT"

python3 - "$RUNTIME_SCRIPT" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
text = p.read_text()

anchor = "python3 .github/scripts/apply-testoptimize.py\n"
if text.count(anchor) != 1:
    raise SystemExit(f"TEST3: expected one apply-testoptimize anchor, found {text.count(anchor)}")
text = text.replace(
    anchor,
    anchor
    + "python3 .github/scripts/apply-test1-syncgate.py\n"
    + "python3 .github/scripts/apply-test3-latedrop.py\n",
    1,
)

# Verify both layers exist in the patched source before Gradle is allowed to run.
source_patch_anchor = "python3 .github/scripts/apply-test3-latedrop.py\n"
source_guards = r'''grep -Fq 'TEST1 initial timeline gate armed' "$CONTROLLER"
grep -Fq 'initialStartPositionNanos: Long = -1L' "$PLAYER"
grep -Fq 'releaseInitialTimelineLoad(positionNanos: Long)' platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt
grep -Fq 'if (screen.releaseInitialTimelineLoad(projectedStartNanos)) return' platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/TimelineFollower.kt
grep -Fq 'TEST3 smart catch-up dropped' media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt
grep -Fq 'SMART_CATCHUP_TRIGGER_NS = 160_000_000L' media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt
grep -Fq 'SMART_CATCHUP_TARGET_NS = 60_000_000L' media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt
grep -Fq 'SMART_CATCHUP_MAX_DROP_FRAMES = 12' media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt
'''
if text.count(source_patch_anchor) != 1:
    raise SystemExit("TEST3: late-drop apply anchor missing after insertion")
text = text.replace(source_patch_anchor, source_patch_anchor + source_guards, 1)

# TEST1 widens the duplicate-load guard while a player creation is pending behind the sync gate.
old_dedupe_guard = '''grep -Fq 'current != null && screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored' "$CONTROLLER"\n'''
new_dedupe_guard = '''grep -Fq 'val pending = pendingInitialLoad' "$CONTROLLER"\ngrep -Fq '(current != null || pending != null) &&' "$CONTROLLER"\ngrep -Fq 'screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored' "$CONTROLLER"\n'''
if text.count(old_dedupe_guard) != 1:
    raise SystemExit(f"TEST3: expected one production duplicate-load guard, found {text.count(old_dedupe_guard)}")
text = text.replace(old_dedupe_guard, new_dedupe_guard, 1)

old_version = "1.9.5-kirazium-android-stallfix1"
new_version = "1.9.5-kirazium-android-syncgate1-smartdrop1"
version_hits = text.count(old_version)
if version_hits < 3:
    raise SystemExit(f"TEST3: suspicious stallfix1 version anchor count: {version_hits}")
text = text.replace(old_version, new_version)

# Never overwrite TEST1 or production build-output refs.
output_branch_hits = text.count("testoptimize-build")
if output_branch_hits != 2:
    raise SystemExit(f"TEST3: expected exactly two testoptimize-build output refs, found {output_branch_hits}")
text = text.replace("testoptimize-build", "test3-build")

if "source_branch=testoptimize" not in text or "workflow=testoptimize" not in text:
    raise SystemExit("TEST3: BUILD_INFO source/workflow anchors missing")
text = text.replace("source_branch=testoptimize", "source_branch=test3")
text = text.replace("workflow=testoptimize", "workflow=test3")
text = text.replace(
    "optimizations=duplicate-load-dedupe,android-init-cap,audio-warm-pool-off,bounded-discard-workers,decoder-progress-watchdog,pre-roll-safe-startup",
    "optimizations=duplicate-load-dedupe,android-init-cap,audio-warm-pool-off,bounded-discard-workers,decoder-progress-watchdog,pre-roll-safe-startup,initial-authoritative-sync-gate,bounded-smart-late-frame-catchup",
)
text = text.replace(
    "commit -m 'build: testoptimize Android software JAR'",
    "commit -m 'build: test3 sync-gate plus smart late-frame catch-up JAR'",
)

# Compiled-JAR guards: source grep alone is not enough. Inspect the class that owns the catch-up path.
jar_copy_anchor = 'cp "$JAR" out/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-syncgate1-smartdrop1.jar\n'
compiled_guards = r'''mkdir -p verify-jar-test3
javap -classpath "$JAR" -p com.dreamdisplays.platform.client.displays.DisplayMediaController > verify-jar-test3/DisplayMediaController.txt
javap -classpath "$JAR" -c -p com.dreamdisplays.platform.client.displays.TimelineFollower > verify-jar-test3/TimelineFollower.txt
javap -classpath "$JAR" -p com.dreamdisplays.platform.client.displays.DisplayScreen > verify-jar-test3/DisplayScreen.txt
javap -classpath "$JAR" -p com.dreamdisplays.media.player.pipeline.FramePrebuffer > verify-jar-test3/FramePrebuffer.txt
grep -F 'releaseInitialTimeline' verify-jar-test3/DisplayMediaController.txt >/dev/null
grep -F 'releaseInitialTimelineLoad' verify-jar-test3/DisplayScreen.txt >/dev/null
grep -F 'releaseInitialTimelineLoad' verify-jar-test3/TimelineFollower.txt >/dev/null
grep -F 'smartCatchupEnabled' verify-jar-test3/FramePrebuffer.txt >/dev/null
grep -F 'lastSmartCatchupLogNanos' verify-jar-test3/FramePrebuffer.txt >/dev/null
grep -F 'SMART_CATCHUP_TRIGGER_NS' verify-jar-test3/FramePrebuffer.txt >/dev/null
grep -F 'SMART_CATCHUP_MAX_DROP_FRAMES' verify-jar-test3/FramePrebuffer.txt >/dev/null
'''
if text.count(jar_copy_anchor) != 1:
    raise SystemExit(f"TEST3: built-JAR copy anchor mismatch: {text.count(jar_copy_anchor)}")
text = text.replace(jar_copy_anchor, compiled_guards + jar_copy_anchor, 1)

p.write_text(text)
PY

chmod +x "$RUNTIME_SCRIPT"

echo "TEST3: production and TEST1 remain untouched"
echo "TEST3: layering bounded Android smart late-frame catch-up on the proven TEST1 sync gate"
bash "$RUNTIME_SCRIPT"
