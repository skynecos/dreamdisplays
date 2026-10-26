# Android Java launcher build

This is a distinct client artifact. Do not put the desktop `kirazium-vtt1` JAR into the mobile launcher.
The production Paper plugin remains `1.10.4-kirazium-vtt1`; its protocol and VTT metadata are unchanged.

The supplied phone log crashed in `GL42.glMemoryBarrier`, after attempting JavaSound `jsound` and a desktop VAAPI decoder.
This branch ports the existing mobilefix1 compatibility chain onto upstream 1.10.4 plus the VTT migration:

- Android uses Minecraft's command encoder instead of direct GL PBO uploads. Persistent mapping also requires an available memory-barrier function.
- Audio uses the legacy Android OpenAL SourceDataLine with a clock based on consumed buffers.
- Software decode, launcher-provided FFmpeg and its shared-library search path are selected before desktop platform detection.
- Native libraries are extracted into the launcher's private runtime directory and loaded with Android's linker.
- The two verified Android ARM64 libraries are reused from the old mobile artifact. Desktop native bundles are kept in a different directory.
- Mobile decode initialization, audio warm-up and stream recovery retain the existing mobile bounds.

## Rebuild

The reference is the old launcher-managed `dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar`,
SHA-256 `01f4a9a9fbab1fad686d2d62b8210d7dd28686bca3551537e158f15b100f6fc9`.
It is also available in KiraziumClient commit `43fae062d82184b9300ae1b40d61cf3fbd5fe3ed` under `app_pojavlauncher/src/main/assets/kirazium/mods/`.

Run `python tools/prepare_android_natives.py <reference-mobile.jar>`, then:

```sh
./gradlew :platform:client:fabric:publishJar :media:player:test :shared:test :util:test \
  --max-workers=1 --no-parallel --no-daemon \
  -Pversion=1.10.4-kirazium-android-vtt1 \
  -Pdreamdisplays.autoBuildNatives=false \
  -Pdreamdisplays.requireNatives=true \
  -Pdreamdisplays.requiredNativePlatforms=linux-aarch64 \
  -Pdreamdisplays.nativeBundleDir=native/build/android-bundle/dreamdisplays-natives
```

`tools/AndroidRuntimeSmoke.java` exercises the actual built JAR's Android renderer selection,
software decoder, FFmpeg environment and consumed-buffer audio clock in a child JVM with `POJAV_FFMPEG_PATH` set.
It does not exercise a phone's GPU, native audio device or video playback. Physical Android playback remains a separate acceptance test.
