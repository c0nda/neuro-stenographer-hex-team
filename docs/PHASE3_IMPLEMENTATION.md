# Phase 3 — Russian ASR, Offline Transcription and the JSON Contract

Status: **implemented and verified at the JVM/desktop level and on an Android 32 x86_64 emulator; not yet verified on physical ARM hardware. WER, DER and timestamp error against a labelled aligner are not measured.**

This document records what Phase 3 actually shipped, how it was verified, and what is still missing. The recognizer is a real on-device model — no mock text, no fabricated timestamps and no cloud calls. Speaker diarization is still a later phase, so every segment currently has `speaker_id: "unknown"`.

---

## 1. Scope delivered

| Area | Delivered |
| --- | --- |
| ASR model | GigaAM v3 CTC INT8 ONNX (`v3_ctc.int8.onnx`) through ONNX Runtime Android |
| Feature frontend | Exact Kotlin port of the official GigaAM v3 log-mel pipeline, checked against a NumPy golden fixture |
| Decoding | Greedy CTC decode with blank/repeat collapsing, word assembly and per-token model timing |
| Offline pipeline | Chunked WAV read → per-chunk inference → word grouping → segment normalization → transactional Room save |
| Public contract | Strict `{"transcript":[{"speaker_id","start_ms","end_ms","text"}]}` snapshot, unchanged from Phase 2 |
| Model provisioning | Model bundled in the APK as an asset, copied and SHA-256-verified into private storage on first launch; SAF import/replace remains as a fallback |
| UI integration | Model card on Home, Transcribe/Cancel/Retry card on Transcript, Reprocess action in History |
| Tests | JVM unit tests for frontend, decoder, segmentation, chunking, pipeline and contract; JVM frontend benchmark; on-device benchmark, recognition, bundled-model and full-pipeline tests |
| Quality gates | `assembleDebug`, `testDebugUnitTest`, `lintDebug` (result recorded in section 10) |

Explicitly **not** delivered in Phase 3:

- Silero VAD or any real voice-activity detector. Chunk boundaries use an energy-minimum heuristic and are documented as such.
- Speaker diarization and unknown-speaker clustering. `speaker_id` is always `"unknown"`.
- Forced alignment / verified word boundaries. Timing is derived from CTC encoder frames and marked `CTC_FRAME`, not `FORCED_ALIGNER`.
- Streaming/live recognition. Recording still writes a WAV; recognition runs after the recording stops.
- Whisper comparison, WER/DER evaluation, model download.
- Measurements on physical ARM64 hardware. Emulator results are recorded in section 10.

---

## 2. Why GigaAM v3 CTC INT8

- Russian-only, open, designed for short-form Russian speech.
- Released with an official ONNX export that runs on CPU through ONNX Runtime.
- CTC output provides frame-level timing directly, so word timestamps do not need to be invented or uniformly distributed.
- INT8 artifact is ~215 MB, large but it is packaged once into the APK and never downloaded.
- sherpa-onnx currently supports GigaAM v2, not v3, so the direct ONNX Runtime path was chosen instead of forcing an older model into a wrapper.

The model binary is **git-ignored** but **packaged into the APK** from `app/src/main/assets/models/gigaam-v3/`. There is no `INTERNET` permission and no download path.

---

## 3. Model artifact and provisioning

Expected artifact from `istupakov/gigaam-v3-onnx`:

| Property | Value |
| --- | --- |
| File name | `v3_ctc.int8.onnx` |
| Size | `224,721,181` bytes |
| SHA-256 | `ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd` |

The size and hash were verified locally against the downloaded file. The app accepts the file only if both match; a truncated, corrupted or different export is rejected and never loaded.

### Bundled first-launch provisioning

The verified artifact ships inside the APK at
`app/src/main/assets/models/gigaam-v3/v3_ctc.int8.onnx`. It is packaged
uncompressed (`androidResources.noCompress.add("onnx")`), so the debug APK is
380,452,897 bytes and contains a stored 224,721,181-byte asset. Re-deflating an
already-compressed INT8 binary would only slow every build and install for a
negligible size gain.

