# Collage

An Android app that takes a short video, finds every distinct person in it, counts how many
times each one appears, and composes a single shareable collage with one generous portrait
per person and their appearance count.

Everything runs **on-device**. There is no backend, no network call, and no API key.

---

## Build and run

**Requirements**

| | |
|---|---|
| JDK | 17+ (Gradle toolchain targets Java 11 bytecode) |
| Android SDK | compileSdk 37 |
| Device / emulator | API 26 (Android 8.0) or newer |

**Steps**

```bash
git clone https://github.com/AnakinSkywalker0/collage.git
cd collage
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Point `local.properties` at your SDK if Gradle cannot find it:

```properties
sdk.dir=/path/to/Android/Sdk
```

No extra setup is needed. **The embedding model is committed to the repo** at
`app/src/main/assets/face_embedder.tflite` (5.0 MB) — nothing is downloaded at build or run
time. `androidResources { noCompress += "tflite" }` in `app/build.gradle.kts` keeps the model
uncompressed in the APK so LiteRT can memory-map it directly.

Run the unit tests with:

```bash
./gradlew test
```

26 tests cover the framework-free maths core — vector operations, IoU geometry, the clustering
algorithm, and the appearance-counting arithmetic. They are plain JVM JUnit and need no device
or Robolectric.

**Using the app:** pick a video, watch per-stage progress, and the collage appears with each
person's count. Save to gallery or share from there.

---

## Embedding model

**MobileFaceNet**, bundled as `app/src/main/assets/face_embedder.tflite`, executed through
**LiteRT** (`com.google.ai.edge.litert`).

| Property | Value |
|---|---|
| Input | 112 × 112, RGB, NHWC float32 |
| Preprocessing | `(x − 127.5) / 128` per channel |
| Output | 192-dimensional embedding, L2-normalized |
| Similarity | Cosine — a plain dot product, since vectors are unit length |
| Size | 5,233,552 bytes |
| Source | [MCarlomagno/FaceRecognitionAuth](https://github.com/MCarlomagno/FaceRecognitionAuth) (`mobilefacenet.tflite`) |

The file was confirmed to be a genuine MobileFaceNet rather than a renamed stand-in by
inspecting the flatbuffer's tensor scopes, which carry `MobileFaceNet/Conv_*` and
`Logits/LinearConv1x1` names.

**Detection** is Google **ML Kit Face Detection** in `PERFORMANCE_MODE_ACCURATE` with
`LANDMARK_MODE_ALL` and `CLASSIFICATION_MODE_ALL` — the landmarks drive alignment, and the
eyes-open and smiling probabilities feed the representative-shot score.

### Two different crops, deliberately

The crop fed to the embedder and the crop shown in the collage are **not** the same image, and
this is intentional:

- **Embedder crop** — `FaceAligner` produces a tight 112 × 112 patch, rotated and scaled so the
  eyes land on ArcFace's canonical positions (38.29, 51.69) and (73.53, 51.50). MobileFaceNet
  was trained on exactly this geometry; feeding it anything else degrades the embedding badly.
- **Collage crop** — `FaceCropUtils.cropGenerous` takes a wide crop with head and shoulders, per
  the brief's instruction not to crop tightly. It clamps its margins when a neighbouring face
  would otherwise be pulled into frame, checking perpendicular overlap so that a face directly
  *above* cannot shrink the left and right margins.

Conflating the two would mean either a bad embedding or an unpleasantly tight portrait.

---

## Similarity threshold

**0.35 cosine, measured on centered embeddings.**

The word *centered* carries the whole weight of that number, so it needs explaining.

### Why raw cosine does not work

Every face in one video shares a camera, a lighting setup, a colour grade, and often a
background. MobileFaceNet encodes all of that alongside identity, so every embedding from a
single clip carries a large common component. Measured on a real 30-second sample:

| Pair | Raw cosine |
|---|---|
| Average over all pairs | **0.601** |
| Two people known to be different (they share the frame at 10.2 s) | **0.684** |
| A second known-different pair | **0.494** |

Two *different* people scoring 0.684 is higher than the 0.5-ish threshold a textbook would
suggest. Any threshold in that range separates nothing — it sits below the impostor level it is
supposed to reject.

### The fix

`VectorMath.centered` subtracts the mean of all embeddings in the video and re-normalizes. That
removes the shared component while leaving identity intact. The same pairs afterwards:

| Pair | Raw | Centered |
|---|---|---|
| Known-different (10.2 s) | 0.684 | **0.010** |
| Known-different (second) | 0.494 | **0.109** |
| Distribution average | 0.601 | **−0.049** |

Impostors now sit near zero and the same person across frames sits above 0.9. There is a wide
empty band between them, which is where the threshold belongs.

Centering happens **once**, in `PipelineOrchestrator`, before anything compares two vectors — so
tracking and clustering both operate on the same scale. An earlier version centered only before
clustering while `TrackletBuilder` still gated on raw cosine at 0.55, which is *below* the 0.684
that impostors measure, so tracklets ran straight through cuts.

### Choosing 0.35

Sweeping the threshold against the sample's 231 tracklet pairs:

| Threshold | Result |
|---|---|
| 0.30 / 0.35 / 0.38 | **identical output** — a stable plateau |
| 0.40 | 7 clusters, starts shedding single appearances |
| 0.42 | 8 clusters |
| 0.45+ | 9 clusters, badly fragmented |

0.35 is the centre of that plateau, which gives the most margin in both directions. It is not a
value picked because it happened to produce a pleasing answer.

**The other thresholds**, all on centered embeddings, live in `TrackletBuilder`: IoU 0.2,
step-wise similarity 0.45, anchor similarity 0.25 against the tracklet's first frame, and a
500 ms maximum gap. Frames are sampled every 200 ms (5 fps), and an absence longer than 600 ms
ends an appearance.

---

## How it works

```
extract frames (5 fps)                         FrameExtractor
  -> detect faces + landmarks                  FaceDetectorStage
  -> align to 112x112 on the eye line          FaceAligner
  -> embed (MobileFaceNet, 192-d)              FaceEmbedder
  -> CENTER all embeddings, once               VectorMath.centered
  -> build tracklets (frame-to-frame runs)     TrackletBuilder
  -> cluster tracklets into people             IdentityClusterer
  -> split each person's timeline on gaps      AppearanceSplitter
  -> pick the representative shot              QualityScorer
  -> compose the collage                       CollageComposer
