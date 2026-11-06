# Android Application: Offline Speech Recognition and Speaker Diarization

## 1. Role and Objective

Act as a Senior Android Developer and Machine Learning Engineer specializing in high-performance on-device audio processing and mobile ML inference.

Your task is to develop a native Android application for fully offline Russian speech recognition, speaker diarization, and timestamp generation.

The application must work entirely on the user's device. No cloud APIs, remote inference servers, or external speech recognition services are allowed.

The primary development priorities are:

1. High speech recognition accuracy.
2. Low inference latency.
3. Low RAM consumption.
4. Support for an unknown number of speakers.
5. Accurate timestamps.
6. Reliable operation on ordinary Android smartphones.

Do not create a mock application or simulate ML functionality. Implement actual model inference and clearly document any components that cannot yet be integrated.

## 2. Platform and Technology Stack

Use:

- Kotlin.
- Native Android.
- Jetpack Compose.
- Material 3.
- Gradle Kotlin DSL.
- Minimum Android API: 26.
- Kotlin Coroutines and Flow.
- Room.
- DataStore.
- Hilt.
- AudioRecord.
- ONNX Runtime and/or sherpa-onnx.
- C++/JNI only when necessary for performance or native library integration.

Architecture: Clean Architecture + MVVM.

Separate the application into:

- Presentation
- Domain
- Data
- Audio Processing
- ML Inference

Business logic must not depend on Android UI components or specific ML implementations.

## 3. Core Functional Requirements

### 3.1. Audio Recording

Implement local audio recording using AudioRecord.

Requirements:

- PCM 16-bit audio.
- 16 kHz sample rate for model input.
- Mono audio for the primary processing pipeline.
- Recording duration indicator.
- Start, pause, resume, and stop functionality.
- Microphone permission handling.
- Local audio file storage.
- Audio import from device storage.
- Streaming audio processing without loading entire recordings into RAM.

Preserve the original recording when practical. If resampling or channel conversion is required, perform it through an efficient audio preprocessing pipeline.

### 3.2. Speech Recognition (ASR)

The application must recognize Russian speech only.

Evaluate the following models:

- GigaAM v3 — primary candidate for Russian ASR.
- Whisper — alternative candidate for accuracy comparisons.

Evaluate:

- ONNX availability.
- Runtime compatibility.
- Model size.
- RAM requirements.
- CPU inference performance.
- INT8 quantization support.
- Streaming capabilities.
- Model licensing and redistribution restrictions.

Do not assume that every ONNX model is automatically compatible with sherpa-onnx.

Choose the most appropriate runtime for each model.

If the selected model does not support true streaming inference, implement chunk-based processing with overlapping audio windows and text stabilization.

Do not claim that chunk-based processing is equivalent to native streaming inference.

### 3.3. Voice Activity Detection

Use Silero VAD or another lightweight VAD implementation.

Responsibilities:

- Detect speech start and end.
- Identify silence.
- Avoid unnecessary ASR inference during silence.
- Split audio into speech regions.
- Preserve accurate timestamps relative to the original recording.

VAD must be efficient enough for continuous real-time operation.

### 3.4. Speaker Diarization

Implement automatic speaker diarization with an unknown number of participants.

The user must not be required to specify the number of speakers.

Evaluate:

- Pyannote Segmentation 3.0.
- 3D-Speaker embeddings.
- sherpa-onnx offline diarization.
- Automatic speaker clustering.

Requirements:

- Automatically determine the number of speakers.
- Assign stable speaker identifiers within a recording.
- Group speech segments belonging to the same person.
- Distinguish different speakers.
- Support UNKNOWN speaker assignments when confidence is insufficient.
- Handle short utterances conservatively.
- Support overlapping speech when the selected models allow it.

For offline processing, use automatic clustering rather than a predefined number of clusters.

For real-time processing, allow provisional speaker assignments and revise them as additional audio becomes available.

Do not assume that offline diarization can be directly used as a true streaming diarization solution.

### 3.5. Word-Level Timestamps

The application must support word-level timestamps.

Investigate Forced Alignment models that support Russian.

Requirements:

- Accurate word start and end timestamps.
- Millisecond precision in the output format.
- Timestamps relative to the original audio file.
- No artificial uniform distribution of timestamps across words.
- Explicitly distinguish estimated timestamps from verified alignment results.

If exact word alignment cannot be implemented with the selected models, document the limitation and provide a suitable alternative.

## 4. Unified Transcript Output Contract

This is a critical architectural requirement.

All recognition pipelines must produce the same normalized output structure, regardless of the ASR model, diarization implementation, or processing mode.

The required JSON format is:

{
"transcript": [
{
"speaker_id": "speaker_1",
"start_ms": 1250,
"end_ms": 4380,
"text": "Добрый день!"
}
]
}

### Field definitions

