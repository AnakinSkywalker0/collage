# Project Handoff — iykyk Android Internship Assignment

**Submission deadline:** Sunday, 6 September 2026, 11:59 PM IST
**Repo:** `AnakinSkywalker0/collage-IYKYK` (private, on GitHub — actual remote name is `collage.git`, confirm which one is the real submission target before you submit)
**Local project:** `D:\collage`

## What the app does

A Kotlin/Compose app (minSdk 26) that takes a 30-second portrait video, processes it entirely on-device, and produces a shareable collage of every unique person in it with their appearance count. Pipeline: extract frames → detect faces (ML Kit) → compute a face embedding per detection (bundled MobileFaceNet-class LiteRT model) → track continuous visible segments within the video ("appearances") → cluster appearances into unique people → pick each person's best representative shot by frontality/sharpness/eyes-open/smiling → render an Instagram-Story-style collage → save to gallery / share.

Graded 50% identity/appearance-count accuracy, 30% code quality and architecture, 20% UX and collage presentation.

## Architecture

```
ui/            Compose screens (Home, Processing, Result) + theme
viewmodel/     CollageViewModel — owns pipeline state as a StateFlow
pipeline/      FrameExtractor, FaceDetectorStage, FaceEmbedder, ImageQuality,
               FaceCropUtils, FaceAligner, AppearanceTracker, IdentityClusterer,
               QualityScorer, PipelineOrchestrator (wires it all together)
pipeline/math/ VectorMath, GeometryMath, GreedyClusterer — framework-free,
               unit-tested under plain JVM JUnit (no Robolectric needed)
collage/       CollageComposer (Canvas grid renderer), CollageSaver (MediaStore),
               ShareUtil (FileProvider + ACTION_SEND)
model/         FrameSample, FaceObservation, Appearance, Person, ProcessingState
```

Stack: Kotlin 2.2.10, Compose BOM 2026.02.01, AGP 9.4.0, ML Kit Face Detection 16.1.7, LiteRT 2.1.0 (the current name for TFLite — TFLite is in maintenance mode, LiteRT is a drop-in Gradle-coordinate swap with the same `Interpreter` API), Kotlin coroutines for all off-main-thread work. No backend, no network calls anywhere in the app.

## Where things stand right now

Every pipeline stage, the UI, save/share, and the Gradle setup are written and build. Three real, evidence-backed bugs were found and fixed today using the actual bundled `.tflite` model run in Python (not synthetic data) against real face crops and real frames pulled from your uploaded `sample2.mp4` — that's the reliable part. What's **not yet done**: rebuilding and rerunning on-device after today's three fixes, confirming Samples 1–3 all give correct counts, building the debug APK, recording the screen capture, and getting the GitHub push finished.

## Bugs found and fixed, in order

| # | Bug | Symptom | Root cause | Fix |
|---|---|---|---|---|
| 1 | `BuildConfig` unresolved | Android Studio build error | AGP disables `BuildConfig` generation by default now | `ShareUtil` uses `context.packageName` instead of `BuildConfig.APPLICATION_ID` |
| 2 | Keyframe-only frame sampling | Severe duplicate-frame sampling; wildly wrong counts on every video | `MediaMetadataRetriever.OPTION_CLOSEST_SYNC` only returns the nearest keyframe (1–3s apart), not the nearest frame | `FrameExtractor` switched to `OPTION_CLOSEST` |
| 3 | Generous crop fed to the embedder | Same root-cause family as #2 — wildly wrong counts | The embedder was receiving the 50%-margin "collage" crop instead of a tightly eye-aligned face; embeddings encoded framing/background instead of identity | New `FaceAligner.kt` — 2-point similarity transform on ML Kit eye landmarks to a canonical 112×112, used only for embedding; the generous crop stays separate for the collage tile |
| 4 | Stale track matched one frame late | Two different people's appearances silently merged across a gap | Gap-closing ran *after* that frame's matching instead of before | `AppearanceTracker` closes expired tracks at the top of the loop, before matching |
| 5 | IOU alone could win against a clearly different embedding | Identity swap risk between spatially close people | IOU says nothing about *who* someone is | Added `embeddingSanityFloor` — an IOU-only match must still not be an obviously-different person |
| 6 | Clustering threshold untested | 0.67 (a guess) never merged anything in simulation | No real or synthetic validation had been done | Threshold moved to 0.5, validated by porting the exact algorithm to Python against synthetic Sample-1-shaped data |
| 7 | Same-frame duplicate detections | ML Kit occasionally emits two boxes for one face in a single frame, spawning a phantom self-tracking "ghost" person | No dedup on ML Kit's raw output | `FaceDetectorStage.dedupeSameFaceDetections` — drops the smaller of two boxes with IOU > 0.6 in the same frame |
| 8 | Single-frame false positives | Stray misdetections counted as real appearances | No minimum persistence check | `AppearanceTracker` drops appearances with fewer than 2 observations |
| 9 | Appearance fragmentation (over-counting) | On-device: one person reported as 9 appearances instead of 4 | Measured with the real model: same-person cosine similarity swings from 0.85 down to **0.51** under nothing more than normal frame-to-frame landmark jitter — right at the matching threshold, so the IOU fallback needed to save the match was too strict (`iouThreshold=0.3`, `maxGapMs=600`) to reliably survive ordinary movement | `AppearanceTracker`: `iouThreshold` 0.3→0.2, `maxGapMs` 600→1000ms |
| 10 | Collage tile shows two different people in one image | Screenshot evidence: "×8" tile showed a man and a hijab-wearing woman merged in one cell | `FaceCropUtils`'s 50%-margin generous crop had no awareness of other faces in the same frame — when two people share a frame (the assignment's own worked example has this), the margin bled into the neighbour | `expandedRect`/`cropGenerous` now take `otherFacesInFrame` and cap each directional margin at half the gap to the nearest neighbour on that side |
| 11 | Identity under-merging | On-device: 5 real people in `sample2.mp4` collapsed into 3 clusters | Measured with the real model on one clean frame per person: all 5 separate cleanly (max similarity 0.47, even the two glasses-wearing men only hit 0.38) — so the model and threshold are fine. The bug was `IdentityClusterer` clustering on the **mean embedding of every observation in a track**, including blurry/off-angle/mid-turn frames, which drags a track's representative point toward whichever other person it's closest to | `IdentityClusterer` now clusters on each appearance's **best-quality observation** (reusing `QualityScorer`'s frontality/sharpness ranking) instead of the noisy mean |

