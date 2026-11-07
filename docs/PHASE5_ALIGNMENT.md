# Phase 5 — Word-level timestamps and forced alignment

Status: **CTC-frame word alignment (estimated) shipped since Phase 3; this phase adds word rendering, timestamp-error measurement tooling, and the documented decision gate for a forced aligner. No forced aligner is integrated — deliberately, per the Phase 1 recommendation and PROMT §3.5 ("If exact word alignment cannot be implemented with the selected models, document the limitation and provide a suitable alternative").**

---

## 1. What is implemented

| Area | State |
| --- | --- |
| Word timestamps source | GigaAM CTC token-frame emission frames, ~40 ms encoder resolution — genuine model-derived timing, **not** a uniform distribution across words (decoder + frontend golden-tested in Phase 3) |
| Persistence | `WordEntity` per segment (text, startMs, endMs, confidence, `alignmentSource`); loaded with segments via `observeSegments` — unchanged |
| Public JSON | Words are **not** in the public transcript JSON (per PROMT §4: word data is internal; only segment-level `speaker_id/start_ms/end_ms/text` is public) — unchanged |
| Internal distinction | `AlignmentSource.CTC_FRAME` = **estimated**; `FORCED_ALIGNER` = **verified** (reserved; UI tag logic already handles both) |
| UI rendering | `TranscriptScreen`: expandable per segment ("Word timestamps"), word chips with per-word start timestamps, tap-to-seek playback, active-word highlight synchronized with playback position |
| Measurement tooling | `domain/benchmark/TimestampError`: reference parsing (`word<TAB>startMs<TAB>endMs`), WER-normalized greedy matching, median/mean/p90 absolute start/end error, within-40/80/120 ms fractions, unmatched-word counts; 8 unit tests |
| Benchmark harness | `GigaamAsrBenchmarkTest` reads optional `<clip>.words.txt` labeled boundaries and emits a `word_timestamp_error` block per clip |

## 2. Decision gate for a forced aligner (honest status)

Phase 1 §6 recommended adding a Russian wav2vec2 CTC forced aligner (~300 MB INT8, plus a Kotlin Viterbi implementation, **not verified on Android**) **only if** measured word-boundary error of the CTC-frame alignment exceeds the agreed target — **median start error > 80–100 ms** on a labeled Russian evaluation set.

That measurement requires a device run with labeled clips — not possible in this environment. Therefore:

- **No second heavy model is integrated preemptively** (PROMT engineering rules 3 and "do not load every heavy model simultaneously").
- The tooling to make the decision is now in place: provision the models, push clips + `<clip>.words.txt` references, run the benchmark, read `word_timestamp_error.median_start_error_ms`.
- If the gate triggers, the integration path is: `AlignmentSource.FORCED_ALIGNER` engine behind the existing `AsrEngine`-adjacent interface, word re-timing pass after ASR (before diarization merge), UI tag flips to "verified" automatically.

Until then, all word timings are honestly labeled **estimated**.

## 3. Verification performed

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

- `BUILD SUCCESSFUL` for all four tasks.
- JVM tests: **87 passed, 0 failed** (+8 `TimestampErrorTest`).
- Lint: 0 errors, 18 warnings (unchanged noise).

## 4. Limitations

1. **CTC-frame resolution is ~40 ms** and emission-frame timing can jitter around word boundaries; this is acceptable for tap-to-seek and highlight, unproven for subtitle-grade alignment.
2. **No measured word-boundary error yet** — the decision gate is documented but not evaluated; a DER-style labeled Russian set is also still needed for Phase 4b tuning.
3. Chunk-boundary words may carry slightly degraded timing (no overlap-and-stitch stabilization).
4. Real-time word highlighting during recording belongs to Phase 6.

## 5. Handoff

- **Phase 6 (real-time):** provisional segments updating in place; word chips reuse the same rendering.
- **Phase 7 (optimization):** run the benchmark with `.words.txt` references; if the gate triggers — forced aligner integration; also ABI splits and threshold tuning.
