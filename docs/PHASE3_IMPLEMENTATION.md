# Phase 3 — ASR: GigaAM v3 CTC offline transcription

Status: **code complete, verified by build, JVM tests and lint. The inference path has NOT been run on a physical device or emulator yet — no RTF/WER/RAM measurement exists. The benchmark harness shipped in this phase is the tool that produces those numbers.**

This document records what Phase 3 shipped, how it was verified, and what is deliberately still missing. It does not claim that recognition quality or speed is known: every performance figure from the Phase 1 report remains tagged **[A]** (assumption) or **[P]** (published by others) until the benchmark harness is run on real hardware.

---

## 1. Scope delivered

| Area | Delivered |
| --- | --- |
| ASR engine | `GigaamV3CtcEngine` — GigaAM v3 CTC INT8 via ONNX Runtime (`onnxruntime-android:1.31.1`), session contract validated at load |
| Acoustic frontend | `GigaamV3FeatureExtractor` — exact Kotlin port of the reference NumPy frontend (16 kHz, 64 HTK mel, win/hop/n_fft = 320/160/320, `center=false`, bfloat16 round-trip on window and filterbank), golden-tested against a NumPy fixture |
| Decoding | `GigaamCtcDecoder` + `GigaamWordBuilder` — greedy CTC collapse over the 34-class vocabulary, word grouping, token-frame timestamps (~40 ms resolution), per-word confidence from log-probs |
| Model provisioning | `AsrModelStore` — fully offline manual import (SAF), streaming copy with progress, exact size (224,721,181 B) **and** SHA-256 verification, `.part` temp file, re-verification on refresh |
| Offline pipeline | `TranscribeRecordingUseCase` — chunked WAV read → inference → word grouping → normalized segments → transactional Room persistence; `TranscriptionManager` — app-scope serialized job queue with per-recording state, cancel, dismiss |
| Chunking | `ChunkPlanner` — 12–23 s chunks with the boundary at the quietest 20 ms frame (explicitly documented as **not a VAD**) |
| UI integration | Transcribe / Re-transcribe on the transcript screen, model import/status card on Home, reprocess on History, progress card with stage, cancel and failure states |
| Benchmark tooling | JVM WER utility (`domain/benchmark/Wer`) + on-device instrumented benchmark (`GigaamAsrBenchmarkTest`) measuring load time, latency, RTF, PSS/RAM deltas, WER |
| Quality gates | `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug` all pass |

Explicitly **not** delivered in Phase 3: VAD, diarization, forced alignment beyond CTC frame resolution, streaming/real-time recognition, any on-device measurement.

---

## 2. How the pieces fit

```
WAV (16 kHz mono PCM16, from Phase 2 storage)
  → WavAudioSource (random-access windowed reads)
  → ChunkPlanner (12–23 s chunks, quietest-frame boundary)
  → GigaamV3FeatureExtractor (log-mel [1, 64, T])
  → GigaamV3CtcEngine (ORT session, log_probs [1, T/4, 34])
  → GigaamCtcDecoder (greedy collapse) → GigaamWordBuilder (words + 40 ms frame timestamps)
  → AsrSegmenter (700 ms pause / 15 s / 240 char utterances)
  → TranscriptSegment(speakerId = null → "unknown", alignmentSource = CTC_FRAME)
  → TranscriptRepository.replaceSegments (single Room transaction, JSON snapshot rebuilt)
```

Timestamps are translated to absolute recording time by adding the chunk's integer sample offset; the clock is never reset per chunk. The public JSON contract from Phase 2 is unchanged and is the only output format.

Threading: audio reads on the IO dispatcher, inference on a CPU dispatcher, one transcription job at a time (`TranscriptionManager` returns `BUSY` for a second request). The ORT session is created once and reused (`GigaamAsrEngineFactory`, mutex-guarded); `intraOpNumThreads = availableProcessors().coerceIn(1, 4)`.

---

## 3. Model provisioning (fully offline)

The app has **no INTERNET permission** and never downloads the model. The user picks `v3_ctc.int8.onnx` (published artifact `istupakov/gigaam-v3-onnx`) via the system file picker on the Home screen; the store streams it into `files/models/gigaam-v3/` and accepts it only when both the byte size and the SHA-256 match:

- size: `224 721 181` bytes
- sha256: `ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd`

A corrupt or truncated file is rejected before it can ever be loaded. Deleting the model is supported; the next `refresh()` re-verifies the on-disk file.

---

## 4. Benchmark tooling

### 4.1 WER (JVM, no device needed)

`domain/benchmark/Wer.kt`:

- Russian normalization: lowercase, `ё→е`, punctuation stripped, whitespace collapsed.
- Word-level Levenshtein with substitution/deletion/insertion counts.
- Empty reference yields `NaN` WER — the metric is undefined there, not zero.

Covered by 9 unit tests.

### 4.2 On-device benchmark (instrumented)

`app/src/androidTest/.../benchmark/GigaamAsrBenchmarkTest.kt` measures **only real numbers** on a device:

- model load time;
- per-clip inference latency and **RTF** (`inferenceMs / durationMs`);
- per-clip process **PSS delta** (`ActivityManager.getProcessMemoryInfo`) and JVM heap delta;
- **WER** when a `.txt` reference transcript sits next to the clip;
- aggregate RTF and micro-averaged WER in a JSON report.

