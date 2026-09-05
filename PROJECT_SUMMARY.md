# iykyk — Android Internship Assignment: Project Summary

**Deadline:** Sunday, 6 September 2026, 11:59 PM IST
**Status as of:** Saturday, 5 September 2026
**Repo:** `AnakinSkywalker0/collage` (verified from `git remote -v`)
**Local project:** `D:\collage`

## What the app does

A Kotlin/Compose app (minSdk 26) that takes a 30-second portrait video and produces a shareable collage of every unique person in it with their appearance count, entirely on-device.

Graded 50% identity/appearance-count accuracy, 30% code quality/architecture, 20% UX/presentation. No backend, no network calls.

## Requirements checklist (from the brief)

| Requirement | Status |
|---|---|
| Detection + embeddings + clustering, all three | ✅ ML Kit → MobileFaceNet → agglomerative clustering |
| On-device, no backend | ✅ |
| Off main thread, clear progress | ✅ `Dispatchers.Default`, per-stage progress |
| Appearance count per person | ✅ — 20/20 on Sample 1; see "Measured on device" |
| Rep shot: frontality / sharpness / eyes open / smiling | ✅ `QualityScorer` |
| "Do not crop tightly… crop generously" | ✅ `cropGenerous`, separate from the embedder crop |
| Save to gallery + share sheet | ✅ |
| Don't hardcode the three clips' results | ✅ nothing sample-specific in the code |
| README: build steps, embedding model, similarity threshold | ❌ not written — threshold is now known (0.35) |
| Debug APK | ✅ builds (`app/build/outputs/apk/debug/app-debug.apk`) |
| ≤60s screen recording, all three collages legible | ❌ not started |

## Architecture

```
ui/            Compose screens (Home, Processing, Result) + theme
viewmodel/     CollageViewModel — pipeline state as StateFlow
pipeline/      FrameExtractor, FaceDetectorStage, FaceEmbedder, ImageQuality,
               FaceCropUtils, FaceAligner, TrackletBuilder, IdentityClusterer,
               AppearanceSplitter, QualityScorer, PipelineOrchestrator
pipeline/math/ VectorMath, GeometryMath, AgglomerativeClusterer,
               TimelineSegmenter  (all plain-JVM unit tested)
collage/       CollageComposer, CollageSaver, ShareUtil
model/         FrameSample, FaceObservation, Tracklet, Appearance, Person,
               ProcessingState
```

Stack: Kotlin 2.2.10, Compose BOM 2026.02.01, AGP 9.4.0, ML Kit Face Detection 16.1.7, LiteRT 2.1.0, coroutines.

### Pipeline order — identity is resolved *before* appearances are counted

```
extract frames (5fps)
  → detect faces (ML Kit, + landmarks/classification)
  → align to 112×112 on the eye line          [FaceAligner]
  → embed (MobileFaceNet, 192-d, L2-normalized)
  → build tracklets (frame-to-frame continuity) [TrackletBuilder]
  → cluster tracklets into people               [IdentityClusterer]
  → split each person's timeline into appearances [AppearanceSplitter]
  → pick representative shot per person         [QualityScorer]
  → compose collage                             [CollageComposer]
```

The ordering of the middle three stages is the main architectural decision, and it is a **change from the earlier design**, which tracked first and counted the tracker's output. Counting tracker output makes the count only as good as frame-to-frame tracking, which is a *spatial* signal and breaks down exactly where these clips are hardest — at cuts. Clustering first and then splitting each person's own timeline on temporal gaps matches the brief's definition of an appearance directly ("one continuous visible segment"), and keeps a tracking slip from becoming a wrong count.

A consequence worth knowing before touching `TrackletBuilder`: **tracklets are allowed to over-fragment.** A visible segment split into two tracklets costs nothing — both cluster to the same person, their observations pool, and the timeline split rejoins them. A tracklet that spans two *people* is unrecoverable. The thresholds there are deliberately asymmetric for that reason.

## The bug that was causing the nonsense output — fixed

`FaceAligner` had been changed from `post*` to `pre*` matrix calls keeping the same call order. Android's `Matrix` composes them in opposite directions (`post` is `M = X·M`, `pre` is `M = M·X`), so the transform ran backwards.

Worked numerically for a face with eyes at (500,400)/(560,430) in a 720×1280 frame:

| | left eye lands at |
|---|---|
| `pre*` (was in the build) | **(−140.96, −314.23)** — off a 112×112 canvas entirely |
| `post*` (correct) | (38.29, 51.69) — exactly the canonical position |

The 112×112 crop was sampling source pixels around **y ≈ 1055–1338 while the face was at y ≈ 400** — the bottom edge of the frame. **MobileFaceNet was embedding background, never a face**, on every detection of every run.

That single bug explains all three reported symptoms: different people scored as similar (their backgrounds match), the same person scored as different (background changes), and repeat runs disagreeing (near-textureless input → embeddings dominated by numerical noise, similarities landing on 0.499–0.508).

**Consequence for old findings:** every embedding-related measurement taken before this fix is void, including all of `test_normalization.py`. The `/128` vs `/127.5` vs `/255` question was never actually measured. It also does not need to be — the bundled model is confirmed genuine MobileFaceNet (`MobileFaceNet/Conv_*`, `Logits/LinearConv1x1` scopes present in the `.tflite`), and `(x − 127.5) / 128` with RGB channel order is already the correct preprocessing for it.

The proof is preserved as a comment in `FaceAligner.kt` so the revert doesn't get "simplified" back.

## Second structural bug — fixed

`AppearanceTracker` matched with:

```kotlin
sim > 0.5f || (iou > 0.2f && sim > 0f)   // sanity floor was 0f
```

