#!/usr/bin/env bash
set -euo pipefail
trap 'status=$?; echo "ERROR: command failed at line $LINENO (exit $status): $BASH_COMMAND" >&2; exit "$status"' ERR

: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

EXPECTED_BRANCH="mobilefix1-1.21.11"
PINNED_LAUNCHER_COMMIT="e677674976ca27718ae00bfdc49ad08895ce1457"
PINNED_NEWPIPE_COMMIT="93eb70b4e6ab3be9bc6f0b1b381e371ec6bcd3d9"
PINNED_NEWPIPE_JAR_SHA256="c1bf3630f2af38c38a298dcbc04f82921b4ccb1101b862c8088024a67b07ecae"
BASE_ANDROID10_JAR_SHA256="dadc3e5ad87fd703567c2fc0cd492a886e0c6388d1c574978517169b24a9b607"
LAUNCHER="$RUNNER_TEMP/KiraziumLauncher-mobilefix1"
BASE_JAR="$RUNNER_TEMP/android10-base-mobilefix1.jar"
PIPE="media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt"
PREBUFFER="media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt"
AUDIO_LINE="media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt"
AUDIO_SINK="media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioSink.kt"
HW="media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt"
PLAYER="media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt"
SESSION="media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt"
WATCHDOG="media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/StreamWatchdog.kt"
CONTROLLER="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayMediaController.kt"
UPLOAD="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/TextureUploadUtil.kt"
MENU="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/DisplayMenu.kt"
PREF="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/SubtitlePreferencesMenu.kt"
VTT="platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/subtitles/WebVttController.kt"

if [[ "${GITHUB_REF_NAME:-}" != "$EXPECTED_BRANCH" ]]; then
  echo "ERROR: mobilefix1 1.21.11 may only build from branch $EXPECTED_BRANCH (got ${GITHUB_REF_NAME:-unset})." >&2
  exit 1
fi

# Freeze the Android compatibility patch source to the exact launcher commit reviewed for this build.
rm -rf "$LAUNCHER"
git clone --quiet --no-checkout https://github.com/skynecos/KiraziumLauncher.git "$LAUNCHER"
git -C "$LAUNCHER" fetch --quiet --depth 1 origin "$PINNED_LAUNCHER_COMMIT"
git -C "$LAUNCHER" checkout --quiet --detach "$PINNED_LAUNCHER_COMMIT"
test "$(git -C "$LAUNCHER" rev-parse HEAD)" = "$PINNED_LAUNCHER_COMMIT"

# JitPack no longer serves the historical NewPipe commit pinned by this source tree.
# Install the exact, repository-pinned and hash-verified compile dependency into
# Maven Local, preserving the original coordinate and transitive dependency POM.
NEWPIPE_DEP_DIR="$HOME/.m2/repository/com/github/TeamNewPipe/NewPipeExtractor/$PINNED_NEWPIPE_COMMIT"
NEWPIPE_JAR=".github/ci-deps/NewPipeExtractor-$PINNED_NEWPIPE_COMMIT.jar"
NEWPIPE_POM=".github/ci-deps/NewPipeExtractor-$PINNED_NEWPIPE_COMMIT.pom"
echo "$PINNED_NEWPIPE_JAR_SHA256  $NEWPIPE_JAR" | sha256sum -c -
grep -Fq "<version>$PINNED_NEWPIPE_COMMIT</version>" "$NEWPIPE_POM"
mkdir -p "$NEWPIPE_DEP_DIR"
cp "$NEWPIPE_JAR" "$NEWPIPE_DEP_DIR/NewPipeExtractor-$PINNED_NEWPIPE_COMMIT.jar"
cp "$NEWPIPE_POM" "$NEWPIPE_DEP_DIR/NewPipeExtractor-$PINNED_NEWPIPE_COMMIT.pom"

python3 - <<'PY'
from pathlib import Path

def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'Expected one build dependency anchor in {path}, found {text.count(old)}')
    p.write_text(text.replace(old, new, 1))