- `transcript`: ordered collection of recognized speech segments.
- `speaker_id`: stable speaker identifier within the recording, formatted as `speaker_1`, `speaker_2`, etc.
- `start_ms`: segment start timestamp in milliseconds relative to the beginning of the original recording.
- `end_ms`: segment end timestamp in milliseconds relative to the beginning of the original recording.
- `text`: recognized speech text.

### Mandatory output rules

1. All timestamps must be integers representing milliseconds.
2. `start_ms` must be less than or equal to `end_ms`.
3. Segments must be ordered by `start_ms`.
4. Speaker IDs must remain stable throughout a recording.
5. An unknown speaker must use `speaker_id: "unknown"`.
6. Different speakers must have different IDs.
7. The output must not contain model-specific internal objects.
8. The same contract must be used by real-time and offline processing.
9. Timestamps must always reference the original recording, not an individual audio chunk.
10. Do not invent speaker IDs or timestamps when the models cannot determine them reliably.

### Segment granularity

The normalized output should use speech segments, typically corresponding to complete utterances or continuous speech from one speaker.

Word-level timestamps are an internal processing requirement and must be preserved in a separate alignment representation or internal data structure.

The primary JSON output must remain compatible with the specified contract.

Do not add extra mandatory fields to the public JSON structure.

### Overlapping speech

Do not artificially force overlapping speech segments into non-overlapping intervals.

If two speakers talk simultaneously, their segments may overlap in time.

Sorting by `start_ms` must still be preserved.

### Real-time output

Use the same JSON contract for partial results.

Maintain internal metadata to distinguish provisional and finalized segments, but do not change the required public JSON schema.

When processing continues, update provisional segments instead of creating duplicate transcript entries.

### Example

{
"transcript": [
{
"speaker_id": "speaker_1",
"start_ms": 1250,
"end_ms": 4380,
"text": "Добрый день!"
},
{
"speaker_id": "speaker_2",
"start_ms": 4520,
"end_ms": 6800,
"text": "Здравствуйте, рад вас видеть."
},
{
"speaker_id": "speaker_1",
"start_ms": 7100,
"end_ms": 9200,
"text": "Как ваши дела?"
}
]
}

## 5. Real-Time Processing

Implement a dedicated streaming processing pipeline.

Requirements:

1. Continuous audio capture.
2. Small audio processing buffers.
3. VAD-based speech detection.
4. Incremental ASR inference.
5. Partial transcript updates.
6. Speaker diarization.
7. Provisional speaker assignments.
8. Timestamp generation.
9. Final segment stabilization.

Use bounded queues and backpressure.

Do not allow unprocessed audio buffers to accumulate indefinitely.

Do not block the main thread.

Do not execute multiple heavy inference tasks concurrently unless benchmarks demonstrate that this improves performance.

When the recording ends, allow offline reprocessing to improve transcript accuracy, speaker assignments, and timestamps.

## 6. Offline Processing

Implement a separate offline pipeline.

Processing sequence:

1. Read the recording in chunks.
2. Run VAD.
3. Perform ASR.
4. Perform speaker diarization.
5. Run forced alignment.
6. Merge recognition and diarization results.
7. Normalize timestamps.
8. Generate the final transcript JSON.
9. Save results locally.

Offline processing should prioritize accuracy over latency.

Reuse intermediate results where possible to avoid unnecessary inference.

## 7. Performance Requirements

Performance is a first-class architectural requirement.

Target devices:

- Android API 26 and above.
- ARM64 devices as the primary target.
- Mid-range smartphones and above.

Optimization requirements:

- CPU inference as the baseline.
- INT8 quantization where appropriate.
- Minimize audio buffer copies.
- Reuse memory buffers.
- Lazy-load ML models.
- Release unused model resources.
- Avoid inference during silence.
- Limit the number of inference threads.
- Avoid unnecessary object allocations.
- Profile CPU and memory usage.
- Consider NNAPI or hardware acceleration only when verified to improve performance.

Do not assume that GPU or NPU inference is always faster than CPU inference.

Do not load every heavy model into memory simultaneously unless necessary.

Implement configurable performance profiles:

- Performance: prioritize latency.
- Balanced: balance speed and accuracy.
- Accuracy: prioritize offline recognition quality.

Do not invent performance benchmarks. Measure actual inference time, RAM usage, and latency.

## 8. Application UI

Build a clean and modern interface using Jetpack Compose and Material 3.

### Home Screen

- Start recording button.
- Recording history.
- Recording duration.
- Creation date.
- Number of detected speakers.
- Processing status.

### Recording Screen

- Recording controls.
- Audio waveform.
- Recording timer.
- Microphone status.
- Real-time transcript preview.
- Current speaker information.
- Processing state.

### Transcript Screen

