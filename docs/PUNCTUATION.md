# Punctuation restoration — Horizon-Labs Punctuation Restoration Small (INT8)

Fully offline punctuation restoration for ASR output, integrated into the
transcription pipeline after word-level speaker assignment and before
speaker-aware segmentation.

## Model facts (verified from the repository, not guessed)

- Model: `Horizon-Labs/punctuation-restoration-small` (ModernBert token
  classification on mmBERT-small, 140M params, Apache-2.0), revision `12b02a8`.
- Artifact: `onnx/model_quantized.onnx` — 268,414,381 bytes, SHA-256
  `5679870be92c7fa47c9be9b2872fac389ef28d43d2c33f35e1315b93a82f4ace`
  (matches the repo's published SHA256SUMS).
- Labels (logit index → mark): `0`→none, `1`→`.`, `2`→`,`, `3`→`?`,
  `4`→` -`, `5`→`:`. **The model has no `!` or `;` classes** — those marks
  are never predicted (structural model limit).
- Specials: `<pad>`=0, `<eos>`=1, `<bos>`=2, `<unk>`=3. Windows wrap tokens
  as `[<bos>] + tokens + [<eos>]` exactly like the reference `punctuate.py`.
- Inputs (ONNX): `input_ids` int64 [1, N] (+ `attention_mask` when exposed);
  output: `logits` float [1, N, 6]. No padding (single sequence, mask=1).
- Tokenizer: HF fast tokenizer (Replace " "→"▁"; Metaspace always-prepend;
  BPE with byte fallback; 256k vocab, 580,604 merges). Faithful Kotlin port
  in `PunctuationTokenizer`; tables extracted byte-faithfully from the
  repository's `tokenizer.json` (SHA-256 `f34b44a6…`).
- A word's label = the label of its LAST token (per the model card).
- Windowing: 400 tokens / 50 overlap (reference values); every token labeled
  exactly once, overlap gives boundary context.

## Assets and bundling

- `app/src/main/assets/models/punctuation/`
  - `model_quantized.onnx` — build-time download with size+SHA-256
    verification (`downloadBundledModels` task), gitignored like all models.
  - `vocab.txt` (3,870,608 B, SHA-256 `597fc89c…`) and `merges.txt`
    (5,815,563 B, SHA-256 `2562b5c2…`) — committed source assets.
- On first use `BundledPunctuationRestorer` copies all three into
  `files/models/punctuation/` (verified, `.part` + rename, no re-copy when a
  valid file exists), then starts the ONNX session. No internet at runtime,
  no external storage, no permissions.

## Pipeline position

```
ASR words (global timestamps)
  → word-level speaker assignment
  → punctuation restoration (independent per consecutive same-speaker group)
  → capitalization post-processing (sentence starts; model does not case)
  → speaker-aware segmentation → transcript
```

Punctuation never changes word timestamps/confidence, never merges words
across speakers, and on model failure the pipeline continues with the
original words (marks are never fabricated).

## Measured (desktop ORT 1.28.0, production tokenizer + restorer)

- Model init: ~0.9 s; inference ~10–20 ms per short sentence; 900-word
  stress input (3,492 tokens, 10 windows): ~2.1 s. Android numbers will be
  higher — measure on device via `PunctuationInferenceTest`.
- APK impact: 360 MB → 621 MB (+261 MB net, assets compress slightly).
- Runtime RAM: not measured on device; the 268 MB figure is the FILE size,
  not memory usage.

## Known limitations

- No `!`/`;` labels in the model.
- Capitalization is rule-based (sentence starts only), not model-predicted.
- `е` is never rewritten to `ё`.
- On-device inference is covered by `PunctuationInferenceTest`
  (instrumented); it has not been executed in this environment (no device).