for path in ('build.gradle.kts', 'platform/client/common/build.gradle.kts',
             'platform/client/fabric/build.gradle.kts'):
    replace_once(path, 'repositories {', 'repositories {\n    mavenLocal()')
PY

printf '1.21.11\n' > versions/active.txt
python3 - <<'PY'
from pathlib import Path
p = Path('gradle.properties')
lines = p.read_text().splitlines()
lines = [('version=1.9.5' if line.startswith('version=') else line) for line in lines]
p.write_text('\n'.join(lines) + '\n')
PY

# Reconstruct the exact Android compatibility chain used by stallfix1.
for patch in apply-patches.py apply-android4.py apply-android5.py apply-android6.py apply-android7.py apply-android8.py apply-android9.py; do
  cp "$LAUNCHER/tools/dreamdisplays-android/$patch" "./$patch"
  python3 "./$patch"
done

# Keep the known-stable software decode policy. MediaCodec remains compiled but is never selected here.
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

# Reapply the exact stallfix1 source changes, then only the reviewed desktop-parity mobile stability fixes.
python3 .github/scripts/apply-testoptimize.py
python3 .github/scripts/apply-mobile-stability1.py

python3 - <<'PY'
from pathlib import Path
gp = Path('gradle.properties')
lines = gp.read_text().splitlines()
lines = [('version=1.9.5-kirazium-android-mobilefix1' if line.startswith('version=') else line) for line in lines]
gp.write_text('\n'.join(lines) + '\n')
PY

# Reuse the exact phone-tested Android10 native payload; fail closed if its bytes ever change.
git fetch --quiet --depth 1 origin android10-subtitles-build
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

# Source-level regression guards. Any missing/duplicated anchor aborts the build.
test "$(cat versions/active.txt)" = "1.21.11"
grep -Fq 'version=1.9.5-kirazium-android-mobilefix1' gradle.properties

grep -Fq 'MEDIACODEC("mediacodec", null, 6)' "$HW"
grep -Fq 'System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true -> NONE' "$HW"
grep -Fq 'return HwAccelBackend.NONE.lavCode' "$PIPE"
if grep -Fq 'return HwAccelBackend.MEDIACODEC.lavCode' "$PIPE"; then
  echo 'ERROR: mobilefix1 unexpectedly selects MediaCodec.' >&2
  exit 1
fi

# Preserve stallfix1 recovery and mobile load-shedding behavior.
grep -Fq 'current != null && screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored' "$CONTROLLER"
grep -Fq 'audioWarmPool.setTracks(emptyList())' "$SESSION"
grep -Fq 'DISCARD_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2)' "$SESSION"
grep -Fq 'lastFrameProgressNanos' "$SESSION"
grep -Fq 'getLastProgressNanos' "$WATCHDOG"
grep -Fq 'startupHardTimeoutMs' "$WATCHDOG"
grep -Fq 'stallThresholdMs = if (ANDROID_POJAV) 15_000L else 45_000L' "$PLAYER"
grep -Fq 'startupThresholdMs = 20_000L' "$PLAYER"
grep -Fq 'startupHardTimeoutMs = if (ANDROID_POJAV) 45_000L else 60_000L' "$PLAYER"

# mobilefix1 invariants: desktop-parity prebuffer, upstream per-plane encoder behavior,
# and an OpenAL clock based on consumed buffers rather than wall time.
grep -Fq 'private const val DEFAULT_PREBUFFER_MS = 400L' "$PREBUFFER"
if grep -Fq 'if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) 0L else 400L' "$PREBUFFER"; then
  echo 'ERROR: Android zero-prebuffer path still present.' >&2
  exit 1
fi

grep -Fq 'writeToTexture(' "$UPLOAD"
if grep -Fq 'val encoder = RenderSystem.getDevice().createCommandEncoder()' "$UPLOAD" && grep -Fq 'writeToTexture(' "$UPLOAD"; then
  : # expected: fresh encoder inside the helper
fi
if grep -Fq 'writeToTexture(\n                encoder,' "$UPLOAD"; then
  echo 'ERROR: Android8 shared planar command encoder still present.' >&2
  exit 1
