# Phase 2 — Android Infrastructure and UI

Status: **functionally complete for the infrastructure/UI scope, verified by build, JVM tests and lint. Not yet verified on a physical device or emulator.**

This document records what Phase 2 actually shipped, how it was verified, which design decisions were made, and what is deliberately still missing. It does not claim that speech recognition, VAD or diarization work: those belong to Phase 3 and the UI states that would require them are explicitly marked as unavailable.

---

## 1. Scope delivered

| Area | Delivered |
| --- | --- |
| Project setup | Single `:app` module, AGP 9.3.3 built-in Kotlin, Gradle Kotlin DSL, version catalog |
| UI | Jetpack Compose + Material 3, five screens and a navigation graph |
| Architecture | Presentation / Domain / Data separation, MVVM, Hilt DI |
| Audio capture | Real `AudioRecord` capture, pause/resume, WAV writer, foreground service |
| Audio import | SAF picker, streaming MediaExtractor/MediaCodec decode, downmix, resample |
| Persistence | Room v1 with five entities, DAOs, transactions, exported schema |
| Settings | DataStore Preferences |
| Playback | `MediaPlayer` wrapper with seek, progress ticker and load-token race protection |
| Contract | Strict transcript JSON encoder/validator for the required public schema |
| Tests | 20 JVM unit tests, all passing |
| Quality gates | `assembleDebug`, `testDebugUnitTest`, `lintDebug` all pass |

Explicitly **not** delivered in Phase 2: ONNX Runtime, sherpa-onnx, Silero VAD, ASR, forced alignment, diarization, live transcript, reprocessing, speaker merge UI, benchmarks on device.

---

## 2. Project configuration

- `namespace` / `applicationId`: `com.vorontsov.neurostenographer`
- `minSdk 26`, `targetSdk 37`, `compileSdk 37`
- Java/Kotlin target: JVM 17
- AGP 9.3.3 with built-in Kotlin; the standalone `org.jetbrains.kotlin.android` plugin is intentionally **not** applied.
- `android.disallowKotlinSourceSets=false` is required for KSP with AGP 9 built-in Kotlin and is marked experimental by the toolchain.
- Room schema export goes to `app/schemas`.

Main dependencies (versions from `gradle/libs.versions.toml`):

- Compose BOM `2026.02.01`, Material 3, Navigation Compose `2.10.2`
- Lifecycle `2.11.0`, Activity Compose `1.13.0`
- Hilt `2.60.1` + KSP `2.2.10-2.0.2`
- Room `2.8.5`
- DataStore Preferences `1.3.0`
- Coroutines `1.11.0`

No model binaries, native ML libraries or network permissions are part of this phase. The app has no `INTERNET` permission.

---

## 3. Package layout

```
com.vorontsov.neurostenographer
├── NeuroStenographerApp.kt        @HiltAndroidApp
├── MainActivity.kt                Compose entry point
├── data
│   ├── audio                      capture, import, playback, WAV, resampler
│   ├── export                     SAF transcript export
│   ├── file                       audio path ownership
│   ├── local                      Room database, entities, DAOs, converters
│   └── repository                 repository implementations
├── di                             Hilt modules and qualifiers
├── domain
│   ├── model                      UI-independent models and enums
│   ├── repository                 repository interfaces
│   └── transcript                 strict public JSON serializer/validator
├── presentation
│   ├── common                     shared Compose widgets/formatters
│   ├── history                    History screen + ViewModel
│   ├── home                       Home screen + ViewModel
│   ├── navigation                 routes and NavHost
│   ├── recording                  Recording screen + ViewModel
│   ├── speakers                   Speakers screen + ViewModel
│   └── transcript                 Transcript screen + ViewModel
└── ui.theme                       Material 3 theme
```

The domain layer has no Android, Compose, Room or ML dependency. Phase 3 ML code should enter through domain interfaces rather than being referenced from ViewModels.

---

## 4. Persistence

### 4.1 Room schema v1

Five entities, all in `data/local/entity/Entities.kt`:

- `recordings` — title, working WAV path, optional original path, duration, timestamps, status, speaker count, sample rate, channels, source, error message.
- `transcripts` — one per recording (unique `recordingId`), timestamps, optional JSON snapshot.
- `speakers` — per recording, unique `(recordingId, speakerKey)`, optional display name/color.
- `segments` — transcript, nullable speaker, `startMs`, `endMs`, text, `isProvisional`, `alignmentSource`, `orderIndex`.
- `words` — segment, text, `startMs`, `endMs`, optional confidence, `alignmentSource`.

Foreign keys:

- Recording → transcripts, speakers: `CASCADE`
- Transcript → segments: `CASCADE`
- Segment → words: `CASCADE`
- Speaker → segments: `SET_NULL` (a segment survives without a speaker and serializes as `"unknown"`)

Indexes cover recording creation time/status, transcript lookup, speaker lookup, segment start time and word lookup. The schema is exported and checked in under `app/schemas/`.

`NeuroStenographerDatabase.MIGRATIONS` is intentionally empty at v1. There is no destructive fallback. Every future schema change must add an explicit migration.

### 4.2 Repositories

`RecordingRepository`, `TranscriptRepository`, `SpeakerRepository`, `SettingsRepository` are domain interfaces bound to implementation singletons by Hilt.

Notable behaviour:

- `TranscriptRepositoryImpl.replaceSegments` runs in a single Room transaction: creates the transcript if needed, deletes old segments/words, upserts speakers by key, rebuilds the JSON snapshot and updates the recording speaker count.
- `SpeakerRepositoryImpl.mergeSpeakers` runs in a transaction, reassigns segments, deletes the source speaker and rebuilds the JSON snapshot. The UI does not expose merge yet.
- Deleting a recording cascades through transcripts, segments, words and speakers via foreign keys. The audio files are deleted separately by the repository.
- Room `Flow` queries drive the lists; history search is an in-memory case-insensitive title filter over the observed rows, so there is no manual refresh loop.

### 4.3 Settings and file storage

- DataStore stores the processing profile (`PERFORMANCE` / `BALANCED` / `ACCURACY`) and the keep-screen-on preference.
- Audio lives in app-specific external storage (`getExternalFilesDir(DIRECTORY_MUSIC)/audio`), split into `recordings/` and `originals/`. No storage permission is required and files are removed with the app.

---

## 5. Audio capture

`AudioRecorder` owns one `AudioRecord` instance on a dedicated `THREAD_PRIORITY_AUDIO` thread.

- Source: `VOICE_RECOGNITION`
- Format: 16 kHz, mono, PCM16
- Read block: 2048 samples
- Buffer: `max(2 × minBuffer, 16 KiB)`
- Pause: `AudioRecord.stop()`; resume: `startRecording()`
- Amplitude: per-block RMS normalized to `0..1`
- Failure: reported through `onError`, never swallowed silently
- Permission: checked by `RecordingSessionManager`; the worker carries a narrow `@SuppressLint("MissingPermission")` because the check is performed before starting.

`WavFileWriter` writes a standard PCM16 WAV; `WavFileReader` parses PCM16 headers for import. The writer is closed on stop and the final duration is derived from the written sample count/header, not from the wall clock.

`RecordingSessionManager` is process-scoped and serializes start/pause/resume/stop behind a `Mutex`:

- Creates the recording row, then starts the recorder; a recorder start failure marks the row `FAILED` and deletes the file.
- Timer uses `SystemClock.elapsedRealtime()` and accumulated paused time, sampled every 200 ms.
- Keeps the last 64 amplitude values for the waveform.
- Emits `finishedRecording` after a successful stop so the UI can navigate to the transcript.
- Marks the row `FAILED` and stops the foreground service on recorder failure.

`RecordingService` is a `START_NOT_STICKY` foreground service that only mirrors session state into an ongoing notification. It does not capture audio itself. The `microphone` foreground-service type is supplied only on API 30+; on API 26–29 the type mask is `0` to avoid referencing a later constant.

