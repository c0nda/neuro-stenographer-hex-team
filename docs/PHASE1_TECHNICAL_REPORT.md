# Phase 1 — Technical Research Report

**Project:** NeuroStenographer — fully offline Russian ASR, diarization, and timestamped transcripts
**Date:** 2026-11-06
**Status:** Research complete; no ML model has been integrated or benchmarked on a device yet.

---

## 0. How to read this report

Every material claim is tagged with one of these markers:

| Tag | Meaning |
| --- | --- |
| **[V]** | Verified from an official repository, model card, documentation page, or published artifact metadata. |
| **[P]** | Published benchmark result. Reproduced by a third party, **not measured by us**. |
| **[A]** | Assumption or engineering estimate. Must be validated by a benchmark before it is treated as fact. |

This project has **no on-device measurements yet**. All RAM, latency, and RTF figures marked **[A]** are planning estimates, not results. Phase 3 and Phase 7 exist specifically to replace them with measurements.

---

## 1. Executive summary

### Recommended stack

| Component | Recommendation | Why |
| --- | --- | --- |
| **ASR** | **GigaAM v3 CTC**, INT8 ONNX, direct ONNX Runtime integration | Best Russian accuracy among candidates; MIT license; official ONNX export path; native token-frame timestamps; ~225 MB INT8. |
| **ASR runtime** | **ONNX Runtime for Android** (`onnxruntime-android`) | GigaAM v3 is not officially supported by sherpa-onnx; ORT can run the official export directly. CPU EP first. |
| **ASR fallback / comparison** | **Whisper small/multilingual** via ONNX Runtime or sherpa-onnx | MIT; useful accuracy baseline; larger, slower, weaker Russian WER, coarse timestamps. Not the production default. |
| **VAD** | **Silero VAD v4/v5** ONNX, INT8 if accuracy holds | MIT; tiny (0.2–2.2 MB); proven; 16 kHz. |
| **Diarization** | **sherpa-onnx offline diarization**: Pyannote Segmentation 3.0 + 3D-Speaker CAM++ embedding + fast automatic clustering | Proven end-to-end pipeline with unknown speaker count; models total ~30–40 MB INT8; no need to reimplement clustering. |
| **Word alignment** | **GigaAM CTC token-frame timestamps**, grouped into words at `▁`/spaces | Genuine model-derived alignment at ~40 ms encoder-frame resolution; no fabricated uniform distribution. |
| **Alignment fallback** | Russian wav2vec2 CTC forced aligner exported to ONNX (Phase 5 only if accuracy is insufficient) | Stronger acoustic alignment, but heavy (~300 MB INT8 + Viterbi implementation). Not yet verified on Android. |

### Headline numbers

| Item | Estimate | Tag |
| --- | --- | --- |
| Total model storage, INT8, ASR + VAD + diarization | **~258 MB** (ASR 225 MB + diarization ~32 MB + VAD ~1 MB) | [V] component sizes; [A] packaging overhead |
| Native runtime footprint (ORT + sherpa-onnx native libs, per ABI) | **~15–35 MB** APK growth | [A] |
| Peak process RAM during offline ASR | **~500–800 MB** | [A] — critical measurement in Phase 3 |
| Peak process RAM during diarization | **~350–600 MB** | [A] |
| GigaAM v3 CTC speed, Cortex-A53 | **RTFx 1.6** (≈0.63 RTF, slower than real time) | [P] |
| GigaAM v3 CTC speed, modern mid-range phone | **RTF ~0.25–0.5** | [A] |
| Raw PCM16 16 kHz mono storage | **~115 MB per hour** | [V] arithmetic (32 KB/s) |

**Key conclusion:** GigaAM v3 INT8 is viable on mid-range ARM64 devices for **offline/near-real-time** processing, but it is **not guaranteed to be faster than real time on low-end hardware**. The real-time pipeline must be designed to degrade gracefully (bounded queue, dropped/coalesced intermediate audio, final offline reprocessing) rather than promise synchronous streaming. GigaAM v3 has no native streaming mode.

---

## 2. ASR evaluation

### 2.1 GigaAM v3

