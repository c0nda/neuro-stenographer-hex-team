# Phase 4b — Speaker diarization via sherpa-onnx

Status: **code complete, verified by build, JVM tests and lint. The sherpa-onnx engine has not been executed on a device in this environment; diarization quality (DER) and peak RAM during diarization are unmeasured.**

This document records what Phase 4b shipped, the verified API/model facts, the engineering decisions (including two build-level trade-offs), and the honest limitations.

---

## 1. Scope delivered

| Area | Delivered |
| --- | --- |
| Native runtime | sherpa-onnx v2.0.1 (Apache-2.0) as vendored official AAR (`app/libs/sherpa-onnx-2.0.1.aar`, static-link-onnxruntime variant, SHA-256 `07e042d9…a43a575`) |
| Diarization engine | `ml/diarization/SherpaDiarizationEngine` — pyannote segmentation 3.0 (INT8) + 3D-Speaker CAM++ embeddings + threshold fast clustering, unknown speaker count |
| Turn merging | `domain/diarization/SpeakerTurnMerger` — cluster→`speaker_N` in first-appearance order; max-overlap assignment; `"unknown"` for no-overlap or low-confidence; words/timestamps/order untouched; no fabricated overlap |
| Pipeline | `TranscribeRecordingUseCase` new `DIARIZING` stage; strictly sequential heavy-model lifecycle (ASR released **before** diarization loads, diarization released after); whole-file buffer with documented RAM cost |
| Model provisioning | `DiarizationModelStore` — two files on the shared `VerifiedModelStore`; combined READY only when both verified; third Home card with two import buttons |
| Speaker UI | Merge enabled on the Speakers screen (repository support existed since Phase 2): dialog picks the merge target, segments are reassigned in one transaction |
| Tests | 10 new JVM tests (79 total, all passing) |

## 2. Verified facts (re-checked at implementation time)

**sherpa-onnx v2.0.1 Kotlin API** (`com.k2fsa.sherpa.onnx`, from the v2.0.1 tag sources):
- `OfflineSpeakerDiarization(config)` loads from file paths; `process(FloatArray)` returns `OfflineSpeakerDiarizationSegment(start, end /* seconds */, speaker /* cluster index from 0 */, confidence /* [-1,1], -2 = unavailable */)`; `release()`.
- `FastClusteringConfig(numClusters = -1, threshold)` — threshold mode = unknown speaker count (larger threshold → fewer speakers).
- Defaults kept: `minDurationOn = 0.2`, `minDurationOff = 0.5`.

**Models (both MIT/Apache-2.0, manually provisioned, pinned by size + SHA-256):**

| Model | Artifact | Size | SHA-256 |
| --- | --- | --- | --- |
| Segmentation | `sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx` (tag `speaker-segmentation-models`; pyannote segmentation 3.0 conversion, MIT) | 1,540,506 B | `d582f4b4…827b5d` |
| Embedding | `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx` (tag `speaker-recongition-models`; 3D-Speaker CAM++, Apache-2.0) | 29,596,978 B | `357a834f…4129b` |

Hashes were computed locally from the downloaded artifacts during implementation.

## 3. Build-level decisions (and their trade-offs)

1. **Vendored AAR instead of Maven.** sherpa-onnx does not publish an Android AAR to Maven Central. The official prebuilt AAR is committed to `app/libs/` as a file dependency: builds stay fully offline and reproducible at the cost of ~38 MB in the repository. Chosen over a build-time download task (which would break offline builds) by explicit decision.
2. **static-link-onnxruntime variant.** The plain AAR bundles its own `libonnxruntime.so` (ORT 1.28) which collided at merge time with onnxruntime-android's (1.31). The static-link variant embeds ORT inside `libsherpa-onnx-jni.so` for arm64-v8a/armeabi-v7a/x86_64, eliminating the conflict on every device ABI.
3. **`pickFirsts` for x86 only.** The x86 (32-bit emulator-only) sherpa build still links ORT dynamically; `packaging.jniLibs.pickFirsts += "**/libonnxruntime.so"` resolves the duplicate by keeping the app-bundled ORT. Trade-off accepted because x86 is not a device target and modern emulators are x86_64. If x86 emulator crashes in the sherpa path, the fix is dropping x86 entirely — devices are unaffected.
4. **`abiFilters` set to arm64-v8a / armeabi-v7a / x86_64** (x86 dropped for the reason above).