Known limitation: if the process dies during a recording, the database row can remain `RECORDING`. Startup recovery for stale rows is not implemented yet.

---

## 6. Audio import

`AudioImporter` imports a user-selected URI without loading the whole file into memory:

1. Copy the original into app storage (`originals/`) so it is preserved.
2. If it is already 16 kHz mono PCM16 WAV, use the copy directly.
3. If it is another PCM16 WAV, stream-convert to 16 kHz mono in chunks.
4. Otherwise use `MediaExtractor` + `MediaCodec` to decode the first audio track to a temporary raw PCM file, downmix and resample in a streaming pass, then delete the temporary file.
5. Create the recording row with the working WAV path, original path, measured duration and `RECORDED` status.

The decoder handles PCM16 and float PCM output. Channel downmix is an arithmetic mean. Resampling is linear interpolation, not a band-limited resampler; this is acceptable for speech-to-ML input but is not audiophile-quality.

Failure or cancellation deletes any partially created original/working files. Import progress is an indeterminate indicator; byte-level progress is not exposed.

---

## 7. Transcript contract

`domain/transcript/TranscriptJson` is the only serializer for the required public format:

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"..."}]}
```

Properties enforced by `encode` / `validate`:

- Exactly four fields per segment: `speaker_id`, `start_ms`, `end_ms`, `text`.
- `start_ms` and `end_ms` are integers and non-negative; `end_ms >= start_ms`.
- `speaker_id` must match `speaker_[1-9][0-9]*` or `unknown`; null/blank normalize to `unknown`.
- `text` must not be blank.
- Output is sorted by `start_ms`, then `end_ms`, then internal id, so ordering is deterministic.
- Internal-only data (words, provisional flag, alignment source, ids) never leaks into the public JSON.
- Strings are escaped manually, including control characters.
- Invalid input fails loudly rather than producing a corrupt document.

The internal `TranscriptSegment` keeps `isProvisional`, `alignmentSource` and words so Phase 3 can distinguish estimated from verified timing without changing the public contract.

---

## 8. Playback and export

- `AudioPlayer` wraps `MediaPlayer`, prepares off the main thread, ticks position every 200 ms and supports pause, seek and seek-and-play.
- A monotonically increasing load token prevents a slow preparation from an abandoned screen from replacing the current screen's player. `release()` invalidates in-flight loads synchronously.
- `TranscriptExporter` writes an already serialized JSON document to a SAF URI (`CreateDocument("application/json")`). Export is not a share intent and does not require FileProvider.

---

## 9. Presentation

Navigation routes: `home`, `recording`, `history`, `transcript/{recordingId}`, `speakers/{recordingId}`.

- **Home** — recent recordings (max 5), start recording, import audio, import progress/errors, active-recording banner, history entry.
- **Recording** — runtime microphone permission request, elapsed timer, waveform, start/pause/resume/stop, keep-screen-on while active, error snackbars, background-recording note. Live transcript and current speaker are honest placeholders that state the feature is not integrated.
- **Transcript** — recording metadata, playback controls with seek, segment list with speaker colors/names, time ranges, provisional and estimated/verified timing tags, tap-to-play-from-segment, empty/missing states, JSON export.
- **Speakers** — speaker list with per-speaker rename dialog, honest empty state, note that merge/split arrives with diarization.
- **History** — search, rename, delete confirmation, reprocess action that reports it is unavailable.

Dynamic messages are resolved with `LocalResources.current.getString(...)` rather than `LocalContext.current.getString(...)`. All user-visible strings are English resources; there is no Russian localization of the UI yet (the product language requirement applies to transcription, not the chrome).

---

## 10. Bug fixed during Phase 2

The streaming linear resampler had a real chunk-boundary defect: with non-integer ratios such as 44100/16000, keeping only one history sample could skip target positions when the next chunk began. The resampler now retains two history samples and uses absolute sample positions, so output is continuous across chunk boundaries. `StreamingLinearResamplerTest` includes regression coverage for non-integer ratios and chunk splits.

A second concurrency issue was addressed in playback: a stale `MediaPlayer.prepare()` from a previous screen could otherwise become the active player. Load tokens fix this.

---

## 11. Verification performed

Commands:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

Result at the end of Phase 2:

- `BUILD SUCCESSFUL`
- Debug APK: **13.6 MB**
- Lint: **0 errors, 18 warnings**
- JVM tests: **20 passed, 0 failed**

Test breakdown:

| Suite | Tests |
| --- | --- |
| `TranscriptJsonTest` | 9 |
| `WavFileTest` | 5 |
| `StreamingLinearResamplerTest` | 5 |
| `ExampleUnitTest` (template) | 1 |

Lint warnings are non-blocking and mostly version/template noise: newer AGP/Compose versions available, unused template resources/strings, and annotation-target warnings from Hilt. There are no lint errors.

Approximate source size: ~4,690 lines of Kotlin under `app/src/main`, ~313 lines of JVM tests.

---

## 12. Honest limitations

1. **No on-device run has been performed.** Everything above is verified by compilation, JVM tests and lint only. Audio capture, permissions, the foreground service, notification behaviour, SAF import and playback are not yet proven on real hardware or an emulator. Instrumentation tests are not written.
2. **No ML is integrated.** There is no VAD, ASR, alignment or diarization code. The UI never fabricates transcript text, timestamps or speakers; it explicitly says the feature is unavailable.
3. **No live pipeline.** Real-time provisional updates, bounded queues and backpressure exist only as Phase 3 design, not as code.
4. **Import progress is indeterminate.** There is no progress callback from decode/resample.
5. **Linear resampling only.** Adequate for speech input, not band-limited; very high or unusual sample rates may alias.
6. **No stale-recording recovery.** A process death during capture can leave a `RECORDING` row that is not reconciled on next launch.
7. **Speaker merge is implemented in the repository but not exposed in the UI.** Merge/split will be enabled with diarization.
8. **Reprocess is a placeholder.** It reports that ASR is not integrated.
9. **UI strings are not localized.** Only English string resources exist.
10. **Room has no migrations yet.** v1 is the initial schema; future changes must add real migrations.
11. **The foreground service is not sticky and has no restart policy.** That is deliberate for a recording session, but it means an OS kill ends the recording.
12. **Storage estimates and performance figures remain Phase 1 assumptions/published numbers.** No RTF, RAM, CPU or WER/DER measurement has been made on this device.
13. **`android.disallowKotlinSourceSets=false` and the JVM 24 fallback warnings** are toolchain constraints, not product bugs, but they should be revisited when AGP/KSP support settles.

---

## 13. Handoff to Phase 3

Phase 3 should add, in this order:

1. ONNX Runtime (GigaAM v3 CTC INT8) and sherpa-onnx (Silero VAD + diarization) behind domain interfaces, loaded sequentially to control peak RAM.
2. Model download/integrity handling outside the APK; no model binaries committed.
3. GigaAM frontend (64-bin HTK log-mel, `win=320`, `hop=160`, `n_fft=320`, `center=false`, `log(clamp(1e-9,1e9))`) with a unit test against a reference implementation.
4. CTC greedy decode + token-frame timestamps grouped into words, marked `CTC_FRAME` (estimated).
5. VAD-driven chunking and the offline pipeline: chunked read → VAD → ASR → diarization → optional forced alignment → merge → normalize → JSON → Room.
6. Streaming pipeline with provisional segments replacing earlier versions, bounded queues and explicit backpressure.
7. Instrumentation tests and real-device verification of capture/import/playback before claiming UI correctness.
8. Benchmark harness for WER, DER, RTF, latency, peak RAM, CPU and timestamp error.

The persistence schema, JSON contract, repository layer and screens were built so Phase 3 can populate them without changing the public output contract. The only UI work expected is replacing the honest "not integrated" states with real progress/results and enabling reprocess, merge/split and live updates.