| Property | Value | Tag |
| --- | --- | --- |
| Developer | Salute Developers (Sber) | [V] |
| Languages | Russian | [V] |
| License | MIT | [V] |
| Architecture | Conformer-based, ~220–240 M parameters | [V] |
| Variants | `ssl`, `ctc`, `rnnt`, `e2e_ctc`, `e2e_rnnt` | [V] |
| Paper | arXiv 2506.01192 | [V] |
| Official ONNX export | Yes, via `model.to_onnx()` | [V] |
| CTC export shape | Single model: `features` `[B, 64, T]` + `feature_lengths`; outputs `log_probs`, `encoded_lengths` | [V] |
| RNNT export shape | Encoder, decoder, joint exported separately | [V] |
| Frontend | 16 kHz, 64 mel bins, `win_length=320`, `hop_length=160`, `n_fft=320`, HTK mel, no mel normalization, `center=false`, `log(clamp(1e-9, 1e9))` | [V] |
| CTC vocabulary | 33 Russian chars + blank (`▁`, Cyrillic letters, `<blk>`) | [V] |
| `e2e_ctc` vocabulary | SentencePiece, 257 classes | [V] |
| Timestamps | Token/frame indices from CTC greedy decode; RNNT records emission frame per token; April 2026 release added native word timestamps | [V] |
| Streaming | **No native streaming**; `transcribe()` applies at most 25 s; longform = VAD segmentation + batched inference | [V] |
| Published INT8 ONNX size | `v3_ctc.int8.onnx` = **224,721,181 B (~224.7 MB)** | [V] |
| Published FP32 ONNX size | `v3_ctc.onnx` = **885,264,128 B (~885 MB)** | [V] |
| `e2e_ctc` INT8 | ~224.9 MB | [V] |
| RNN-T INT8 total | ~225.8 MB (encoder + decoder + joint) | [V] |
| Published speed | CTC **RTFx 1.6**, RNNT **RTFx 1.5** on Cortex-A53 | [P] |

**Notes**

- The INT8 ASR model alone is ~225 MB. FP32 (~885 MB) is not shippable on ordinary phones.
- The official ONNX export is the safest integration path; community conversions exist but are not production dependencies.
- The model input is **log-mel features, not raw samples**. The Kotlin side must implement GigaAM's exact frontend or the model will silently produce garbage. This is the highest-risk integration detail.
- GigaAM's `transcribe()` 25 s limit means long recordings **must** be VAD-chunked. This is not true streaming.

### 2.2 Whisper (alternative / comparison)

| Property | Value | Tag |
| --- | --- | --- |
| License | MIT (code and weights) | [V] |
| Languages | 99, including Russian | [V] |
| Sizes | tiny 39 M, base 74 M, small 244 M, medium 769 M, large ~1550 M | [V] |
| ONNX availability | Yes, multiple community/official-ish exports and `optimum` export | [V] |
| Runtime options | ONNX Runtime, sherpa-onnx, whisper.cpp | [V] |
| Streaming | No native streaming; fixed 30 s windows | [V] |
| Timestamps | Cross-attention / DTW-based; word timestamps require extra alignment | [V] |
| Russian accuracy | Generally below GigaAM v3 on Russian benchmarks | [P] |
| INT8 | Supported by ORT quantization; quality/size trade-off must be measured | [V]/[A] |

**Role in this project:** comparison baseline only. Whisper small INT8 is roughly the same storage class as GigaAM v3 but is multilingual, has weaker Russian accuracy, and its timestamp machinery is more complex to expose correctly. It must not be silently substituted for GigaAM.

### 2.3 Decision

Use **GigaAM v3 CTC INT8** as the primary ASR. Keep RNNT as an optional higher-accuracy offline variant if INT8 CTC accuracy is insufficient. Keep Whisper behind the same `AsrEngine` interface for evaluation builds, not as the default.

**Why CTC first rather than RNNT:** CTC is a single ONNX graph, simpler to decode (greedy + collapse repeats), and produces token frames directly. RNNT needs three graphs, a beam/greedy transducer loop, and more state management. Start with the simpler path; add RNNT only if WER demands it.

---

## 3. Inference runtime evaluation

### 3.1 ONNX Runtime for Android

