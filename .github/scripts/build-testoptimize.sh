#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"

LAUNCHER_BRANCH="codex/dreamdisplays-ffmpeg"
BASE_ANDROID10_JAR_SHA256="dadc3e5ad87fd703567c2fc0cd492a886e0c6388d1c574978517169b24a9b607"
LAUNCHER="$RUNNER_TEMP/KiraziumLauncher"
BASE_JAR="$RUNNER_TEMP/android10-base.jar"
PIPE="media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt"
HW="media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt"
PLAYER="media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt"
SESSION="media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt"
WATCHDOG="media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/StreamWatchdog.kt"
CONTROLLER="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayMediaController.kt"
MENU="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/DisplayMenu.kt"
PREF="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/SubtitlePreferencesMenu.kt"
VTT="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/subtitles/WebVttController.kt"

rm -rf "$LAUNCHER"
git clone --depth 1 --branch "$LAUNCHER_BRANCH" \
  https://github.com/skynecos/KiraziumLauncher.git "$LAUNCHER"

printf '26.1.2\n' > versions/active.txt
python3 - <<'PY'
from pathlib import Path
p = Path('gradle.properties')
lines = p.read_text().splitlines()
lines = [('version=1.9.5' if line.startswith('version=') else line) for line in lines]
p.write_text('\n'.join(lines) + '\n')
PY

# Reapply the proven Android compatibility chain first.
for patch in apply-patches.py apply-android4.py apply-android5.py apply-android6.py apply-android7.py apply-android8.py apply-android9.py; do
  cp "$LAUNCHER/tools/dreamdisplays-android/$patch" "./$patch"
  python3 "./$patch"
done

