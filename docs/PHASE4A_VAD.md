# Phase 4a — Silero VAD: speech detection and silence skipping

Status: **code complete, verified by build, JVM tests and lint. The VAD model has not been executed on a device in this environment; end-to-end behaviour on real audio is pending an on-device run.**

This document records what Phase 4a shipped, the verified model contract, the decisions made, and the honest limitations. Diarization (Phase 4b) is deliberately not part of this phase.

---

## 1. Scope delivered

| Area | Delivered |
| --- | --- |
| VAD engine | `ml/vad/SileroVadEngine` — Silero VAD v5/v6 via ONNX Runtime (no new native dependencies), stateful LSTM (state + 64-sample context carried between 512-sample frames) |
| Region logic | `domain/vad/VadRegionTracker` — pure state machine over per-frame probabilities: onset/offset hysteresis (0.5 / 0.35), 300 ms silence hangover, 250 ms minimum-speech filter |
| Region post-processing | `domain/vad/SpeechRegionMerger` — ±250 ms padding, gap merge below 300 ms, clamping to file bounds; pure function over integer sample indices (no drift) |
| Chunk planning | `domain/vad/VadSpeechPlanner` — VAD regions become ASR chunks (≤ 23 s; longer regions split at quietest frames inside their own bounds); `ChunkPlanner` gained an internal `planRange` overload, its public behaviour is unchanged |
| Pipeline integration | `TranscribeRecordingUseCase` — new `DETECTING_SPEECH` stage; VAD runs over the file in bounded blocks (≤ 16 384 samples per read, whole file never in RAM); silence is skipped by ASR; absolute timestamps come from integer sample offsets |
| Fallback | VAD model not installed → previous energy-based `ChunkPlanner` path, byte-for-byte unchanged |
| Model provisioning | Generic `VerifiedModelStore` (shared with ASR) + `VadModelStore`; official artifact pinned by size and SHA-256 |
| Engine lifecycle | `AsrEngineFactory.release()` added (default no-op; closes the GigaAM session) — the API Phase 4b needs for the sequential ASR→diarization lifecycle. The pipeline deliberately does **not** release ASR after each job (reloading 225 MB per transcription would be wasteful) |
| UI | Home screen shows two model cards: ASR (required) and VAD (optional, honest copy about the energy-based fallback); generic `ModelState`/`ModelStatus`/`ModelRepository` replaced the ASR-specific status types |
| Tests | 20 new JVM tests (69 total, all passing) |

## 2. Verified model contract (Silero VAD v5/v6, official `silero_vad.onnx`)

Confirmed against multiple independent implementations (official snakers4 repo, pipecat, onnx-community) during Phase 4 planning:

- 16 kHz mono, 512-sample frames (32 ms); stateful.
- Inputs: `input` float32 [1, 576] (64 context + 512 frame), `state` float32 [2,1,D] (D = 128 for v5/v6), `sr` int64 [1] = 16000.
- Outputs: `output` float32 [1,1] speech probability, `stateN` float32 [2,1,D].
- License: MIT.

The engine reads the state dimension from the session at load (v4 exports use D = 64) and validates input/output names; anything else is rejected with `VadModelUnavailableException` before transcription starts.

## 3. Pinned artifact

| Property | Value |
| --- | --- |
| Source | `github.com/snakers4/silero-vad`, `src/silero_vad/data/silero_vad.onnx` |
| Size | 2,327,524 bytes |
| SHA-256 | `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` |

The hash was computed locally from the downloaded artifact at implementation time; only this exact file is accepted, and the app never downloads it (no `INTERNET` permission).

## 4. Pipeline behaviour

```
WAV (16 kHz mono) ── bounded block reads ──► SileroVadEngine ──► VadRegionTracker
      ──► SpeechRegionMerger (pad/merge/clamp) ──► VadSpeechPlanner (chunks ≤ 23 s)
      ──► GigaAM CTC per chunk (silence never inferred) ──► AsrSegmenter
      ──► replaceSegments (single Room transaction)
```

- Timestamps stay in integer sample space until the final ms translation; region offsets are added with the same arithmetic as chunk offsets in Phase 3 — no per-region clock.
- A silence-only recording is a successful empty transcript (`READY`, 0 segments), not an error.
- Cancellation is honoured between VAD blocks and between chunks.

## 5. Decisions and their reasons

| Decision | Reason |
| --- | --- |
| Silero VAD directly on ONNX Runtime (not sherpa-onnx) | ORT is already integrated; VAD needs no sherpa-specific machinery. sherpa-onnx is deferred to Phase 4b where its diarization pipeline is actually required. |
| Hysteresis 0.5 / 0.35, 300 ms hangover | Silero's recommended threshold is 0.5; the lower release threshold plus hangover avoids splitting words on single weak frames. These are starting values — device benchmarks (Phase 7) may tune them. |
| ±250 ms padding | Phase 1 recommendation; protects word onsets clipped by thresholding. |
| VAD session stays resident | ~2 MB model; releasing it saves nothing meaningful, unlike the 225 MB ASR session. |
| `ModelState`/`ModelStatus`/`ModelRepository` generalized | Three model families (ASR, VAD, diarization) share identical provisioning semantics; one abstraction instead of three copies. |

## 6. Verification performed

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

- `BUILD SUCCESSFUL` for all tasks
- JVM tests: **69 passed, 0 failed** (up from 49; +`VadRegionTrackerTest` 7, +`SpeechRegionMergerTest` 6, +`VadSpeechPlannerTest` 5, +2 use-case tests)
- Lint: 0 errors (warnings unchanged — version/template noise)

The ONNX engine itself compiles against `onnxruntime-android:1.31.1` and validates the session contract at load; actual inference on real audio requires the model on a device.

## 7. Honest limitations

1. **No on-device VAD run.** Region logic is unit-tested with synthetic probability streams; real-audio behaviour (threshold tuning, RTF of VAD itself) is unmeasured.
2. **CPU saving from silence skipping is not yet measured** — the Phase 3 benchmark harness can quantify it once run on hardware.
3. **VAD gating applies to the offline pipeline only.** Real-time recording still captures everything; streaming VAD-gated ASR belongs to Phase 6.
4. **Overlap is not modelled** — Silero VAD reports "speech or not", not "who". Overlap handling depends on the diarization stage (Phase 4b).
5. **Short utterances (< ~250 ms) are dropped** by design (conservative noise handling, per PROMT §3.4); padding can recover slightly longer clipped words but not sub-minimum clicks.
6. **Diarization is untouched**: every segment still serializes as `"unknown"` — honestly, without fabricated speaker labels.

## 8. Handoff to Phase 4b (diarization)

1. sherpa-onnx integration (local AAR built from the official release — no Maven artifact ships Android ABIs): pyannote segmentation 3.0 (`sherpa-onnx-pyannote-segmentation-3-0`, INT8 1.5 MB, MIT) + 3D-Speaker embedding (default CAM++ en-voxceleb per Phase 1) + threshold-based fast clustering for unknown speaker counts.
2. Sequential lifecycle in `TranscribeRecordingUseCase`: `asrEngineFactory.release()` after ASR (API now exists), diarization session active alone, then merge speaker turns with transcript segments by time overlap; stable `speaker_N` keys in order of first appearance; `"unknown"` for short/low-confidence turns.
3. Reuse `VerifiedModelStore` for the two diarization artifacts (generic provisioning is already in place); add a third model card on Home or a grouped "diarization models" card.
4. Enable speaker UI: rename (exists), merge (repository exists, UI pending), and provisional-vs-final labels.
5. Extend the benchmark harness with DER on a labelled multi-speaker clip.