fi

grep -Fq 'private val framesPlayed = AtomicLong(0)' "$AUDIO_LINE"
grep -Fq 'framesPlayed.addAndGet' "$AUDIO_LINE"
grep -Fq 'override fun getLongFramePosition(): Long = min(framesWritten.get(), framesPlayed.get())' "$AUDIO_LINE"
grep -Fq 'MOBILEFIX1 Android OpenAL line opened with reclaimed-frame clock.' "$AUDIO_SINK"

grep -Fq 'provisionAndroidHelperLibraries' media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/LavFfmpeg.kt
grep -Fq 'System.load(lib.absolutePath)' media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/NativeMedia.kt

test -s "$PREF"
test -s "$VTT"
grep -Fq 'SubtitlePreferencesMenu(ds, this)' "$MENU"
grep -Fq 'class WebVttController' "$VTT"

chmod +x gradlew
./gradlew :platform:client:fabric:publishJar \
  -Pdreamdisplays.autoBuildNatives=false \
  --no-daemon \
  --stacktrace

JAR="build/libs/dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar"
test -s "$JAR"
mkdir -p out verify-mobilefix1
jar tf "$JAR" | grep -F 'dreamdisplays-natives/linux-aarch64/libdreamdisplays_native.so' >/dev/null
jar tf "$JAR" | grep -F 'dreamdisplays-natives/linux-aarch64/libdreamdisplays_lav.so' >/dev/null
jar tf "$JAR" | grep -F 'SubtitlePreferencesMenu' >/dev/null
jar tf "$JAR" | grep -F 'WebVttController' >/dev/null
jar tf "$JAR" | grep -F 'AndroidOpenALLine' >/dev/null
jar tf "$JAR" | grep -F 'NativeVideoFramePipe' >/dev/null
unzip -p "$JAR" fabric.mod.json | grep -F '1.9.5-kirazium-android-mobilefix1' >/dev/null

javap -classpath "$JAR" -p com.dreamdisplays.media.player.pipeline.AndroidOpenALLine > verify-mobilefix1/AndroidOpenALLine.txt
grep -F 'framesPlayed' verify-mobilefix1/AndroidOpenALLine.txt >/dev/null
javap -classpath "$JAR" -p com.dreamdisplays.media.player.managers.StreamWatchdog > verify-mobilefix1/StreamWatchdog.txt
grep -F 'getLastProgressNanos' verify-mobilefix1/StreamWatchdog.txt >/dev/null
grep -F 'startupHardTimeoutMs' verify-mobilefix1/StreamWatchdog.txt >/dev/null
javap -classpath "$JAR" -c -p com.dreamdisplays.media.player.pipeline.NativeVideoFramePipe > verify-mobilefix1/NativeVideoFramePipe.txt
grep -F 'HwAccelBackend.NONE' verify-mobilefix1/NativeVideoFramePipe.txt >/dev/null
if grep -F 'HwAccelBackend.MEDIACODEC' verify-mobilefix1/NativeVideoFramePipe.txt >/dev/null; then
  echo 'ERROR: built mobilefix1 NativeVideoFramePipe references MediaCodec selector.' >&2
  exit 1
fi

cp "$JAR" out/dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar
sha256sum out/dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar > \
  out/dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar.sha256

cat > out/BUILD_INFO-mobilefix1.txt <<EOF
source_commit=$GITHUB_SHA
source_branch=$EXPECTED_BRANCH
launcher_patch_commit=$PINNED_LAUNCHER_COMMIT
newpipe_commit=$PINNED_NEWPIPE_COMMIT
minecraft=1.21.11
server_target=Paper 26.1.2
decode=software
base_native_jar_sha256=$BASE_ANDROID10_JAR_SHA256
base=stallfix1
changes=desktop-prebuffer-400ms,upstream-planar-command-encoder-behavior,reclaimed-openal-frame-clock
unchanged=ffmpeg-native-payload,stallfix1-watchdog,subtitle-system,server-protocol,software-decode
EOF

cat out/dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar.sha256