| Property | Value | Tag |
| --- | --- | --- |
| License | MIT | [V] |
| ARM64 support | Yes | [V] |
| CPU EP | Yes, baseline | [V] |
| INT8 | Yes (dynamic/QDQ depending on export) | [V] |
| NNAPI EP | Yes, but operator coverage for this graph is **unverified** | [V]/[A] |
| Thread control | `intra_op_num_threads`, `inter_op_num_threads` | [V] |
| Model loading | `OrtEnvironment`, `OrtSession`, direct `ByteBuffer` / `OnnxTensor` | [V] |
| APK impact | Native libs per ABI; ~10–20 MB order of magnitude | [A] |

**Recommendation:** pin a specific stable ORT version in Phase 3 after checking Maven Central, and record the exact version in the version catalog. Do not guess a version now.

### 3.2 sherpa-onnx

| Property | Value | Tag |
| --- | --- | --- |
| License | Apache-2.0 | [V] |
| GigaAM support | Official docs list **GigaAM v2 NeMo transducer** Russian (`sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19`); **no official GigaAM v3** as of the current docs | [V] |
| GigaAM v3 via sherpa-onnx | Only unverified community conversion (`Smirnov75/GigaAM-v3-sherpa-onnx`), low usage | [V] |
| Whisper support | Yes, offline | [V] |
| Silero VAD | Yes, built in | [V] |
| Offline diarization | Yes: pyannote segmentation + embedding + fast clustering, unknown count supported | [V] |
| Streaming | Yes for supported streaming models; **not applicable to GigaAM v3** | [V] |

**Recommendation:** use sherpa-onnx for **VAD and diarization** (proven high-level APIs), and ONNX Runtime for **GigaAM v3 ASR**. This means two native runtimes in the APK. That is accepted for now because:

1. GigaAM v3 has no official sherpa-onnx path.
2. Reimplementing pyannote powerset decoding, embedding extraction, and clustering in Kotlin is expensive and error-prone.
3. Both libraries can coexist; they use different JNI packages. APK size and memory must be measured in Phase 7.

All ML access is hidden behind interfaces, so either runtime can be replaced later.

**Risk:** dual runtimes increase APK size and may increase peak memory if sessions are alive simultaneously. Mitigation: strictly sequential model lifecycle (load ASR → run → release → load diarization), with explicit `close()` and arena disposal.

### 3.3 NNAPI / GPU

NNAPI is **not** a baseline. GigaAM's dynamic shapes, Conformer ops, and INT8 graph may fall back to CPU or fail partitioning. GPU/NPU is only considered in Phase 7 if a measured benchmark shows a win. No assumption that accelerator > CPU.

---

## 4. VAD evaluation

| Property | Value | Tag |
| --- | --- | --- |
| Model | Silero VAD | [V] |
| License | MIT | [V] |
| Sampling rate | 16 kHz (selected export); official v4/v5 also support 8 kHz | [V] |
| ONNX size (k2-fsa export) | 629 KB FP32 / **208 KB INT8** | [V] |
| ONNX size (official v4/v5) | 1.72 MB / 2.22 MB | [V] |
| Streaming | Frame-based, stateful; suitable for continuous capture | [V] |
| Android integration | Direct ORT session with recurrent state, or sherpa-onnx built-in | [V] |

**Recommendation:** Silero VAD, INT8 k2-fsa export initially. It runs on 512-sample (32 ms) frames with internal state. In the offline pipeline, VAD regions are padded by ~200–300 ms on each side and merged when gaps are short, so ASR does not clip word onsets.

VAD outputs are used to:

- Skip silence before ASR (major CPU saving).
- Define chunk boundaries for GigaAM's 25 s limit.
- Produce speech regions whose timestamps are mapped back to the original recording by adding the chunk's absolute sample offset.

VAD does **not** provide speaker identity or overlap labels.

---

## 5. Diarization evaluation

### 5.1 Candidates

| Candidate | License | Size / params | Strength | Weakness |
| --- | --- | --- | --- | --- |
| **Pyannote Segmentation 3.0** | MIT, but **gated on Hugging Face** (must accept conditions) | sherpa-onnx conversion: **5.7 MB FP32 / 1.5 MB INT8** | Strong segmentation, powerset overlap modeling, 10 s chunks | Gated download; 10 s window; max 3 speakers/chunk, max 2 overlapping; not an embedding model |
| **3D-Speaker embeddings** | Apache-2.0 | CAM++ 7.2 M params; ERes2Net-base 6.61 M; ONNX: CAM++ zh 27 MB, CAM++ en-voxceleb 28.2 MB, ERes2Net-base 37.8 MB, ERes2NetV2 68.1 MB, ERes2Net-large 110.7 MB | Good speaker discrimination; multiple sizes | No verified Russian-specific checkpoint; language mismatch may hurt |
| **sherpa-onnx offline diarization** | Apache-2.0 (library) | Combines the above | End-to-end; automatic cluster count via threshold; documented RTF 0.11–0.45 on desktop | Overlap exposure and per-segment uncertainty need verification |