**APK impact:** debug APK grew 155.2 MB → **182.5 MB** (three ABIs of the statically linked sherpa JNI lib). ABI splits remain a Phase 7 work item.

## 4. Pipeline behaviour

```
VAD → ASR chunks → segments (speakerId = null)
  → asrEngineFactory.release()              // 225 MB ASR session freed FIRST
  → read whole WAV into FloatArray (bounded block reads on IO dispatcher)
  → SherpaDiarizationEngine.diarize()       // cpuDispatcher
  → diarizationFactory.release()            // ~30 MB diarization freed
  → SpeakerTurnMerger.assign(segments, turns)
  → replaceSegments(...)                    // single Room transaction
```

- Fallback when diarization models are absent: identical to Phase 4a behaviour (all speakers `"unknown"`), no errors.
- RAM note: the whole-file float buffer costs 64 KB per second of audio (~115 MB for 30 min) **on top of** the diarization models. Recordings longer than `Int.MAX_VALUE` samples are rejected with an explicit error. Sliding-window diarization for very long recordings is a Phase 7 item.
- Cancellation is honoured between stages; status rollback is unchanged.

## 5. Verification performed

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

- `BUILD SUCCESSFUL` for all four tasks (the vendored AAR compiles into both app and androidTest).
- JVM tests: **79 passed, 0 failed** (+`SpeakerTurnMergerTest` 9, +1 pipeline test asserting stable `speaker_1`/`speaker_2` inside the validated public JSON).
- Lint: 0 errors, 18 warnings (unchanged version/template noise).

## 6. Honest limitations

1. **No on-device diarization run.** The sherpa-onnx JNI has not been exercised on hardware; whether the v2.0.1 AAR loads and produces sensible Russian-speaker clusters is unproven until a device run (multi-speaker test clip + DER measurement).
2. **DER is unmeasured; threshold 0.5 is the library default, not a tuned value.** Phase 7 must evaluate `clustering.threshold` on labelled Russian multi-speaker data. `minConfidence` in `SpeakerTurnMerger` is currently 0.0f for the same reason.
3. **CAM++ is trained on VoxCeleb (English-centric).** Phase 1 flagged Russian DER as a risk; the embedding artifact is replaceable behind `DiarizationEngineFactory` if ERes2Net or a Russian-tuned checkpoint proves better.
4. **Overlapping speech is not preserved.** sherpa-onnx's high-level diarization emits single-speaker turns; the JSON contract permits overlap but we do not fabricate it (Phase 1 documented this as a stretch goal requiring powerset decoding).
5. **Whole-file diarization buffer** limits very long recordings (see §4). No resumable/progress-persistent diarization jobs yet.
6. **Speaker split UI is not built.** Merge + per-segment reassignment cover most corrections; a dedicated split is a separate feature.
7. **Real-time diarization does not exist.** Provisional speaker labels during recording are Phase 6, and offline diarization must not be presented as a streaming solution.

## 7. Handoff

- **Phase 5 (word alignment):** decide on a Russian forced aligner only if measured word-boundary error from CTC frames exceeds the agreed target; keep `AlignmentSource` tagging.
- **Phase 6 (real-time):** provisional segments/speakers, bounded queues; reuse `VadRegionTracker` for streaming gating.
- **Phase 7 (optimization):** ABI splits (APK is 182.5 MB), DER/threshold tuning, peak-RAM measurement during diarization, sliding-window diarization, stale-recording recovery.
- **Benchmark harness:** add a DER scorer and a labelled multi-speaker clip; extend `GigaamAsrBenchmarkTest` or add a sibling diarization benchmark test.