# Keep Android decoding explicitly software-only. MediaCodec remains compiled but dormant.
python3 - <<'PY'
from pathlib import Path
pipe = Path('media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt')
text = pipe.read_text()
old = '''        if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) {
            return HwAccelBackend.MEDIACODEC.lavCode
        }
'''
new = '''        if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) {
            return HwAccelBackend.NONE.lavCode
        }
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'Expected exactly one Android MediaCodec selector, found {count}')
pipe.write_text(text.replace(old, new, 1))
PY

# Apply only the isolated testoptimize source changes after the known-good Android patch chain.
python3 .github/scripts/apply-testoptimize.py

python3 - <<'PY'
from pathlib import Path
gp = Path('gradle.properties')
lines = gp.read_text().splitlines()
lines = [('version=1.9.5-kirazium-android-stallfix1' if line.startswith('version=') else line) for line in lines]
gp.write_text('\n'.join(lines) + '\n')
PY

# Reuse the exact native binaries from the phone-tested Android10/software baseline.
git fetch --depth 1 origin android10-subtitles-build
git show FETCH_HEAD:artifacts/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android10-subtitles.jar > "$BASE_JAR"
echo "$BASE_ANDROID10_JAR_SHA256  $BASE_JAR" | sha256sum -c -

BUNDLE="native/build/ci-bundle/dreamdisplays-natives/linux-aarch64"
mkdir -p "$BUNDLE"
unzip -p "$BASE_JAR" dreamdisplays-natives/linux-aarch64/libdreamdisplays_native.so > "$BUNDLE/libdreamdisplays_native.so"
unzip -p "$BASE_JAR" dreamdisplays-natives/linux-aarch64/libdreamdisplays_lav.so > "$BUNDLE/libdreamdisplays_lav.so"
test -s "$BUNDLE/libdreamdisplays_native.so"
test -s "$BUNDLE/libdreamdisplays_lav.so"
readelf -h "$BUNDLE/libdreamdisplays_lav.so" | grep -F 'AArch64' >/dev/null
readelf -d "$BUNDLE/libdreamdisplays_lav.so" | grep -F 'libavcodec.so' >/dev/null
readelf -d "$BUNDLE/libdreamdisplays_lav.so" | grep -F 'libavformat.so' >/dev/null

# Regression guards: software decode must stay explicit and all optimizations must actually be present.
test "$(cat versions/active.txt)" = "26.1.2"
grep -Fq 'MEDIACODEC("mediacodec", null, 6)' "$HW"
grep -Fq 'System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true -> NONE' "$HW"
grep -Fq 'return HwAccelBackend.NONE.lavCode' "$PIPE"
if grep -Fq 'return HwAccelBackend.MEDIACODEC.lavCode' "$PIPE"; then
  echo 'ERROR: Android LAV still selects MediaCodec.' >&2
  exit 1
fi

grep -Fq 'current != null && screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored' "$CONTROLLER"
grep -Fq 'private val ANDROID_POJAV' "$PLAYER"
grep -Fq 'coerceIn(2, 4)' "$PLAYER"
grep -Fq 'audioWarmPool.setTracks(emptyList())' "$SESSION"
grep -Fq 'DISCARD_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2)' "$SESSION"
grep -Fq 'DISCARD_EXECUTOR.execute {' "$SESSION"
grep -Fq 'lastFrameProgressNanos' "$SESSION"
grep -Fq 'getLastProgressNanos' "$WATCHDOG"
grep -Fq 'startupHardTimeoutMs' "$WATCHDOG"
grep -Fq 'stallThresholdMs = if (ANDROID_POJAV) 15_000L else 45_000L' "$PLAYER"
grep -Fq 'startupThresholdMs = 20_000L' "$PLAYER"
grep -Fq 'startupHardTimeoutMs = if (ANDROID_POJAV) 45_000L else 60_000L' "$PLAYER"

grep -Fq 'provisionAndroidHelperLibraries' media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/LavFfmpeg.kt
grep -Fq 'System.load(lib.absolutePath)' media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/NativeMedia.kt
grep -Fq 'version=1.9.5-kirazium-android-stallfix1' gradle.properties

test -s "$PREF"
test -s "$VTT"
grep -Fq 'SubtitlePreferencesMenu(ds, this)' "$MENU"
grep -Fq 'displayScreen.subtitleScale' "$PREF"
grep -Fq 'displayScreen.subtitleVerticalPosition' "$PREF"
grep -Fq 'subtitleBackgroundOpacity' "$PREF"
grep -Fq 'subtitleFont' "$PREF"
grep -Fq 'subtitleTextColor' "$PREF"
grep -Fq 'subtitleOutlineColor' "$PREF"
grep -Fq 'subtitleBackgroundColor' "$PREF"
grep -Fq 'class WebVttController' "$VTT"

chmod +x gradlew
./gradlew :platform:client:fabric:publishJar \
  -Pdreamdisplays.autoBuildNatives=false \
  --no-daemon \
  --stacktrace

JAR="build/libs/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-stallfix1.jar"
test -s "$JAR"
mkdir -p out verify-jar
jar tf "$JAR" | grep -F 'dreamdisplays-natives/linux-aarch64/libdreamdisplays_native.so' >/dev/null
jar tf "$JAR" | grep -F 'dreamdisplays-natives/linux-aarch64/libdreamdisplays_lav.so' >/dev/null
jar tf "$JAR" | grep -F 'SubtitlePreferencesMenu' >/dev/null
jar tf "$JAR" | grep -F 'WebVttController' >/dev/null
jar tf "$JAR" | grep -F 'NativeVideoFramePipe' >/dev/null
jar tf "$JAR" | grep -F 'DisplayMediaController' >/dev/null
jar tf "$JAR" | grep -F 'PlaybackSessionManager' >/dev/null

javap -classpath "$JAR" -c -p com.dreamdisplays.media.player.pipeline.NativeVideoFramePipe > verify-jar/NativeVideoFramePipe.txt
grep -F 'HwAccelBackend.NONE' verify-jar/NativeVideoFramePipe.txt >/dev/null
if grep -F 'HwAccelBackend.MEDIACODEC' verify-jar/NativeVideoFramePipe.txt >/dev/null; then
  echo 'ERROR: Built NativeVideoFramePipe still references MediaCodec.' >&2
  exit 1
fi

javap -classpath "$JAR" -p com.dreamdisplays.media.player.managers.StreamWatchdog > verify-jar/StreamWatchdog.txt
javap -classpath "$JAR" -p com.dreamdisplays.media.player.managers.PlaybackSessionManager > verify-jar/PlaybackSessionManager.txt
grep -F 'getLastProgressNanos' verify-jar/StreamWatchdog.txt >/dev/null
grep -F 'startupHardTimeoutMs' verify-jar/StreamWatchdog.txt >/dev/null
grep -F 'getLastFrameProgressNanos' verify-jar/PlaybackSessionManager.txt >/dev/null

cp "$JAR" out/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-stallfix1.jar
sha256sum out/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-stallfix1.jar > out/dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-stallfix1.jar.sha256

staged="$RUNNER_TEMP/testoptimize-stage"
output="$RUNNER_TEMP/testoptimize-output"
rm -rf "$staged" "$output"
mkdir -p "$staged"
cp out/* "$staged/"

git worktree add --detach "$output" "$GITHUB_SHA"
cd "$output"
git switch --orphan testoptimize-build
git rm -rf . >/dev/null 2>&1 || true
mkdir -p artifacts
cp "$staged"/* artifacts/
printf 'source_commit=%s\nsource_branch=testoptimize\nworkflow=testoptimize\ndecode=software\nbase_native_jar_sha256=%s\noptimizations=duplicate-load-dedupe,android-init-cap,audio-warm-pool-off,bounded-discard-workers,decoder-progress-watchdog,pre-roll-safe-startup\nwatchdog_android_ms=stall:15000,startup-progress:20000,startup-hard:45000\n' \
  "$GITHUB_SHA" "$BASE_ANDROID10_JAR_SHA256" > artifacts/BUILD_INFO.txt
git add artifacts
git -c user.name='github-actions[bot]' \
    -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
    commit -m 'build: testoptimize Android software JAR'
git push --force \
  "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" \
  HEAD:refs/heads/testoptimize-build