One-time preparation:

```powershell
adb push v3_ctc.int8.onnx /sdcard/Android/data/com.vorontsov.neurostenographer/files/models/gigaam-v3/
adb push clips/ /sdcard/Android/data/com.vorontsov.neurostenographer/files/benchmark/
```

Each clip must be 16 kHz mono PCM16 WAV; an optional same-named `.txt` (UTF-8) is the reference. Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.vorontsov.neurostenographer.benchmark.GigaamAsrBenchmarkTest
```

The report lands in `files/benchmark/results/benchmark-<timestamp>.json` and is echoed to logcat (`GigaamBenchmark`). Without the model or clips the test **skips** via JUnit assumptions, so an unprepared device stays green.

---

## 5. Verification performed

Environment note: this machine had no JDK and no Android SDK. An isolated Temurin JDK 25 (`C:\Code\.jdk`) and a minimal SDK (`C:\Code\.android-sdk`: platform `android-37.0`, build-tools 36.1.0, platform-tools) were installed **outside the repository**; `local.properties` (gitignored) points at the SDK.

Commands:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

Result:

- `BUILD SUCCESSFUL` for all four tasks
- JVM tests: **49 passed, 0 failed** (10 suites)
- Lint: **0 errors, 18 warnings** (same non-blocking version/template noise as Phase 2)
- Debug APK: **155.2 MB** — the jump from Phase 2's 13.6 MB is the ONNX Runtime native libraries bundled for all four ABIs (~24–41 MB each, uncompressed). Per-ABI splits / ABI filtering are deferred to Phase 7, where the target ABI set is decided.

Test breakdown:

| Suite | Tests |
| --- | --- |
| `TranscriptJsonTest` | 9 |
| `WerTest` (new) | 9 |
| `GigaamCtcDecoderTest` | 7 |
| `WavFileTest` | 5 |
| `StreamingLinearResamplerTest` | 5 |
| `AsrSegmenterTest` | 4 |
| `ChunkPlannerTest` | 4 |
| `GigaamV3FeatureExtractorTest` (golden vs NumPy fixture) | 3 |
| `TranscribeRecordingUseCaseTest` | 2 |
| `ExampleUnitTest` (template) | 1 |

Two defects were found and fixed during this phase's verification — both in the new WER test expectations (a hyphenated word normalizes to two words; a two-word difference is two substitutions), not in production code.

---

## 6. Honest limitations

1. **No on-device run of the inference path.** The engine, frontend-to-model wiring, and ORT integration are verified by compilation and JVM tests only. Whether the INT8 model loads and produces correct Russian text on a real phone is unproven until the benchmark (or a manual transcription) runs on hardware.
2. **No performance numbers.** RTF, latency, peak RAM, and WER on device are unknown. Phase 1 figures remain assumptions/published numbers. The harness in §4.2 exists precisely to replace them.
3. **No VAD.** Chunk boundaries use an energy minimum, which does not detect speech and does not skip silence; silence is transcribed wastefully. Phase 4 replaces this.
4. **No diarization.** Every segment is persisted with `speakerId = null` and serializes as `"unknown"` — honestly, without fabricated speaker labels.
5. **Alignment is estimated, not verified.** Word timestamps come from CTC token frames (~40 ms resolution), tagged `CTC_FRAME` internally. No forced aligner is integrated (Phase 5 decision point).
6. **Chunk-based, not streaming.** GigaAM v3 has no native streaming; the pipeline processes bounded chunks sequentially. This is not claimed to be real-time recognition (Phase 6).
7. **The ASR session is never released.** `GigaamAsrEngineFactory` caches the engine for the process lifetime. That is acceptable with one model, but Phase 4's rule "never hold ASR and diarization sessions simultaneously" requires adding an explicit release path.
8. **APK grew to ~155 MB** (all-ABI ORT). Acceptable for development; Phase 7 must add ABI splits/filters.
9. **The 25 s model limit is handled by chunking, not by the model.** Word timestamps at chunk boundaries can be slightly degraded; no overlap-and-stitch stabilization exists yet.
10. **Room schema is unchanged** (still v1, no migrations needed for Phase 3).

---

## 7. Handoff to Phase 4 (VAD + diarization)

1. **Silero VAD via ONNX Runtime** (ORT is already integrated; Phase 1 lists a direct ORT session as a sanctioned option). ~2 MB model, same manual-provisioning pattern as `AsrModelStore`. VAD regions (padded ~200–300 ms, short gaps merged) replace `ChunkPlanner`'s energy heuristic and gate ASR on silence. Timestamps stay absolute via sample offsets.
2. **Engine release path**: add `release()` to `AsrEngineFactory` so the 225 MB session can be dropped before diarization loads (sequential lifecycle, Phase 1 §3.2 risk mitigation).
3. **Diarization via sherpa-onnx**: pyannote segmentation 3.0 + 3D-Speaker CAM++ + automatic clustering, unknown speaker count, `"unknown"` for low-confidence/short segments. Adds the second native runtime — APK size and peak memory must be measured.
4. **Speaker UI**: enable rename persistence (exists), merge (repository exists, UI pending), and surface provisional vs final labels.
5. Extend the benchmark harness with DER on a labeled multi-speaker clip and a VAD-gating CPU-saving measurement.
