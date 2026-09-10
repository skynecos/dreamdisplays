#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1):
    p = ROOT / path
    text = p.read_text()
    found = text.count(old)
    if found != count:
        raise SystemExit(
            f"TEST2 mobile720p30 anchor mismatch in {path}: expected {count}, found {found}: {old[:180]!r}"
        )
    p.write_text(text.replace(old, new, count))


SELECTOR = "media/source/src/main/kotlin/com/dreamdisplays/media/source/DefaultStreamSelector.kt"

# TEST2 adds a Kirazium-Android-only stream selection profile. It does not transcode on-device:
# when a resolver exposes alternatives we prefer <=720p and <=30 fps so the phone decodes less.
# If a direct URL exposes only a higher-quality stream, playback is preserved and the fallback is
# logged explicitly instead of breaking the display.
replace(
    SELECTOR,
    '''    /** Whether to prefer progressive audio over adaptive. */\n    private val preferProgressiveAudio: Boolean =\n        System.getProperty("dreamdisplays.audio.preferProgressive", "true").toBoolean()\n\n''',
    '''    /** Whether to prefer progressive audio over adaptive. */\n    private val preferProgressiveAudio: Boolean =\n        System.getProperty("dreamdisplays.audio.preferProgressive", "true").toBoolean()\n\n    /** Kirazium Android profile: prefer native <=720p and <=30 fps streams when available. */\n    private val mobile720p30: Boolean =\n        !System.getenv("POJAV_FFMPEG_PATH").isNullOrBlank() ||\n                !System.getenv("MOD_ANDROID_RUNTIME").isNullOrBlank()\n\n    private val mobileMaxHeight = 720\n    private val mobileMaxFps = 30.5\n\n''',
)

replace(
    SELECTOR,
    '''        val targetHeight = preferences.maxHeight ?: 1080\n        val lang = preferences.preferredAudioLanguage ?: ""\n\n        val video = MediaStreamSelector.pickVideo(videoStreams, targetHeight, preferences.preferFps60)\n            ?: videoStreams.firstOrNull()\n''',
    '''        val requestedHeight = preferences.maxHeight ?: 1080\n        val targetHeight = if (mobile720p30) minOf(requestedHeight, mobileMaxHeight) else requestedHeight\n        val lang = preferences.preferredAudioLanguage ?: ""\n\n        val preferFps60 = if (mobile720p30) false else preferences.preferFps60\n        val preferredVideoStreams = if (mobile720p30) {\n            val heightSafe = videoStreams.filter { it.height == null || it.height <= mobileMaxHeight }\n            val heightAndFpsSafe = heightSafe.filter { it.fps == null || it.fps <= mobileMaxFps }\n            when {\n                heightAndFpsSafe.isNotEmpty() -> heightAndFpsSafe\n                heightSafe.isNotEmpty() -> heightSafe\n                else -> videoStreams\n            }\n        } else {\n            videoStreams\n        }\n\n        val video = MediaStreamSelector.pickVideo(preferredVideoStreams, targetHeight, preferFps60)\n            ?: preferredVideoStreams.firstOrNull()\n            ?: videoStreams.firstOrNull()\n\n        if (mobile720p30) {\n            val selectedHeight = video?.height\n            val selectedFps = video?.fps\n            val withinHeightCap = selectedHeight == null || selectedHeight <= mobileMaxHeight\n            val withinFpsCap = selectedFps == null || selectedFps <= mobileMaxFps\n            logger.info(\n                "TEST2 mobile profile selected={}p fps={} target={}p constrained={}",\n                selectedHeight ?: -1, selectedFps ?: -1.0, targetHeight, withinHeightCap && withinFpsCap\n            )\n        }\n''',
)

replace(
    SELECTOR,
    '''                        preferences.preferFps60\n                    )\n                } " +\n                        "candidates=${videoStreams.size} preferFps60=${preferences.preferFps60}.",\n''',
    '''                        preferFps60\n                    )\n                } " +\n                        "candidates=${preferredVideoStreams.size}/${videoStreams.size} preferFps60=$preferFps60 mobile720p30=$mobile720p30.",\n''',
)

print("TEST2 mobile 720p30 stream profile patch applied cleanly")
