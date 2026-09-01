# Kirazium WebVTT and local media

This branch extends Dream Displays 1.9.5 with two independent source choices:

- Video: an existing internet URL or a server-local `.mp4` file.
- Subtitles: an HTTP(S) WebVTT URL or a server-local `.vtt` file.

The Paper plugin serves local files byte-for-byte. MP4 responses support HTTP `Range` requests and
`206 Partial Content`, so clients can start and seek without downloading or transcoding the entire
video. The Fabric client parses WebVTT off the render thread and samples cues from the same playback
clock used by pause, resume, and seek.

## Installation

Install the matching Kirazium builds on both sides:

1. Put the Paper JAR in the server's `plugins` folder.
2. Put the Fabric JAR in every viewer's client `mods` folder.
3. Start the server once. Dream Displays creates:

```text
plugins/DreamDisplays/
├── media-server.properties
└── media/
    ├── videos/
    └── subtitles/
```

4. Put MP4 files in `media/videos` and VTT files in `media/subtitles`.
5. Configure the media address in `media-server.properties`:

```properties
enabled=true
bind-address=0.0.0.0
port=8095
public-base-url=http://your-public-host:8095
```

`public-base-url` must be reachable from every player's computer. Open or tunnel the configured
port as **TCP**. When a reverse proxy supplies HTTPS, use its public HTTPS origin instead. Run
`/display reload` after changing this file, or restart the server.

## Commands

Use `this` while looking at a display, or replace it with the display's ID/name.

```text
/display video this url <video-url> [audio-language]
/display video this file videos/film.mp4 [audio-language]

/display subtitle this url <vtt-url>
/display subtitle this file subtitles/film-tr.vtt
/display subtitle this off
```

The old URL syntax remains compatible:

```text
/display video this <video-url> [audio-language]
```

Quote local paths containing spaces:

```text
/display video this file "videos/Uzun Film.mp4" tr
```

A remote subtitle endpoint does not need to end in `.vtt`; it only needs to return valid UTF-8
WebVTT text. UTF-8 Turkish characters, cue identifiers, cue settings, markup, multiline cues, and
overlapping cues are supported.

## Compatibility and safety

- The frozen v1 plugin-message format is unchanged.
- The optional subtitle field is appended to the v2 protobuf packet. Old 1.9.5 clients ignore it;
  new clients treat it as empty when connected to an old server.
- Only existing `.mp4` and `.vtt` files below `plugins/DreamDisplays/media` can be served.
- Absolute paths, `..` traversal, unsupported extensions, missing files, and symlink escapes are
  rejected.
- The server provides no directory listing and never modifies or re-encodes media bytes.