`AsrModelRepository.ensureProvisioned()` runs when Home opens:

1. If a file already exists in `files/models/gigaam-v3/`, it is re-verified
   (the size + modification stamp avoids re-hashing on every open) and the
   method stops.
2. Otherwise the bundled asset is streamed into
   `v3_ctc.int8.onnx.part`, SHA-256 is computed during the copy, size and hash
   are checked against the table above, and the temporary file is atomically
   renamed into place.
3. Status moves `IMPORTING → READY` (or `INVALID` / `ERROR`), and the Home
   model card shows `Preparing the model on this device…`, then
   `Ready · 214.3 MB · size and SHA-256 verified`.

No user action and no network are involved. The app has no `INTERNET`
permission and there is no download path.

The model binary is git-ignored
(`app/src/main/assets/models/**/*.onnx`) because of its size.
`app/src/main/assets/models/gigaam-v3/NOTICE.txt` records the upstream
provenance and MIT license. A build made without the binary still succeeds,
logs a `preBuild` warning, and falls back to manual import.

First-install storage cost is roughly twice the artifact size: the copy inside
the APK plus the hash-verified copy in private storage. Keeping the private
copy is deliberate — it can be hash-verified, replaced, and read with ordinary
file I/O.

### Manual import fallback

The Home screen still offers **Import model file** / **Replace model file**
with the system document picker. This covers builds made without the bundled
asset and lets a user replace the model. The chosen file is streamed into
app-private storage, hashed while copying, and atomically renamed only after
verification.

For automated instrumentation tests the model is normally provisioned from the
bundle automatically. `BundledAsrModelTest` checks the asset's declared length
and the provisioning path itself; manual `adb` push is no longer required.

---

## 4. Architecture

```
Home / Transcript / History (Compose, MVVM)
        │
        ├── AsrModelRepository  ── AsrModelStore (bundled asset / SAF import, SHA-256, private file)
        ├── TranscriptionManager ── TranscribeRecordingUseCase
        │                              ├── AudioSourceFactory → WavAudioSource (random access PCM16)
        │                              ├── ChunkPlanner (energy-minimum split, 12–23 s)
        │                              ├── AsrEngineFactory → GigaamV3CtcEngine
        │                              │      ├── GigaamV3FeatureExtractor (64-bin log-mel)
        │                              │      ├── GigaamCtcDecoder (greedy CTC)
        │                              │      └── GigaamWordBuilder (words + 40 ms timings)
        │                              ├── AsrSegmenter (pauses/length → utterances)
        │                              └── TranscriptRepository (Room transaction + JSON snapshot)
        └── Room / DataStore / audio capture
```

The domain layer still has no Compose, Room, ONNX or Android dependency. The model and ONNX Runtime live behind `AsrEngine`; ViewModels see only `AsrModelRepository`, `TranscriptionManager` and repository interfaces.

Key files:

- `domain/asr/Asr.kt` — engine, audio source, model status/repository contracts
- `domain/asr/Segmentation.kt` — `ChunkPlanner`, `AsrSegmenter`
- `domain/transcription/TranscribeRecordingUseCase.kt` — offline pipeline and persistence
- `ml/asr/gigaam/GigaamV3FeatureExtractor.kt` — exact frontend
- `ml/asr/gigaam/GigaamCtcDecoder.kt` — CTC decode and word builder
- `ml/asr/gigaam/GigaamV3CtcEngine.kt` — ONNX session and inference
- `data/asr/AsrModelStore.kt` — bundled provisioning, import, verify, status
- `data/transcription/TranscriptionManager.kt` — serialized jobs and progress state
- `di/AsrModule.kt` — Hilt bindings
- `assets/models/gigaam-v3/NOTICE.txt` — bundled model provenance and license

---

## 5. Feature frontend

The frontend must match the model's training preprocessing exactly, otherwise the model silently degrades. The implementation mirrors the reference `onnx-asr` NumPy preprocessor:

