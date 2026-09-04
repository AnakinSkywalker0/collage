# iykyk Android Internship Assignment — Unique-Person Collage

On-device pipeline: load a portrait video → detect faces → embed them → track
appearances within the video → cluster appearances into unique people → pick
the best shot per person → render a shareable collage.

## Stack

- Kotlin, Jetpack Compose, minSdk 26
- ML Kit Face Detection (`com.google.mlkit:face-detection`) — detection, landmarks, head-pose (Euler Y/Z), eyes-open + smiling classification
- LiteRT (`com.google.ai.edge.litert`, the current name for TensorFlow Lite — TFLite is in maintenance mode as of 2026, LiteRT is a drop-in package swap with the same `Interpreter` API) running a bundled MobileFaceNet-class embedding model
- Kotlin coroutines for all off-main-thread processing
- `MediaMetadataRetriever` for frame sampling, `MediaStore` for gallery save, `FileProvider` + `ACTION_SEND` for sharing
- No backend, no network calls — everything runs on-device

## Build / setup

1. **Get the embedding model** (required — the app will throw a clear error at
   the start of processing if it's missing). Download a 112×112-input,
   MobileFaceNet-class `.tflite` file and place it at:
   ```
   app/src/main/assets/face_embedder.tflite
   ```
   Known-good public sources (pick one):
   - https://github.com/MCarlomagno/FaceRecognitionAuth/blob/master/assets/mobilefacenet.tflite
   - https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android (assets folder)
   - https://github.com/shubham0204/OnDevice-Face-Recognition-Android

   Any model matching that input/output shape works as long as you update
   `FaceEmbedder.INPUT_SIZE` / `EMBEDDING_DIM` to match if it differs (default
   assumes 112×112 input, 192-d output — adjust to the specific file's actual
   output dimension, check with Netron if unsure).

2. Open the project in Android Studio (or just `./gradlew` from terminal), let Gradle sync.
3. Run on an emulator (API 26+) or physical device — no special permissions needed to pick or process a video (SAF handles that); `WRITE_EXTERNAL_STORAGE` is requested only on API 26-28 right before saving to gallery.
4. Debug build: `./gradlew assembleDebug` → APK at `app/build/outputs/apk/debug/`.

## Embedding model used

MobileFaceNet-class TFLite model, 112×112 RGB input, single float embedding
output (192-d as bundled; verified against the actual file's tensor shapes).
Preprocessing: pixels scaled to roughly `[-1, 1]` via `(value - 127.5) / 128`.
Output is L2-normalized in-app so cosine similarity reduces to a dot product
everywhere downstream.

**Face alignment matters more than any threshold.** These models are trained
on tightly cropped, eye-aligned faces where the eyes sit at fixed canonical
positions. `FaceAligner` applies a 2-point similarity transform on the ML Kit
eye landmarks (rotate the eye line level, scale to canonical inter-eye
distance, translate the eyes onto their canonical spots) to produce the
112×112 input. If both eyes aren't available it falls back to a tight
(15% margin) box crop.

Note the deliberate split: the **generous** crop (50% margin) is what goes
into the collage tile, per the assignment's "don't crop tightly" rule, while
the **aligned** crop is the only thing the embedder ever sees. Feeding the
generous crop to the embedder — which is what an earlier version did — puts
the face at under half the frame width surrounded by background, way off the
model's training distribution, and the embeddings end up encoding framing
instead of identity. Symptom: wildly wrong person counts on every video.

*(If you swap in a different embedding model, update this section with its
name, source, and license before submitting — the assignment explicitly asks
for this to be documented.)*

## Similarity threshold chosen

- **Appearance tracking (within one video)**: a detection is matched to an
  active track if embedding cosine similarity > 0.5, **or** if IOU > 0.2 *and*
  the embedding similarity is at least 0 (i.e. not obviously a different
  person — see "Bugs found and fixed" below for why that floor exists).
  Greedy best-score assignment per frame. A track closes — finalizing one
  `Appearance` — after 1000ms with no match, which is what lets a blurred
  whip-pan or a person leaving frame end a segment instead of bridging it
  indefinitely, while tolerating a few consecutive missed detections during
  fast head movement. Stale tracks are closed *before* matching each frame,
  not after (also covered below).
- **Identity clustering (across appearances)**: appearances are merged into
  the same person via greedy threshold clustering at cosine similarity >
  **0.5** against the running cluster centroid (`IdentityClusterer`, backed
  by the framework-free `GreedyClusterer`).

### How these were actually calibrated

The Sample-1 simulation described below validated the *matching/clustering
logic* against synthetic data, but not the real bundled model's actual
similarity distribution — that can only come from running the real model.
So the exact same alignment + preprocessing pipeline used on-device
(`FaceAligner`'s 2-point transform → 112×112 → `(px-127.5)/128`) was run
against `face_embedder.tflite` in Python, on real photos, to measure real
genuine-vs-impostor cosine similarity:

| Comparison | cosine similarity |
|---|---|
| Different people (real photos) | **-0.06** |
| Same person, small alignment jitter (2-6° rotation / a few px, matching normal frame-to-frame landmark noise) | **0.51 – 0.85** |

Two takeaways that directly shaped the values above:

1. **Different-person similarity is strongly negative** — 0.5 has a wide,
   safe margin as a clustering threshold. This ruled out the embedding
   model itself as the source of the on-device over-merging seen earlier;
   that was a tracker logic bug (see below), not weak embeddings.
2. **Same-person similarity is far less stable than expected** — ordinary
   landmark noise between adjacent 200ms video frames swings it from 0.85
   down to 0.51, right at the matching threshold. `iouThreshold` (0.3→0.2)
   and `maxGapMs` (600→1000) were loosened specifically so the IOU fallback
   reliably carries a real appearance through the frames where the
   embedding dips just under 0.5 instead of splitting it into a new track
   — this was the direct cause of an on-device over-count (one person
   reported as 9 appearances instead of 4).

**Still tune against the Sample 1 worked example** (5 distinct people, each
appearing 4 times, 20 appearances total, including two 2-person overlapping
segments) before trusting either value on Samples 2/3 — two data points
(one person pair) is a sanity check, not a full calibration. The values
live in `AppearanceTracker` and `IdentityClusterer` constructors.

### Clustering on best-observation embedding, not the track mean

A real 5-person clip (Sample 2) exposed a second real bug: on-device it
merged 5 people into 3 clusters. Re-running the real model on one clean,
correctly-aligned frame per person (same alignment/preprocessing as
on-device) showed all 5 separating fine — max pairwise similarity 0.47,
comfortably under 0.5, including the two most visually similar people (both
men, both wearing glasses, 0.38). So the model and threshold weren't at
fault; the gap was that `IdentityClusterer` clustered on `Appearance.
meanEmbedding` — the mean over *every* observation in a track, including
the blurry, off-angle, and mid-turn frames a real appearance inevitably
contains at 5fps. Averaging those in drags the appearance's representative
point away from the person's true identity centre, enough to tip a
borderline pair over threshold. Fixed by clustering on each appearance's
*best-quality* observation instead (`IdentityClusterer.representativeEmbedding`,
reusing `QualityScorer`'s existing frontality/sharpness ranking) — the same
properties that make a good collage shot also make a trustworthy embedding.

### Bugs found and fixed while calibrating

This algorithm can't be exercised end-to-end without the Android SDK / an
emulator, so before wiring it up for real, the exact tracking + clustering
logic was ported to a standalone Python script and run against synthetic
data shaped like the Sample 1 worked example (5 people, 4 appearances each,
two 2-person overlaps, with per-frame embedding noise and occasional missed
detections). That caught two real bugs that would otherwise have only shown
up as wrong counts on-device, with no obvious cause:

1. **Stale tracks were being matched one frame too late.** Gap-closing ran
   *after* that frame's matching instead of before, so a track that should
   already have been dead could still greedily steal a detection just
   because no fresher candidate outscored it — silently merging two
   different people's appearances into one track across a gap. Fixed by
   closing expired tracks at the top of each frame's loop, before matching.
2. **High IOU alone could win a match against a clearly-different
   embedding.** Two people standing close together (spatially overlapping
   boxes) could have their identities swapped between tracks, since IOU says
   nothing about *who* someone is. Fixed by requiring embedding similarity
   to at least clear a floor (0, i.e. "not obviously a different person")
   whenever a match is justified by IOU rather than by embedding similarity
   alone.

With both fixes, the simulation recovered the correct 5 clusters of 4
appearances across a wide 0.35-0.55 clustering-threshold band and across
noise levels harsher than the baseline. `IdentityClusterer`'s default
moved from an untested guess of 0.67 (which turned out too high to ever
merge anything) to 0.5. This is documented so you know *why* those numbers
are what they are, not just what they are — and so you re-run the same kind
of sanity check if you change the matching logic again.

`app/src/test/java/.../pipeline/math/` has unit tests for the pure
algorithmic core (`VectorMath`, `GeometryMath`, `GreedyClusterer`),
including a regression test (`GreedyClustererTest.cluster_sampleOneShapedScenario...`)
that encodes this exact calibration scenario. Run `./gradlew test` before
you do anything else — it's fast, needs no emulator, and will tell you
immediately if a future change breaks the clustering math.

## Architecture

```
ui/            Compose screens (Home, Processing, Result) + theme
viewmodel/     CollageViewModel — owns pipeline state as a StateFlow
pipeline/      FrameExtractor, FaceDetectorStage, FaceEmbedder, ImageQuality,
               FaceCropUtils, AppearanceTracker, IdentityClusterer,
               QualityScorer, PipelineOrchestrator (wires it all together)
pipeline/math/ VectorMath, GeometryMath, GreedyClusterer — the framework-free
               algorithmic core (no android.* imports), unit-tested directly
               under plain JVM JUnit; AppearanceTracker/IdentityClusterer are
               thin Android-shaped adapters over these
collage/       CollageComposer (Canvas-based grid renderer), CollageSaver
               (MediaStore), ShareUtil (FileProvider + ACTION_SEND)
model/         FrameSample, FaceObservation, Appearance, Person, ProcessingState
```

Faces are always cropped generously (50% margin around the ML Kit bounding
box, clamped to frame bounds) for both the embedding input and the stored
tile source — never a tight bbox crop, per the assignment brief.

## Known limitations / things to verify before submitting

- Thresholds above need empirical tuning against all three sample videos with the real embedding model, not just the synthetic simulation described above.
- Two people with heavily overlapping bounding boxes and unusually similar embeddings can still, rarely, get their identities swapped between tracks — a known, documented residual risk (see "Bugs found and fixed"), not something worth chasing to zero since tightening it further caused far more common track fragmentation in testing.
- Emulator inference is slower than a real device; if timing matters for your recording, do a final pass on physical hardware.
- The collage layout (2 cols ≤4 people, 3 cols beyond) hasn't been visually tuned for very large person counts — check it looks right on your actual results.
- `./gradlew test` only covers the framework-free math core; the Android-coupled stages (frame extraction, ML Kit detection, TFLite inference, MediaStore/FileProvider) are only exercised by actually running the app.