```

```
ui/            Compose screens (Home, Processing, Result) + theme
viewmodel/     CollageViewModel — pipeline state as StateFlow
pipeline/      extraction, detection, alignment, embedding, tracking,
               clustering, appearance splitting, quality scoring
pipeline/math/ VectorMath, GeometryMath, AgglomerativeClusterer,
               TimelineSegmenter — pure functions, all unit tested
collage/       CollageComposer, CollageSaver, ShareUtil
model/         FrameSample, FaceObservation, Tracklet, Appearance, Person
```

### Identity is resolved before appearances are counted

This ordering is the main design decision. The obvious alternative — track faces, then count the
tracks — makes the count only as good as frame-to-frame tracking. Tracking is a *spatial* signal,
and it is weakest exactly where these clips are hardest: at a hard cut between shots.

In cut-based portrait video, consecutive shots frame different people's heads in the same part of
the frame, so at a cut the outgoing and incoming bounding boxes overlap heavily. **IoU is high
precisely when identity has changed.** A tracker leaning on IoU bridges the cut, chains two people
into one track, and the count is then wrong with no way to recover.

So the pipeline clusters first, then splits each person's own timeline on temporal gaps — which is
exactly the brief's definition of an appearance, one continuous visible segment.

A consequence worth knowing before touching `TrackletBuilder`: **tracklets are allowed to
over-fragment.** One segment split into two tracklets costs nothing — both cluster to the same
person, their observations pool, and the timeline split rejoins them. A tracklet spanning two
*people* is unrecoverable. The thresholds there are asymmetric for that reason, and every match
must satisfy spatial **and** identity continuity.

### Clustering

`AgglomerativeClusterer` — average linkage, merging the globally best pair each round until
nothing exceeds the threshold. It replaced a single-pass greedy clusterer whose output depended on
the order items were visited, and whose running-mean centroid drifted with each merge so that
every subsequent merge became easier. Average linkage always re-reads actual members, so **the
output is a function of the embeddings alone and repeat runs are identical.** O(n³) on a few dozen
tracklets is free.

A tracklet's identity embedding is the mean of its **top-5 quality observations**. The full mean
dilutes identity with blurry and mid-turn frames; a single best frame stakes everything on one
frame's noise.

---

## Measured results

Sample 1, whose ground truth the brief gives as **5 people × 4 appearances = 20**:

```
151 frames -> 164 detections -> 22 tracklets -> 6 people, 20 appearances
```

**What is right:**

- **20 appearances — exactly the ground truth.**
- **Segmentation is correct.** The 18 single-person segments are all 1.2–1.4 s, and there are
  exactly two double-occupancy moments, at 10.2–11.4 s and 20.2–21.4 s. Those are precisely where
  the brief says A & B and C & D share the frame. 18 + 2 = 20.
- **Deterministic.** Repeat runs on the same video give byte-identical output.

**What is not:** the 20 appearances group into **6 identities instead of 5**. Two clusters are
exactly right at 4 appearances each; one holds 6 and is two people merged, with the remainder
scattered as 3, 1 and 2.

### Known limitation — this is the model's ceiling, not a loose threshold

It would be easy to present the 6-vs-5 gap as something a little more tuning would fix. It is not,
and the evidence says so specifically.

The over-merged cluster is two internally tight sub-groups — one at 0.865–0.892 similarity
internally, the other at 0.802–0.945 — bound to each other at **0.425 average linkage**. Splitting
them apart and giving each its correct fourth appearance would need some candidate pair to score
above that. The best candidate available anywhere is **+0.099**, and most are negative.

Worse, a merge that *must* happen for the correct answer sits at **0.402** — only 23 thousandths
below the wrong merge at 0.425. **No threshold can order those two correctly**, because the
embedding evidence genuinely points the wrong way. The sweep confirms it: raising the threshold
fragments correct clusters long before it splits the incorrect one.

The limit is MobileFaceNet's separability on this footage. Closing it means a stronger embedding
model, not a different number in `IdentityClusterer`.

---

## A bug worth recording

`FaceAligner` composes its alignment matrix with `post*` calls. Android's `Matrix` composes `post`
as `M = X · M` and `pre` as `M = M · X` — opposite directions. A version of this code used `pre*`
with the same call order, which ran the transform backwards and placed the left eye at
**(−140.96, −314.23)**, entirely off a 112 × 112 canvas. The crop sampled source pixels around
y ≈ 1055–1338 while the face sat at y ≈ 400.

**MobileFaceNet was embedding background, never a face**, on every detection of every run. That
single bug explained every symptom at once: different people scoring as similar (their backgrounds
match), the same person scoring as different (the background changed), and repeat runs disagreeing,
because near-textureless input leaves embeddings dominated by numerical noise.

The numeric proof is kept as a comment in `FaceAligner.kt` so that the revert does not get
"simplified" back.

---

## Tech stack

Kotlin 2.2.10 · Jetpack Compose (BOM 2026.02.01) · AGP 9.4.0 · ML Kit Face Detection 16.1.7 ·
LiteRT 2.1.0 · kotlinx-coroutines · minSdk 26 / targetSdk 37