### 5.2 Recommended pipeline

```
audio (16 kHz mono)
  → pyannote segmentation 3.0 (10 s sliding chunks)
  → local speaker activity / powerset logits
  → 3D-Speaker CAM++ embedding per local speaker turn
  → fast clustering (unknown K, threshold-based)
  → global speaker labels + turn boundaries
  → merge with ASR segments by time overlap
  → segments with speaker_id or "unknown"
```

Default embedding: **CAM++ en-voxceleb (28.2 MB)**. Evaluate **ERes2Net-base (37.8 MB)** if DER is poor. Both are Apache-2.0.

**Unknown speaker count:** use clustering threshold, not a fixed K. Segments whose embedding is too short (< ~0.5 s effective speech) or whose cluster confidence is below threshold are assigned `"unknown"` rather than forced into a cluster.

**Stability:** assign `speaker_1..N` in order of first appearance after clustering, then persist the mapping. Do not renumber on later passes unless the user explicitly merges/splits.

### 5.3 Overlapping speech — honest limitation

Pyannote Segmentation 3.0 models overlap in its powerset (up to 2 simultaneous speakers in a 10 s window). However, **sherpa-onnx's high-level offline diarization output is typically a sequence of single-speaker turns**, and it is not verified that overlap regions are preserved through its API.

Therefore:

- **Phase 4 target:** correct non-overlapping speaker turns + automatic K + `unknown`.
- **Overlap preservation is a stretch goal** requiring custom powerset decoding or a fork of the diarization post-processing.
- The JSON contract *permits* overlapping intervals but **does not require inventing them**. We will not fabricate overlap.

This limitation will be documented in the app and in the Phase 4 report.

### 5.4 Real-time diarization

Offline diarization is **not** a streaming algorithm. It performs global clustering and can revise earlier labels. For real-time:

- Use short-term embeddings and an online/provisional cluster assignment.
- Mark segments provisional in internal metadata.
- Re-run offline diarization on stop and replace provisional labels.
- Never present provisional labels as final.

Do not claim sherpa-onnx offline diarization is a streaming solution.

---

## 6. Word-level timestamps and forced alignment

### 6.1 Primary mechanism: GigaAM token frames

GigaAM's CTC greedy decode returns token IDs and the encoder frame at which each token was emitted. The encoder frame shift is:

```
frame_shift_s = audio_length_samples / 16000 / encoded_seq_len
```

With GigaAM's subsampling this is approximately **40 ms per encoder frame**. Words are formed by grouping tokens at `▁` / spaces. A word's start is the first token's frame start; its end is the last token's frame end. This is a **genuine model-derived alignment**, not a uniform distribution — but its resolution is one encoder frame (~40 ms), and it is **estimated alignment**, not a dedicated forced-alignment verification.

Internal representation:

```kotlin
data class WordTimestamp(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float?,        // if available
    val alignmentSource: AlignmentSource // CTC_FRAME, RNNT_EMISSION, FORCED_ALIGNER
)
```

The public JSON never exposes `alignmentSource` or word data; it exposes only segment-level `speaker_id`, `start_ms`, `end_ms`, `text`.

### 6.2 Refinement options (Phase 5)

| Option | Size | Feasibility | Notes |
| --- | --- | --- | --- |
| GigaAM token frames | 0 extra | **High** | Default. ~40 ms resolution. |
| Russian wav2vec2 CTC forced aligner (e.g. XLSR-53 Russian) | ~300 MB INT8, ~1.2 GB FP32 | Medium | Viterbi alignment against known transcript; strong accuracy; heavy storage/RAM; ONNX + Kotlin Viterbi; **not verified on Android** |
| WhisperX-style alignment | Same wav2vec2 dependency | Medium | Requires phoneme model + language model; overkill |
| Vosk small Russian | ~50 MB | Low | Kaldi runtime; lower ASR quality; alignment only as auxiliary; adds another runtime |