- 16 kHz mono input
- 64 mel bins, HTK mel scale, no mel normalization
- `n_fft = win_length = 320`, `hop_length = 160`, `center = false`
- `f_min = 0`, `f_max = 8000`
- periodic Hann window (`np.hanning(321)[:-1]`)
- window and filterbank rounded to bfloat16 and widened back to float32
- power spectrum `|rfft|²`, then `log(clamp(mel, 1e-9, 1e9))`
- frame count `(N - 320) / 160 + 1`; no padding

JTransforms performs the arbitrary 320-point real FFT. The feature matrix is laid out for the ONNX input `features` with shape `[1, 64, frames]`.

A 1600-sample deterministic fixture was generated with the reference NumPy code and committed as `app/src/test/resources/gigaam/gigaam_v3_fixture.txt`. The Kotlin extractor matches it with a maximum absolute difference below `2e-3`.

---

## 6. Model contract, decoding and timestamps

Verified by loading the actual INT8 artifact:

| Tensor | Type | Shape |
| --- | --- | --- |
| `features` | float32 | `[batch, 64, seq_len]` |
| `feature_lengths` | int64 | `[batch]` |
| `log_probs` | float32 | `[batch, ~seq_len/4, 34]` |

Vocabulary: `0` = word separator `▁`, `1..32` = Russian letters (no `ё`), `33` = CTC blank. GigaAM v3 CTC is lowercase and punctuation-free; the app intentionally does not capitalize or punctuate the result.

Greedy decoding collapses repeated frames and drops blanks. A token's time comes from its encoder frame: one frame = 4 frontend hops × 10 ms = **40 ms**. A word spans its first token's frame to one frame past its last token. Confidence is the mean selected-token probability, stored as internal metadata only.

These timings are model-derived (`AlignmentSource.CTC_FRAME`), not forced-aligned. They are shown in the UI as **Estimated timing**. No timestamp is spread uniformly across an utterance.

A reference NumPy pipeline plus the real ONNX model was run on a Russian SAPI sample (Microsoft Irina Desktop, 16 kHz mono). It decoded:

> привет это проверка распознавания русской речи на улице сегодня холодно но солнце светит ярко

for the spoken text “Привет. Это проверка распознавания русской речи. На улице сегодня холодно, но солнце светит ярко.” This validates the frontend, the model contract and the CTC reconstruction path end to end. It is a smoke test, not a WER measurement.

---

## 7. Offline pipeline and resource bounds

1. Recording is finalized as a 16 kHz mono PCM16 WAV.
2. `WavAudioSource` opens the file with random access; audio is never loaded in full.
3. `ChunkPlanner` splits recordings longer than 23 s. The boundary is the centre of the quietest 20 ms frame in a 12–23 s search window. This is a boundary heuristic, **not VAD**; it reduces the chance of cutting a word but does not detect speech.
4. Each chunk is read as float PCM and passed to the ONNX session. A chunk is at most 23 s, so session memory stays bounded.
5. Words from all chunks are offset by the chunk start and grouped into utterances on pauses longer than 700 ms, duration longer than 15 s, or 240 characters.
6. Segments are written in one Room transaction, together with words and the JSON snapshot. Recording status moves `RECORDED → PROCESSING → READY`; cancellation returns it to `RECORDED`; failure sets `FAILED` with the error message.
7. `TranscriptionManager` runs one job at a time in an application-scoped coroutine, exposes progress/stage state and supports cancellation. No work runs on the main thread.

Very short audio (less than one 320-sample window) produces an empty transcript and is saved as `{"transcript":[]}`.

The ONNX session is created lazily once and shared. Model loading is expensive, so it happens off the main thread behind a mutex.

---

## 8. Public JSON contract

The only public shape is unchanged:

```json
{"transcript":[{"speaker_id":"unknown","start_ms":0,"end_ms":1680,"text":"привет"}]}
```

Rules enforced by `TranscriptJson`:

- exactly four fields per segment; no confidence, alignment, word or model metadata;
- integer milliseconds;
- ordered by `start_ms`, then `end_ms`;
- stable speaker IDs matching `speaker_<n>` or `unknown`;
- overlapping segments are preserved rather than merged;
- invalid segments fail loudly instead of producing a corrupt file.

