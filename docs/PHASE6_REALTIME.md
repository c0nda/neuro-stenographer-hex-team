# Phase 6 — Real-Time Processing (live transcription while recording)

Status: **code complete, verified by build, JVM tests and lint. The pipeline has not run on a device in this environment; latency, RTF under recording load and drop behaviour are unmeasured.**

---

## 1. What is implemented (PROMT §5)

| Requirement | Implementation |
| --- | --- |
| Continuous audio capture | PCM tap on the existing audio capture thread (`AudioRecorder.onPcmBlock`), wired by `RecordingSessionManager.setPcmBlockListener` |
| Small audio buffers | 128 ms capture blocks; `RegionAssembler` keeps audio from (oldest pending region − 1 s pad) only; silence between regions is discarded continuously |
| VAD-based speech detection | Reuses the Phase 4a Silero engine in streaming mode (`VadRegionTracker.drainCompleted()` — closed regions delivered mid-stream, open region untouched). Without the VAD model the real-time path is disabled (honest UI notice) — recording is unaffected |
| Incremental ASR inference | Completed VAD regions transcribed utterance-by-utterance on a single background worker; regions > 20 s split at quietest frames via the shared `ChunkPlanner` |
| Partial transcript updates | Provisional segments in a `StateFlow`, keyed by region id — updates replace in place, never duplicate; shown live on the Recording screen |
| Speaker diarization (provisional) | CAM++ embedding per region ≥ 1.5 s through `SherpaEmbeddingExtractor` + `OnlineSpeakerClusterer` (cosine threshold 0.5, creation-order-stable `speaker_N`) |
| Provisional speaker assignments | `currentSpeaker` flow; provisional labels are revised by the full offline diarization after the stop — never presented as final (PROMT §3.4) |
| Timestamp generation | Absolute ms from integer sample offsets — same arithmetic as the offline pipeline |
| Final segment stabilization | Recording screen states explicitly that the preview is provisional; the existing offline transcription pass produces the authoritative transcript/speakers/timestamps (PROMT §5 "allow offline reprocessing") |
| Bounded queues / backpressure | Region jobs go through a capacity-4 channel; a full queue drops the OLDEST unprocessed region with a log entry and counter — provisional data only, offline pass is authoritative |
| No main-thread blocking | Capture thread only copies PCM and runs VAD (< 1 ms per 32 ms frame); all inference runs on one `Default`-dispatcher worker; UI observes StateFlows |
| No concurrent heavy inference | VAD (cheap, inline) + one worker doing ASR → embedding strictly sequentially |

## 2. Honest framing (PROMT §3.2)

GigaAM v3 has **no native streaming mode**. This pipeline is chunk-based
processing of completed VAD regions — in the terminology of the requirements,
"chunk-based processing with overlapping audio windows and text
stabilization", not streaming inference. Regions are processed whole
(utterance-level granularity); overlapping-window stitching inside a region is
only used to respect the model's ~25 s window for very long speech. The UI
and this document do not claim native streaming.

## 3. Memory and lifecycle during recording

Unlike the offline pipeline (strictly sequential ASR → diarization), the
real-time pipeline keeps resident simultaneously: ASR session (~225 MB), Silero
VAD (~2 MB) and the sherpa embedding extractor (~45 MB including its ORT
runtime copy), plus the region ring buffer (bounded, see §1). This is the
conscious trade-off Phase 1 §3.2 anticipated for real-time operation; peak RAM
during recording is a Phase 7 measurement. The offline pipeline remains
sequential — after a recording stops, ASR is released before diarization loads.

If the user starts recording while an offline transcription job holds the ASR
engine, both share the factory singleton; the offline `release()` may close
the session under the streaming pipeline, which then reloads it on the next
`getOrCreate()`. Correct but expensive; noted for Phase 7 (mutual exclusion).

## 4. Files

- New: `domain/realtime/Streaming.kt` (contract + models), `RegionAssembler.kt`, `OnlineSpeakerClusterer.kt`; `ml/diarization/SherpaEmbeddingExtractor.kt`; `data/realtime/ChunkedStreamingTranscriber.kt`; `di/RealtimeModule.kt`; tests `OnlineSpeakerClustererTest`, `RegionAssemblerTest` (+2 `VadRegionTrackerTest` drain cases).
- Modified: `AudioRecorder` (PCM tap), `RecordingSessionManager` (listener), `VadRegionTracker`/`VadEngine`/`SileroVadEngine` (`drainCompleted`/`drainRegions`), `RecordingViewModel`/`RecordingScreen` (live transcript, current speaker, backlog notice), `strings.xml`.

## 5. Verification

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

- `BUILD SUCCESSFUL` for all four tasks.
- JVM tests: **103 passed, 0 failed** (+15: clusterer 7, assembler 6, VAD drain 2).
- Lint: 0 errors, 18 warnings (unchanged noise).

## 6. Limitations (honest)

1. **No on-device run.** Thread interactions (capture thread → assembler/VAD, worker races) are reasoned and unit-tested at the logic level, but real-device behaviour — latency from utterance end to provisional segment, drop rates on slow hardware, thermal effects — is unmeasured.
2. **Utterance-level latency.** A provisional segment appears only after the VAD closes the region (+ inference time). Mid-utterance partial words are not shown — that would require a streaming-capable model GigaAM v3 does not provide.
3. **Provisional speakers** use an untuned 0.5 cosine threshold and per-region embeddings; short regions (< 1.5 s) get no speaker. The offline diarization pass is authoritative.
4. **Drops are visible in logs only** (`StreamingTranscriber` tag); no UI counter yet — the backlog indicator shows queue pressure.
5. **Pause during recording** pauses the PCM tap naturally (no blocks); VAD state persists across pause/resume (speech spanning a pause stays one region — acceptable v1, noted).
6. **No provisional data in Room** by design; if the process dies during recording, only the WAV survives (same guarantee as Phase 2) and the offline pass recovers everything.

## 7. Handoff to Phase 7

- Measure: live latency (utterance end → provisional segment), drop counts, peak RAM during recording (ASR+VAD+extractor resident), RTF under capture load.
- Tuning: `OnlineSpeakerClusterer` threshold against DER; VAD hysteresis from §4a.
- Engineering: mutual exclusion between streaming pipeline and offline `release()`; ABI splits (APK is ~183 MB); stale-recording recovery on startup (Phase 2 known gap); sliding-window diarization for long files.
