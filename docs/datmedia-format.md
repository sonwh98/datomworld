# Datom Media Container v1

`.datmov` and `.datmus` are local-first, lossless media containers. They add a
canonical d5 manifest to an existing browser-playable media payload. They are
not codecs and do not transcode, re-encode, or alter the payload.

## Run locally

Install the Java/Clojure and Node.js dependencies, then start the dedicated
media-player build:

```sh
npm install
npx shadow-cljs watch media-player
```

Open `http://localhost:9000`. Select a browser-supported audio or video file,
then use **Export .datmov** or **Export .datmus** to create a local datom
container. The exported file can be reopened with the same picker.

Create an optimized build with:

```sh
npx shadow-cljs release media-player
```

## Layout

| Offset | Size | Value |
| --- | ---: | --- |
| 0 | 8 bytes | UTF-8 magic: `DATMOV1\n` or `DATMUS1\n` |
| 8 | 4 bytes | Unsigned little-endian manifest byte length |
| 12 | variable | UTF-8 EDN vector of d5 datoms |
| 12 + manifest length | remaining bytes | Original media payload |

The manifest is limited to 1 MiB. A reader must reject an unknown signature,
oversized or truncated manifest, invalid datom shape, kind/MIME mismatch, or a
payload length different from `:datom.media/payload-bytes`.

## Manifest

Every manifest fact has the canonical `[e a v t m]` shape:

```clojure
[[-1025 :datom.media/version 1 1 1]
 [-1025 :datom.media/kind :movie 1 1]
 [-1025 :datom.media/codec-mode :native-payload 1 1]
 [-1025 :datom.media/original-name "clip.mp4" 1 1]
 [-1025 :datom.media/original-mime "video/mp4" 1 1]
 [-1025 :datom.media/payload-bytes 4096 1 1]
 [-1025 :datom.media/duration-seconds 12.5 1 1]]
```

- `e=-1025` is the container-local media entity.
- `t=1` is the manifest transaction.
- `m=1` is `:db/assert`.
- `:movie` maps to `.datmov`; `:music` maps to `.datmus`.
- `:native-payload` means the payload remains in its original codec/container.

## Conversion

Conversion constructs one browser `Blob` from the fixed header, manifest, and
original `File`. The original bytes are not sent through DaoStreams or Yin.VM.
The browser downloads the result directly and no network request is made.

## Playback

The source terminal reads only the fixed header and bounded manifest. After
validation it exposes the payload with `Blob.slice`, preserving the original
MIME type. The native media element decodes that slice. Only the manifest,
playback observations, commands, and Yin state appear as datoms.

## Compatibility

Format support remains limited to the codecs supported by the browser and
operating system. Wrapping an unsupported codec does not make it playable.
Future transcoded or chunk-indexed formats require a new version and magic
signature.