Bugs 9–11 were all found today by actually running the bundled `face_embedder.tflite` model in Python — installed `ai-edge-litert` (same runtime family as the app's LiteRT dependency) in a sandbox, ported `FaceAligner`'s exact alignment math, and tested against real photos (a public Obama/Biden test set, for a clean two-person baseline) and then against real frames pulled from your own `sample2.mp4` with hand-measured eye landmarks. That's what makes 9–11 different from 1–8: they're measured, not guessed.

## Why the git setup took a detour

`D:\collage` is mounted into the sandbox in a way that doesn't support git's internal lock/object file operations — repeated `Operation not permitted` errors on `.git/objects/*/tmp_obj_*` and `index.lock`. Git operations on this project have to be run from Android Studio (or a terminal) directly on your machine, not from this session. A `.gitattributes` was added to stop the line-ending churn that was making every `.idea/*` file and `gradlew.bat` show as 100% changed on every commit (Windows CRLF vs repo LF — cosmetic, not a real change). Last known state: a commit `e3a9242 Initial Working Commit` was pushed and `origin/main` was in sync, with 78 files including the model and all pipeline code.

## Open items before you can submit

1. **Rebuild and rerun** on your device now that fixes #9, #10, #11 are in — none of them have been verified on-device yet, only measured against the real model offline.
2. **Sample 1**: there was an earlier unresolved question — appearance[1] (10.2–11.4s) was nested inside appearance[2]'s range, and appearance[3] overlapped its tail, in a log from before fixes #9–11. Re-check this video specifically once you rerun; it may already be resolved by the tracker fix.
3. **Samples 2 and 3**: Sample 2 was checked in detail today (5 real people, confirmed by you) — rerun it after the rebuild. Sample 3 hasn't been tested at all yet.
4. **Commit and push** the day's changes (`AppearanceTracker.kt`, `FaceCropUtils.kt`, `IdentityClusterer.kt`, `PipelineOrchestrator.kt`, `README.md`) via Android Studio's VCS panel — this session cannot write to the repo directly (see above).
5. **Confirm the actual repo name** — `AnakinSkywalker0/collage-IYKYK` vs `AnakinSkywalker0/collage.git` were both referenced at different points; make sure the one you submit is the one with your latest commits.
6. **Build the debug APK** (`./gradlew assembleDebug` or Android Studio's Build menu) once everything above checks out.
7. **Record the screen capture** — up to 60 seconds, no narration needed, must clearly show processing + appearance counts + the finished collage for all three sample videos, held long enough to read.
8. **Finish the README** — it already documents the stack, build steps, embedding model, and the full threshold-calibration story (including today's real-model measurements); just make sure it still reads correctly after your final on-device numbers are in.

## Key files if you need to find something fast

- `pipeline/AppearanceTracker.kt` — within-video tracking (today's fix: gap/IOU tolerance)
- `pipeline/IdentityClusterer.kt` — cross-appearance clustering (today's fix: best-observation embedding)
- `pipeline/FaceCropUtils.kt` — collage crop generosity (today's fix: neighbour-aware margin)
- `pipeline/FaceAligner.kt` — 2-point eye alignment feeding the embedder
- `pipeline/PipelineOrchestrator.kt` — wires every stage together, all the diagnostic Logcat lines live here (tag `CollagePipeline`)
- `README.md` — full build/setup instructions, embedding model details, and the complete threshold-calibration writeup