The same serializer is used for Room snapshots and SAF export. Real-time provisional segments are not produced in Phase 3 because live recognition is not implemented; the existing `is_provisional` storage and UI path remain ready for that phase.

Words are persisted in the `words` table and are now reloaded with their segments. They remain internal and never enter the public JSON.

---

## 9. UI integration

- **Home** — model card showing preparing / verifying / ready / invalid / error, manual import and replace buttons as a fallback, verification note, and size after verification. The bundled model is provisioned automatically on first open.
- **Transcript** — on-device recognition card with model guidance, Transcribe / Transcribe again, queued and running stages, percentage, cancel, failure and cancellation states. A note states that timing is CTC-derived and speakers are not diarized yet.
- **History** — Reprocess now starts an actual transcription job, with snackbars for started, busy and model-missing cases.
- **Recording** — copy updated: live recognition is not available; audio is saved and transcribed offline after stopping.

Stale Phase 2 strings claiming recognition was not integrated were replaced.

---

## 10. Verification performed

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | passed (debug APK builds) |
| `:app:testDebugUnitTest` | passed; 41 tests, 0 failures, 0 skipped (ASR pipeline, decoder, segmenter, chunk planner, JSON contract and frontend golden test) |
| `:app:compileDebugAndroidTestKotlin` | passed; benchmark, recognition and pipeline harnesses compile |
| `:app:lintDebug` | passed; 0 errors, 17 warnings (unused resources, a plurals suggestion, and newer-dependency notices) |
| Instrumented tests on emulator (Android 32, x86_64, 4 cores) | 5/5 passed: app context, bundled-model provisioning, model benchmark, Russian recognition smoke test, full offline pipeline |
| Debug APK packaging | 380,452,897 bytes; contains `assets/models/gigaam-v3/v3_ctc.int8.onnx`, stored uncompressed at 224,721,181 bytes |
| Fresh install with the bundled-model APK | uninstall → streamed install → first launch; Home reached **Ready · 214.3 MB · size and SHA-256 verified** with no user action, model present at `files/models/gigaam-v3/v3_ctc.int8.onnx` |
| Bundled provisioning timing on emulator | model file visible 5.3–6.8 s after `am start` on a cold launch (includes process start); warm copy + SHA-256 of the 224.7 MB asset: 1.21–1.22 s |
| Desktop NumPy frontend vs Kotlin frontend | max absolute difference < `2e-3` |
| Real ONNX model on Russian SAPI sample | correct sentence decoded |
| JVM frontend benchmark, 10 s synthetic audio | 999 frames, best 22 ms on the final run (12–22 ms across runs), frontend RTF ≈ 0.0023 on this machine |
| On-device recognition of `ru_sample.wav` (10.2 s) | 14 words, 280–9360 ms, text matched the spoken sentence |
| On-device offline pipeline | 3 segments, 14 words, recording status `READY`, exact four-field JSON contract |
| Home screen after automatic provisioning | reported **Ready · 214.3 MB · size and SHA-256 verified** |
| On-device model benchmark (warm, after provisioning) | load 2.4–3.4 s, inference 2.9–3.0 s for 15 s audio, RTF 0.19–0.20, heap used 9–11 MB, delta PSS ≈ 280–285 MB |

The on-device run used the `Pixel_6_Pro` AVD (Android 32, x86_64, 4 vCPU, 2 GB RAM, WHPX) with the real 224,721,181-byte INT8 model installed automatically from the bundled asset. The first, cold benchmark run measured load 7.9 s and RTF 1.198; after the emulator and JIT had warmed up, repeated runs measured RTF 0.19–0.20. Neither number is representative of a physical ARM phone, and the model benchmark uses synthetic tone, so it measures latency/RAM, not accuracy.

The on-device pipeline produced exactly:

```json
{"transcript":[
  {"speaker_id":"unknown","start_ms":280,"end_ms":720,"text":"привет"},
  {"speaker_id":"unknown","start_ms":1680,"end_ms":4520,"text":"это проверка распознавания русской речи"},
  {"speaker_id":"unknown","start_ms":5520,"end_ms":9360,"text":"на улице сегодня холодно но солнце светит ярко"}
]}
```