**Recommendation:** ship GigaAM CTC frame alignment first. Add a forced aligner only if measured word-boundary error exceeds an agreed target (e.g. median error > 80–100 ms on a Russian evaluation set). Do not add a second heavy model preemptively.

### 6.3 Verified vs estimated

- **Verified alignment:** produced by a dedicated aligner constrained by the known transcript, with an acoustic model trained for alignment.
- **Estimated alignment:** model token timing (GigaAM CTC/RNNT). Accurate enough for segment boundaries and reasonable word highlighting, but not exact.
- The public contract has no field for this distinction. The app will show it internally / in export metadata **without changing the required JSON schema** (e.g., a separate sidecar or Room column, not an added public JSON field).

---

## 7. Storage and RAM estimates

### 7.1 Model storage (INT8, recommended set)

| Model | Size | Tag |
| --- | --- | --- |
| GigaAM v3 CTC INT8 | 224.7 MB | [V] |
| Silero VAD INT8 (k2-fsa) | 0.2 MB | [V] |
| Pyannote Segmentation 3.0 INT8 | 1.5 MB | [V] |
| 3D-Speaker CAM++ en-voxceleb | 28.2 MB | [V] |
| **Total models** | **~254.6 MB** | arithmetic |
| Packaging / tokenizer / config overhead | ~2–5 MB | [A] |
| **On-device model footprint** | **~258 MB** | [A] |
| Native runtime libs (ORT + sherpa-onnx, per ABI) | ~15–35 MB | [A] |
| APK/DEX/Compose | ~10–20 MB | [A] |
| **Approx. installed total** | **~285–315 MB** | [A] |

Optional additions:

- Whisper small INT8 for comparison: +~250 MB.
- RNNT variant instead of CTC: roughly same total (~226 MB).
- ERes2Net-base instead of CAM++: +~10 MB.
- wav2vec2 Russian aligner: +~300 MB INT8.

**Storage strategy:** do not put 225 MB into `assets/` and do not commit binaries to git. Provision models into app-specific external files storage via a Gradle download task (development), `adb push` (testing), or a first-run model manager / Play Asset Delivery (production). The app must remain fully offline **after** models are provisioned. This is a product decision to confirm with the user.

### 7.2 Audio storage

| Format | Size per hour | Tag |
| --- | --- | --- |
| PCM16 16 kHz mono WAV | ~115.2 MB | [V] arithmetic |
| AAC/Opus compressed copy (optional) | ~30–60 MB | [A] |

Preserve the original recording when practical. If the source is 44.1/48 kHz stereo, store the original and resample a working 16 kHz mono copy for inference; resampling must be documented and timestamp-mapped.

### 7.3 RAM estimates

| Phase | Estimate | Tag |
| --- | --- | --- |
| App baseline + Compose | 80–150 MB | [A] |
| Silero VAD only | +5–15 MB | [A] |
| GigaAM v3 CTC INT8 session (weights + ORT overhead) | +300–450 MB | [A] |
| GigaAM inference activations (≤25 s chunk) | +100–250 MB | [A] |
| **Peak during ASR** | **500–800 MB** | [A] |
| Pyannote + CAM++ diarization | +200–350 MB | [A] |
| **Peak during diarization** | **350–600 MB** | [A] |

**Rules:**

- Never keep ASR and diarization sessions alive simultaneously in the offline pipeline.
- Load lazily, close deterministically, and consider `OrtSession.SessionOptions` arena settings.
- 4 GB RAM devices are the practical lower bound; 6 GB+ recommended. This must be verified on real hardware.

---

## 8. Integration challenges and risks