The supplied clips are cut-based portrait video: consecutive shots frame different people's heads in the same part of the frame, with no gap between segments. At a hard cut the outgoing and incoming boxes overlap heavily — **IoU is high precisely when identity has changed** — so the IoU branch with a `0f` identity floor bridged straight across cuts and chained ~20 real segments into a handful of tracks. That is the "20 appearances collapse to ~5, then cluster to 1–2 people" symptom.

`TrackletBuilder` now requires IoU **and** step-wise embedding similarity **and** a similarity floor against the tracklet's first observation (which bounds drift over its life).

## Other changes made in the same pass

- **`GreedyClusterer` → `AgglomerativeClusterer`.** Single-pass greedy assigned each item to the best cluster *existing at the time it was visited*, so results depended on input order, and its running-mean centroid drifted — each marginal merge moved the target and made the next one easier. Average-linkage agglomerative always merges the globally-best pair and re-reads actual members, so output is a function of the embeddings alone. Deterministic by construction. O(n³) on tens of tracklets is free.
- **Tracklet identity embedding = mean of its top-5 quality observations.** The full mean dilutes identity with blurry/mid-turn frames; a single best frame puts the whole decision on one frame's noise. Top-k keeps selectivity and averages the noise out.
- **`QualityScorer.rank()`** added; shot-picking and clustering now read one ranking instead of two that can drift apart.
- **`TimelineSegmenter`** extracted into `pipeline/math/` so the counting rule — the single most heavily graded piece of logic — is testable under plain JVM JUnit without Robolectric.
- **Unused candidate crops released** before compositing, instead of holding one generous crop per detection for the whole run.
- **People ordered by first appearance**, so display order is stable across runs and checkable against the video.

## Verified vs. not verified

**Verified:**
- 24 unit tests pass (`./gradlew :app:testDebugUnitTest`), including 7 new `TimelineSegmenterTest` cases covering continuous runs, a mid-segment detector dropout, cut-away-and-back, the brief's 4-appearances-per-person shape, and the gap boundary condition.
- `AgglomerativeClustererTest` recovers 5 people × 4 appearances on Sample-1-shaped synthetic data.
- `./gradlew :app:assembleDebug` succeeds.
- The matrix claim above was checked numerically, not by reading the code.

**Measured on device, Sample 1 (5 Sept, calibration run):**
- 151 frames → 164 detections → 22 tracklets → 6 people, **20 appearances**.
- Segmentation is correct. The 18 distinct visible segments are all 1.2–1.4s, and there are exactly two double-occupancy moments — at 10.2–11.4s and 20.2–21.4s, which is precisely where the brief says A&B and C&D share the frame. 18 + 2 = 20, and the brief's ground truth is 20.
- Identity grouping reaches **6 clusters instead of 5**. Two are exactly right (4 appearances each); one holds 6 appearances and is two people merged.

**Known limitation — the 6-vs-5 gap is the embedding model's ceiling, not a tunable:**
The over-merged cluster is two tight sub-groups ({0,9,17} at 0.87–0.89 internally, {4,11,20} at 0.80–0.95) bound together at 0.425 average linkage. Splitting them and giving each a correct 4th appearance would require some candidate pair to score above that; the best available across every candidate is **+0.099**, and most are negative. No threshold prefers the correct grouping, because the embedding evidence points the other way. A sweep confirms it: 0.30/0.35/0.38 all produce the same output, 0.40 starts shedding singletons, 0.45 fragments into 9 clusters.

**Still not verified:**
- Samples 2 and 3 have never been run.
- End-to-end wall-clock time per clip has not been measured (matters for the 60s recording budget).

## Immediate next steps (in order)

1. Run Samples 2 and 3. Their ground truth isn't given — sanity-check by eye against the collage.
2. Time a full run. `SAMPLE_INTERVAL_MS = 200` with `OPTION_CLOSEST` decodes ~150 non-keyframes per clip and may be slow; the screen recording budget is 60s total for all three videos. 300–400ms sampling is still ample for ~1.4s segments if needed.
3. README: build/setup steps, embedding model + source/license, and **the chosen similarity threshold with the reasoning** (all three are explicitly required by the brief). Write the 6-vs-5 limitation honestly — the evidence is in "Known limitation" above.
4. Commit and push to `AnakinSkywalker0/collage`.
5. Build the final debug APK.
6. Record the ≤60s screen capture: processing, appearance counts, and the finished collage for **all three** samples, each held on screen long enough to read.

**Already done:** calibration run against Sample 1; threshold set to 0.35 (centre of the measured stable plateau); debug logging stripped. The pipeline now emits only three warnings for real failure conditions plus one `Log.i` summary line (`N frames -> N detections -> N tracklets -> N people, N appearances`).

## Key files

- `pipeline/FaceAligner.kt` — 112×112 eye-aligned crop for the embedder. **Do not change `post*` to `pre*`**; the comment explains why.
- `pipeline/TrackletBuilder.kt` — frame-to-frame continuity; requires IoU AND embedding agreement.
- `pipeline/IdentityClusterer.kt` — clusters tracklets into people; holds the similarity threshold.
- `pipeline/AppearanceSplitter.kt` — the appearance count comes from here, nowhere else.
- `pipeline/math/TimelineSegmenter.kt` — the counting arithmetic, unit tested.
- `pipeline/math/AgglomerativeClusterer.kt` — deterministic average-linkage clustering.
- `pipeline/PipelineOrchestrator.kt` — stage wiring; centers embeddings once, before anything compares two.
- `pipeline/FaceEmbedder.kt` — MobileFaceNet, 112×112 RGB, `(x−127.5)/128`, 192-d, L2-normalized.
- `README.md` — needs model, threshold, and build steps.
- `test_normalization.py` — **obsolete**; its inputs were produced by the broken aligner. Delete or regenerate before relying on it.