The JVM benchmark measures **feature extraction only**, not the model. It is not an end-to-end ASR real-time factor.

Numbers that are **not** measured and must not be claimed:

- on-device RTF, first-token latency, peak RAM or CPU on physical ARM hardware (emulator numbers exist, phone numbers do not);
- WER on a Russian corpus;
- DER / speaker-count accuracy (no diarization exists);
- timestamp error against a labelled aligner;
- Whisper comparison.

Instrumented harnesses skip themselves when the model/fixture is absent, so CI without a device stays green.

---

## 11. Known limitations and next steps

1. Add Silero VAD before chunking and use speech boundaries instead of the energy heuristic.
2. Add real diarization with unknown speaker count, conservative handling of short utterances, stable `speaker_<n>` IDs and overlap support.
3. Add a forced aligner for verified word boundaries; keep `CTC_FRAME` as the estimated fallback.
4. Measure WER and timestamp error on a labelled Russian corpus and compare against Whisper.
5. Repeat the on-device benchmark on a physical ARM64 phone before making performance claims; the only numbers so far are from an x86_64 emulator.
6. Decide whether to expose model removal/storage management and whether a smaller model tier is needed for low-RAM devices.
7. Add streaming recognition with bounded queues and provisional-segment updates while keeping the same public JSON contract.
8. Consider reloading the ONNX session after the model file is replaced; currently an already-loaded session keeps using the old weights until process restart.
9. The bundled APK is ~380 MB, above Google Play's 200 MB base-module limit. Sideloading, MDM or an internal distribution channel is the intended delivery path; Play distribution would require Play Asset Delivery or a download step, which conflicts with the no-download requirement.
10. First install uses roughly 2× the model size (APK asset + verified private copy). A future variant could read the model directly from the APK or delete the asset copy, at the cost of hash verification and replaceability.

---

## 12. How to reproduce

```powershell
# Build and JVM tests
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Frontend benchmark only
.\gradlew.bat :app:testDebugUnitTest --tests "*GigaamV3FrontendBenchmarkTest" -i

# On-device benchmark (requires a connected device; the model is provisioned automatically on first launch)
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.vorontsov.neurostenographer.ml.asr.gigaam.GigaamOnDeviceBenchmarkTest

# Bundled asset + automatic provisioning check
adb shell am instrument -w `
  -e class com.vorontsov.neurostenographer.ml.asr.gigaam.BundledAsrModelTest `
  com.vorontsov.neurostenographer.test/androidx.test.runner.AndroidJUnitRunner

# Instrumented recognition + full pipeline (fixture pushed via adb)
adb shell am instrument -w `
  -e class com.vorontsov.neurostenographer.ml.asr.gigaam.GigaamOnDeviceRecognitionTest `
  com.vorontsov.neurostenographer.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w `
  -e class com.vorontsov.neurostenographer.ml.asr.gigaam.GigaamOnDevicePipelineTest `
  com.vorontsov.neurostenographer.test/androidx.test.runner.AndroidJUnitRunner
```

To install the debug APK and provision the test fixture:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Launching Home copies and verifies the bundled model; wait for Ready.
adb shell am start -n com.vorontsov.neurostenographer/.MainActivity

adb push ru_sample.wav /data/local/tmp/ru_sample.wav
adb shell chmod 644 /data/local/tmp/ru_sample.wav
adb shell run-as com.vorontsov.neurostenographer mkdir -p files/test
adb shell run-as com.vorontsov.neurostenographer cp `
  /data/local/tmp/ru_sample.wav files/test/ru_sample.wav
```

`ru_sample.wav` is not committed; generate any 16 kHz mono PCM16 Russian sample, or reuse the SAPI sample described in section 6.

To use the app: install the debug APK, open Home and wait for **Ready** (the first launch copies and SHA-256-verifies the bundled recognizer), then record or import audio and tap **Transcribe** on the transcript screen. If the APK was built without the bundled asset, tap **Import model file** instead and choose the verified `v3_ctc.int8.onnx`.