| # | Challenge | Impact | Mitigation |
| --- | --- | --- | --- |
| 1 | **GigaAM feature frontend must match exactly** (64 mel, HTK, no norm, center=false, specific padding) | Silent garbage output | Unit-test log-mel output against a reference NumPy/Python fixture; gate Phase 3 on it |
| 2 | **225 MB model provisioning** | APK/Play limits; onboarding | Model manager + checksum; dev sideload; document first-run download or manual import |
| 3 | **No official sherpa-onnx GigaAM v3 support** | Must write CTC decode + frontend in Kotlin | Keep `AsrEngine` interface; use official ONNX export; do not rely on community conversion |
| 4 | **No true streaming** | "Real-time" is chunked/near-real-time | VAD chunks + overlap + stabilization; label partial results; final offline pass |
| 5 | **Likely RTF ≥ 0.3 and possibly >1 on low-end** | Backlog, thermal throttling | Bounded queue, process only speech, limit threads, drop/coalesce intermediate buffers, degrade to record-then-process |
| 6 | **Dual runtimes (ORT + sherpa-onnx)** | APK size, native lib conflicts, memory | Sequential lifecycle; per-ABI splits; measure; fallback to single runtime if needed |
| 7 | **Pyannote weights are gated** | Cannot auto-download without HF token | Use sherpa-onnx's converted model artifacts with clear license attribution; document acceptance of terms |
| 8 | **Overlap not preserved by high-level diarization** | Contract supports overlap but output may not | Document limitation; custom powerset decoding as stretch |
| 9 | **No Russian-specific speaker embedding verified** | Worse DER on Russian | Evaluate CAM++ vs ERes2Net on Russian data; threshold tuning |
| 10 | **No on-device benchmarks yet** | All performance claims provisional | Phase 3/7 benchmark harness; publish measured numbers |
| 11 | **Timestamp drift across chunks** | Misaligned transcript | Integer sample-offset mapping; never reset clock per chunk; test with synthetic click track |
| 12 | **Room migrations + long transcripts** | Data loss / slow UI | Versioned migrations; paging; transactions for speaker reassignment |
| 13 | **Model licensing redistribution** | Legal risk | GigaAM MIT (keep notices), Silero MIT, 3D-Speaker Apache-2.0, pyannote MIT + gated terms, sherpa-onnx Apache-2.0, ORT MIT |
| 14 | **INT8 accuracy loss** | WER regression | Evaluate FP32 vs INT8 on Russian test set; per-channel/QDQ if needed; RNNT fallback |
| 15 | **Thermal throttling on long recordings** | RTF worsens over time | Chunked offline processing, progress persistence, resumable jobs |

---

## 9. Proposed architecture

### 9.1 Modules / packages

Start with a single `:app` Gradle module (fast iteration) but enforce layer boundaries by package and by interfaces. Split into Gradle modules in Phase 7 only if build times justify it.

```
com.vorontsov.neurostenographer
├── presentation/
│   ├── navigation/
│   ├── home/
│   ├── recording/
│   ├── transcript/
│   ├── speakers/
│   ├── history/
│   └── theme/
├── domain/
│   ├── model/            Recording, Transcript, Segment, Word, Speaker, ProcessingState
│   ├── repository/       interfaces only
│   ├── usecase/
│   └── transcript/       TranscriptJsonContract, validation
├── data/
│   ├── local/            Room entities, DAOs, database, migrations
│   ├── datastore/        settings
│   ├── file/             audio file store, model store
│   └── repository/       implementations
├── audio/
│   ├── capture/          AudioRecordSource
│   ├── io/               WavWriter, WavReader, chunk reader
│   ├── dsp/              resampler, mel frontend
│   └── vad/              SileroVad
├── ml/
│   ├── asr/              AsrEngine, GigaAmCtcEngine, GigaAmRnntEngine, WhisperEngine
│   ├── diarization/      DiarizationEngine
│   ├── embedding/        SpeakerEmbeddingEngine
│   ├── clustering/       agglomerative / fast clustering
│   ├── alignment/        CtcTokenizer, word grouper, optional aligner
│   └── runtime/          OrtSessionFactory, model lifecycle
└── di/                   Hilt modules
```

### 9.2 Key interfaces (domain-owned)

```kotlin
interface AsrEngine {
    suspend fun transcribe(
        audio: AudioChunkSource,
        options: AsrOptions
    ): AsrResult // text, tokens, word timings, confidence
}

interface VadEngine {
    fun reset()
    fun process(frame: ShortArray): List<SpeechRegion>
    fun flush(): List<SpeechRegion>
}

interface DiarizationEngine {
    suspend fun diarize(audio: AudioChunkSource): List<SpeakerTurn>
}

interface SpeakerEmbeddingEngine {
    suspend fun embed(pcm: ShortArray, sampleRate: Int): FloatArray
}

interface TranscriptRepository {
    fun observeRecordings(): Flow<List<Recording>>
    suspend fun saveTranscript(recordingId: Long, segments: List<Segment>)
    suspend fun reassignSpeaker(segmentIds: List<Long>, speakerId: String)
}
```

