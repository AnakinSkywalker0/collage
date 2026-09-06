package com.abhishek.collage.pipeline

import android.util.Log
import com.abhishek.collage.model.Tracklet
import com.abhishek.collage.pipeline.math.AgglomerativeClusterer
import com.abhishek.collage.pipeline.math.VectorMath

private const val TAG = "CollagePipeline"

/**
 * Groups [Tracklet]s that belong to the same real person.
 *
 * Embeddings are CENTERED before anything is compared — that step is what makes
 * the threshold meaningful at all, and it is explained in [VectorMath.centered].
 * PipelineOrchestrator logs the sorted pairwise spread of these centered values
 * on every run so the threshold can be checked against real data: pick a value
 * in the gap between the impostor ceiling and the genuine floor.
 */
class IdentityClusterer(
    /**
     * Cosine threshold on CENTERED embeddings (see [VectorMath.centered]) — not
     * on raw ones, and the two scales are completely different. Raw similarities
     * on a single video sit around 0.6 on average because every embedding shares
     * a large common component; centered ones average about 0.0, so this
     * threshold is much lower than a raw-cosine threshold would be.
     *
     * 0.35 is the centre of a measured stable plateau, not a guess. Sweeping this
     * value against a real 30s clip's 231 tracklet pairs, the output is identical
     * for every threshold from 0.30 to 0.38; at 0.40 clusters start shedding
     * single appearances, and by 0.45 the result has fragmented badly. Sitting in
     * the middle of that plateau gives the most margin on both sides.
     */
    val similarityThreshold: Float = 0.35f,
    /**
     * How many of a tracklet's best observations are averaged into its identity
     * embedding. See [representativeEmbedding].
     */
    private val topKObservations: Int = 5
) {

    class PersonCluster(val tracklets: List<Tracklet>)

    fun cluster(tracklets: List<Tracklet>): List<PersonCluster> {
        if (tracklets.isEmpty()) return emptyList()
        // Center across this video's tracklets before comparing anything --
        // without it, different people routinely score 0.5-0.7 against each other
        // and no threshold separates identities. Index-aligned with `tracklets`.
        val centered = VectorMath.centered(tracklets.map { representativeEmbedding(it) })
        val embeddingByIndex = tracklets.indices.associateWith { centered[it] }
        val result = AgglomerativeClusterer.cluster(
            items = tracklets.indices.toList(),
            embeddingOf = { embeddingByIndex.getValue(it) },
            threshold = similarityThreshold
        )
        logSweep(tracklets)
        return result.map { indices -> PersonCluster(indices.map { tracklets[it] }) }
    }

    /**
     * TEMPORARY calibration sweep -- strip before submission.
     *
     * Evaluates every candidate configuration on the real tracklets and logs what
     * each WOULD produce, without changing what the app returns. Done in one pass
     * because the sweep costs microseconds while each device run costs minutes,
     * and because comparing variants measured on the same input is the only way
     * to attribute a difference to the variant rather than to the run.
     *
     * Two axes:
     *  - topK: how many of a tracklet's best observations form its identity
     *    vector. Never validated against real footage until now.
     *  - linkage: whether tracklets are compared as one averaged vector (current)
     *    or by their underlying observations. Averaging destroys a good frame
     *    inside an otherwise poor tracklet, which is exactly the shape of the
     *    remaining errors.
     */
    private fun logSweep(tracklets: List<Tracklet>) {
        Log.i(TAG, "DIAG FOCUS on observation max-linkage (ground truth: 5 people, 4 each)")

        val obs = tracklets.map { t -> t.observations.map { it.embedding } }
        val cache = HashMap<Long, FloatArray>()
        fun pairSims(a: Int, b: Int): FloatArray {
            val i = minOf(a, b)
            val j = maxOf(a, b)
            return cache.getOrPut(i.toLong() * 1000 + j) {
                val values = FloatArray(obs[i].size * obs[j].size)
                var at = 0
                for (x in obs[i]) for (y in obs[j]) values[at++] = VectorMath.cosineSim(x, y)
                values.sortedArray()
            }
        }
        fun linkage(percentile: Float): (Int, Int) -> Float = { a, b ->
            val values = pairSims(a, b)
            values[((values.size - 1) * percentile).toInt().coerceIn(0, values.size - 1)]
        }

        // Finer threshold sweep around the plateau found in the previous run, to
        // locate its centre rather than sit on an edge.
        for (percentile in floatArrayOf(1.0f, 0.95f)) {
            val measure = linkage(percentile)
            for (threshold in floatArrayOf(0.50f, 0.55f, 0.58f, 0.60f, 0.62f, 0.65f, 0.70f)) {
                val clusters = AgglomerativeClusterer.clusterBySimilarity(
                    tracklets.indices.toList(), threshold, measure
                )
                Log.i(
                    TAG,
                    "DIAG  p=%.2f thr=%.2f -> %d clusters %s"
                        .format(percentile, threshold, clusters.size, clusters.map { it.size }.sorted())
                )
            }
        }

        // Membership and timing for the centre of the plateau, so the 3+3 split
        // can be checked against the video rather than assumed correct, and so we
        // can see which clusters the orphan tracklets sit next to.
        val measure = linkage(1.0f)
        val clusters = AgglomerativeClusterer.clusterBySimilarity(
            tracklets.indices.toList(), 0.60f, measure
        )
        Log.i(TAG, "DIAG --- membership at p=1.00 thr=0.60 ---")
        clusters.forEachIndexed { index, cluster ->
            val spans = cluster.joinToString(" ") { "t$it:${tracklets[it].startMs}-${tracklets[it].endMs}" }
            Log.i(TAG, "DIAG  C$index n=${cluster.size} $spans")
        }

        // What absorbing each orphan into its best-scoring host would do. Reported
        // rather than applied: a merge is only worth making if the best host beats
        // the runner-up clearly, and that margin is what these lines show.
        val hosts = clusters.filter { it.size > 1 }
        for (orphan in clusters.filter { it.size == 1 }) {
            val stray = orphan.single()
            val scored = hosts.map { host -> host to host.maxOf { measure(stray, it) } }
                .sortedByDescending { it.second }
            val best = scored.firstOrNull()
            val runnerUp = scored.getOrNull(1)
            Log.i(
                TAG,
                "DIAG  orphan t$stray best=%.3f (C%d) runnerUp=%.3f margin=%.3f".format(
                    best?.second ?: -1f,
                    best?.let { clusters.indexOf(it.first) } ?: -1,
                    runnerUp?.second ?: -1f,
                    (best?.second ?: 0f) - (runnerUp?.second ?: 0f)
                )
            )
        }
    }


    /**
     * The point in identity space that represents this tracklet: the mean of its
     * [topKObservations] highest-quality observations, re-normalized.
     *
     * Both extremes are worse. Averaging EVERY observation dilutes the identity
     * with the blurry, mid-turn, half-occluded frames that any real track
     * contains, dragging the tracklet toward whoever it happens to be nearest.
     * Using the single best observation removes that dilution but makes the
     * whole identity decision rest on one frame, so ordinary per-frame noise
     * moves it -- which is what made repeat runs of the same video disagree.
     *
     * Top-k keeps the selectivity (QualityScorer already ranks by frontality and
     * sharpness, the same properties that make an embedding trustworthy) while
     * averaging over enough frames for noise to cancel.
     */
    private fun representativeEmbedding(
        tracklet: Tracklet,
        k: Int = topKObservations
    ): FloatArray {
        val best = QualityScorer.rank(tracklet.observations)
            .take(k)
            .map { it.observation.embedding }
        return if (best.isEmpty()) {
            VectorMath.mean(tracklet.observations.map { it.embedding })
        } else {
            VectorMath.mean(best)
        }
    }
}