- Complete transcript.
- Speaker labels.
- Segment timestamps.
- Playback synchronization.
- Jump to the corresponding audio position.
- Visually distinguish speakers.
- Clearly distinguish provisional and finalized results.

### Speakers Screen

- List of detected speakers.
- Speaker identifiers.
- Custom speaker names.
- Merge speaker identities.
- Split incorrectly merged speakers.
- Reassign transcript segments.
- Persist manual corrections.

### History Screen

- Search recordings.
- Rename recordings.
- Delete recordings.
- Reprocess recordings.
- View previous transcripts.

## 9. Local Data Storage

Use Room for metadata and transcript persistence.

Suggested entities:

- RecordingEntity.
- TranscriptEntity.
- SegmentEntity.
- WordEntity.
- SpeakerEntity.

Relationships:

- One recording has one transcript.
- One transcript contains multiple segments.
- One segment contains multiple words.
- One segment belongs to a speaker.
- One speaker can be associated with multiple segments.

Store audio files separately from the database.

Persist the normalized transcript JSON or provide a reliable serialization layer for generating it.

Use database transactions when updating transcripts and speaker assignments.

Implement Room migrations.

## 10. Testing and Benchmarking

Create unit tests and integration tests for the main components.

Measure:

- WER — Word Error Rate.
- DER — Diarization Error Rate.
- RTF — Real-Time Factor.
- Latency.
- Peak RAM usage.
- CPU utilization.
- Timestamp alignment error.

Test:

- Single-speaker recordings.
- Multiple-speaker conversations.
- Unknown speaker counts.
- Short utterances.
- Background noise.
- Overlapping speech.
- Long recordings.
- Silence.
- Interrupted processing.

Validate the JSON contract:

- Valid JSON.
- Correct field names and types.
- Integer timestamps.
- Valid timestamp ranges.
- Stable speaker IDs.
- Correct chronological ordering.
- Overlapping intervals preserved when necessary.

Provide benchmark tooling for repeatable evaluation.

## 11. Development Phases

Do not implement the entire system at once.

### Phase 1 — Technical Research

Investigate and compare:

- GigaAM v3.
- Whisper.
- sherpa-onnx.
- ONNX Runtime.
- Silero VAD.
- Pyannote.
- 3D-Speaker.
- Russian forced alignment models.

For each candidate, document:

- Model size.
- Runtime compatibility.
- RAM requirements.
- CPU performance.
- Streaming support.
- Quantization options.
- License.
- Integration complexity.

Do not integrate models without verifying their compatibility.

### Phase 2 — Android Infrastructure

- Create the project.
- Configure architecture.
- Implement audio recording.
- Implement audio import.
- Implement local storage.
- Create the basic UI.

### Phase 3 — ASR

- Integrate the first working Russian ASR model.
- Implement offline transcription.
- Implement the normalized transcript contract.
- Add benchmark tests.

### Phase 4 — VAD and Diarization

- Integrate VAD.
- Integrate speaker embeddings.
- Implement automatic clustering.
- Implement unknown speaker handling.
- Implement manual speaker correction.

### Phase 5 — Word Alignment

- Integrate Russian-compatible forced alignment.
- Generate word-level timestamps.
- Validate alignment accuracy.

### Phase 6 — Real-Time Processing

- Implement streaming audio capture.
- Implement incremental recognition.
- Add provisional transcript updates.
- Add provisional speaker assignments.
- Implement result stabilization.

### Phase 7 — Optimization

- Profile CPU and RAM.
- Optimize inference.
- Test on API 26 and newer Android devices.
- Document hardware requirements.
- Verify offline operation.

## 12. Engineering Rules

1. Inspect the existing repository before modifying files.
2. Preserve existing working code.
3. Do not introduce unnecessary dependencies.
4. Verify dependency versions and compatibility.
5. Never use cloud-based speech recognition.
6. Never upload audio recordings.
7. Never use fake ML implementations.
8. Never generate fabricated timestamps.
9. Never simulate speaker diarization.
10. Keep the transcript JSON contract stable.
11. Keep model implementations replaceable.
12. Document architectural decisions.
13. Run builds and tests after each development phase.
14. Do not silently replace an unavailable model with a different model.
15. Report integration limitations honestly.

Prioritize a reliable, efficient, working application over superficial feature completeness.

## 13. First Task

Start with Phase 1.

Do not immediately implement the entire application.

First inspect the repository, research model compatibility, and prepare a technical report containing:

- Recommended ASR model.
- Recommended inference runtime.
- Recommended VAD model.
- Recommended diarization pipeline.
- Recommended forced alignment solution for Russian.
- Estimated model storage requirements.
- Estimated RAM requirements.
- Expected integration challenges.
- Proposed architecture.
- Development roadmap.

Clearly distinguish verified facts from assumptions.

After presenting the report, proceed with implementation in small, independently testable phases.