Business logic depends only on these interfaces. Hilt binds ORT/sherpa-onnx implementations. No `Context`, `Bitmap`, Compose, or ONNX type leaks into `domain`.

### 9.3 Threading model

- `AudioRecord` on a dedicated high-priority thread; writes PCM to a bounded ring buffer and to disk.
- VAD on a dedicated single-thread dispatcher (cheap, sequential).
- One ASR inference worker (no concurrent ASR + diarization).
- Bounded `Channel(capacity = N)` with explicit overflow policy: drop oldest intermediate (never drop finalized segments) or block producer with backpressure; log drops.
- Room/DataStore on `Dispatchers.IO`.
- UI observes `StateFlow`; main thread never touches inference or file I/O.

### 9.4 Unified transcript contract

Public JSON is exactly:

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
```

Rules enforced by a dedicated serializer + validator:

- Integer ms; `start_ms <= end_ms`.
- Sorted by `start_ms` (stable tie-break by `end_ms`, then insertion order).
- Speaker IDs stable within a recording; `"unknown"` allowed.
- No extra fields.
- Overlaps preserved; never forced non-overlapping.
- Same serializer for real-time partials and offline finals.

Provisional/final is internal metadata only (`SegmentEntity.isProvisional`). Partial updates replace by stable segment key (`segmentId`), not append.

### 9.5 Room schema

| Entity | Key fields |
| --- | --- |
| `RecordingEntity` | id, filePath, durationMs, createdAt, status, speakerCount, sampleRate, channels, originalPath |
| `TranscriptEntity` | id, recordingId (unique FK), createdAt, updatedAt, jsonSnapshot, version |
| `SegmentEntity` | id, transcriptId FK, speakerId, startMs, endMs, text, isProvisional, alignmentSource |
| `WordEntity` | id, segmentId FK, text, startMs, endMs, confidence, alignmentSource |
| `SpeakerEntity` | id, recordingId FK, speakerKey (`speaker_1`/`unknown`), displayName, color, embedding? (blob, optional) |

- Foreign keys + indices on `recordingId`, `transcriptId`, `segmentId`, `startMs`.
- Speaker reassignment and transcript updates in `@Transaction`.
- Explicit `Migration` objects from v1 onward; no destructive fallback in release.

### 9.6 Model lifecycle

`ModelManager` with states: `NotInstalled → Verifying → Loading → Ready → Running → Released`. Checksums verified before load. ASR and diarization sessions are not held simultaneously. Model download/provisioning is isolated from the inference layer.

---

## 10. Development roadmap

| Phase | Deliverable | Exit criteria |
| --- | --- | --- |
| **1. Research** | This report | User approves stack and provisioning approach |
| **2. Infrastructure** | Gradle deps/plugins, Hilt, Room, DataStore, navigation, audio recording (PCM16/16 kHz/mono, pause/resume/stop), WAV storage, import, Home/Recording/History skeleton | `assembleDebug` green; unit tests green; manual record→save→list works |
| **3. ASR** | GigaAM v3 CTC INT8 via ORT, exact frontend, CTC greedy decode, segment JSON, benchmark harness | Reference frontend test passes; offline transcription of a real Russian clip produces non-empty correct text; JSON validator passes; measured RTF/RAM recorded |
| **4. VAD + diarization** | Silero VAD, pyannote + CAM++ clustering via sherpa-onnx, unknown speakers, manual speaker edit | VAD reduces inference on silence; multi-speaker sample gets plausible stable labels; DER measured on a small labeled set; unknowns handled |
| **5. Word alignment** | GigaAM token-frame word timings, internal Word storage, accuracy evaluation | Word timings stored and rendered; error measured; decide whether wav2vec2 aligner is needed |
| **6. Real-time** | Streaming capture, incremental ASR, provisional segments, stabilization, final reprocess | Partials update in place (no duplicates); bounded queue verified under load; main thread never blocked |
| **7. Optimization** | Profiling, INT8 tuning, thread limits, API 26 device tests, offline verification, docs | Measured benchmarks published; APK/model footprint documented; airplane-mode end-to-end test passes |

**Immediate Phase 2 scope:** dependencies (Hilt, Room, DataStore, Navigation, Coroutines/Flow, kotlinx-serialization), package skeleton, `Application`, DI modules, Room entities/DAOs/database, DataStore settings, `AudioRecord` capture + WAV writer, import, five-screen Compose navigation skeleton, then build + tests.

---

## 11. Benchmark and test plan

### 11.1 Metrics

| Metric | Definition | Tooling |
| --- | --- | --- |
| WER | Levenshtein over normalized Russian text (case/punctuation-normalized) | Kotlin test harness + small labeled corpus |
| DER | Diarization error rate with collar, incl. false alarm / missed / confusion | Labeled multi-speaker clips; custom scorer |
| RTF | processing time / audio duration | `System.nanoTime` around inference, per stage |
| Latency | time from utterance end to final segment | Timestamped pipeline events |
| Peak RAM | `Debug.getMemoryInfo` / `ActivityManager.MemoryInfo` + process stats | Instrumentation benchmark |
| CPU | per-core utilization during inference | `adb shell top` / perfetto |
| Timestamp error | predicted vs reference word boundaries | Labeled forced-alignment reference |

### 11.2 Test matrix

Single speaker; multi-speaker with unknown K; 1–3 s utterances; background noise; overlapping speech; long recordings (≥30 min); silence-only; interrupted processing (process death, cancel, low storage).

### 11.3 JSON contract tests

Valid JSON; exact field names/types; integer ms; `start <= end`; sorted; stable IDs; overlaps allowed and preserved; no extra fields; same output from real-time and offline serializers.

**No benchmark numbers will be invented.** Until measured, every performance figure stays tagged **[A]** or **[P]**.

---

## 12. Open decisions for the user

1. **Model provisioning:** first-run download vs. Play Asset Delivery vs. manual import? All keep inference offline; only installation differs.
2. **Minimum device target:** 4 GB RAM acceptable, or require 6 GB+?
3. **Accuracy vs. latency profile default:** Balanced (CTC INT8) vs. Accuracy (RNNT/FP32 with higher RAM)?
4. **Overlap priority:** is preserving overlapping speech a hard requirement for v1, or an accepted Phase 4 limitation?
5. **Whisper comparison build:** include a switchable Whisper baseline in debug builds only, or omit entirely?

Proposed defaults: manual/asset-delivery provisioning, 4 GB minimum with documented 6 GB recommendation, Balanced default, overlap documented as a limitation for v1, Whisper behind a debug-only engine switch.

---

## 13. Verified facts vs assumptions — quick index

**Verified [V]:** GigaAM v3 license/size/variants/ONNX export/frontend/no streaming; INT8 artifact sizes; Silero VAD sizes/license; pyannote/3D-Speaker licenses and sherpa-onnx sizes; sherpa-onnx GigaAM v2-only official support; sherpa-onnx offline diarization capabilities; ORT/sherpa-onnx licenses.

**Published benchmarks [P]:** GigaAM Cortex-A53 RTFx 1.6/1.5; sherpa-onnx desktop diarization RTF 0.11–0.45; Whisper Russian accuracy below GigaAM.

**Assumptions to measure [A]:** all phone RAM peaks; phone RTF; APK/native lib sizes; INT8 WER/DER impact; overlap preservation through sherpa-onnx; wav2vec2 aligner feasibility on Android; CAM++ quality on Russian; actual battery/thermal behavior.

---

## 14. Sources

- GigaAM repository — https://github.com/salute-developers/GigaAM
- GigaAM v3 model card — https://huggingface.co/ai-sage/GigaAM-v3
- GigaAM v3 ONNX exports (sizes) — https://huggingface.co/istupakov/gigaam-v3-onnx
- `onnx-asr` benchmarks — https://github.com/istupakov/onnx-asr
- GigaAM paper — https://arxiv.org/abs/2506.01192
- sherpa-onnx speaker diarization models — https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html
- sherpa-onnx Silero VAD — https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html
- sherpa-onnx GigaAM v2 Russian model — https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
- Silero VAD — https://github.com/snakers4/silero-vad
- Pyannote segmentation 3.0 — https://huggingface.co/pyannote/segmentation-3.0
- 3D-Speaker — https://github.com/modelscope/3D-Speaker
- ONNX Runtime — https://onnxruntime.ai/docs/
- Whisper — https://github.com/openai/whisper
