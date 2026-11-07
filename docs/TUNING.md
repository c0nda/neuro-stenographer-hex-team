# Tuning guide — speakers and utterance splitting

The recognition pipeline has three user-tunable parameters, available on the
Home screen under **Recognition tuning** (persisted in DataStore, applied to
the next recognition run — both offline reprocessing and the live pipeline).
Defaults are starting values; they are **not** tuned against measured DER on
Russian data (that measurement is a Phase 7 exit criterion).

## Parameters

### 1. Speaker separation threshold (clustering)
- **What:** cosine-similarity threshold of the fast clustering in sherpa-onnx.
- **Lower (0.3):** embeddings must be very similar to share a speaker →
  more speakers, similar voices split. Use when **different people get the
  same label**.
- **Higher (0.6–0.7):** one person spread across distant turns merges into a
  single cluster. Use when **one person is fragmented into speaker_1/2/3**.
- Default 0.4 (slightly separation-leaning).

### 2. Speaker turn gap (minDurationOff)
- **What:** turns of the same speaker separated by less than this gap are
  merged into one turn.
- **Lower (200 ms):** finer turn alternation in fast dialogues; risk of
  fragments at pauses.
- **Higher (1000 ms):** calmer, longer turns; risk of gluing a short reply of
  the other speaker into the previous turn.
- Default 500 ms (sherpa's own default).

### 3. Utterance pause (ASR segmenter)
- **What:** a silence longer than this starts a new transcript segment.
- **Lower (500 ms):** more, shorter segments.
- **Higher (1500 ms):** fewer, longer segments; a slow speaker's phrase is not
  split mid-thought, but a quick reply within 1.5 s stays in the same segment
  (and then receives a single speaker from the diarization merge).
- Default 700 ms.

## Suggested experiment order

1. Reprocess one problematic recording with the default tuning; note what is
   wrong (over-merge vs over-split of speakers; fragmenting vs gluing of
   segments).
2. Adjust **threshold** first — it drives speaker identity:
   - whole dialogue labelled as one speaker → 0.3, then 0.35;
   - many spurious speakers → 0.5, then 0.6.
3. Then **turn gap**: fast dialogue chopped into fragments → 1000 ms; turns
   of different speakers glued → 200 ms.
4. Then **utterance pause** if segment boundaries feel wrong.
5. Reprocess the same recording after each change and compare (History →
   Reprocess / Transcript → Re-transcribe).

## Measuring instead of eyeballing

For a labelled multi-speaker clip, DER can be measured with the benchmark
harness: push the clip + a reference turn list, run the instrumented
benchmark, and compare DER across settings. A dedicated DER scorer in the
harness is tracked as a Phase 7 item; until it exists, the A/B comparison
above is the honest method.

## Known structural limits (no tuning fixes these)

- The segmentation model (pyannote 3.0) handles at most **3 speakers per
  10 s chunk** and 2 overlapping speakers; a 4+ speaker meeting can confuse
  it regardless of clustering threshold.
- Overlapping speech is **not preserved** through sherpa-onnx's high-level
  diarization API; overlapped regions get a single best-guess speaker.
- The embedding model (CAM++, VoxCeleb-trained) is **English-centric**;
  Russian speaker discrimination may be its weak point. If tuning saturates,
  the next experiment is an alternative embedding checkpoint (e.g. the
  3D-Speaker zh/en CAM++ advanced model) — it drops in behind
  `DiarizationEngineFactory`, but must be re-pinned (size/SHA-256) and
  re-provisioned.
- Short regions (< ~1.5 s) get no reliable embedding and stay `unknown`.
