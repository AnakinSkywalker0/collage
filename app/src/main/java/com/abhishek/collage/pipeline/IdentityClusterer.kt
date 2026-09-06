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
        val clusters = AgglomerativeClusterer.cluster(
            items = tracklets.indices.toList(),
            embeddingOf = { embeddingByIndex.getValue(it) },
            threshold = similarityThreshold
        )
        logAlignmentDiagnostics(clusters, tracklets, embeddingByIndex)
        return clusters.map { indices -> PersonCluster(indices.map { tracklets[it] }) }
    }

    /** TEMPORARY calibration logging -- strip before submission. */
    private fun logAlignmentDiagnostics(
        clusters: List<List<Int>>,
        tracklets: List<Tracklet>,
        embeddingByIndex: Map<Int, FloatArray>
    ) {
        clusters.forEachIndexed { i, cluster ->
            val obs = cluster.flatMap { tracklets[it].observations }
            val widths = obs.map { it.boundingBox.width() }.sorted()
            val yaw = obs.map { kotlin.math.abs(it.eulerY) }.sorted()
            val roll = obs.map { kotlin.math.abs(it.eulerZ) }.sorted()
            // Average pairwise similarity BETWEEN this cluster's own tracklets --
            // the number that says whether the embedder recognises this person as
            // themselves at all. Never measured before now.
            val internal = mutableListOf<Float>()
            for (a in cluster.indices) {
                for (b in a + 1 until cluster.size) {
                    internal.add(
                        VectorMath.cosineSim(
                            embeddingByIndex.getValue(cluster[a]),
                            embeddingByIndex.getValue(cluster[b])
                        )
                    )
                }
            }
            Log.i(
                TAG,
                ("DIAG  c$i n=${cluster.size} obs=${obs.size} " +
                    "faceW med=${widths[widths.size / 2]} min=${widths.first()} max=${widths.last()} " +
                    "|yaw| med=%.0f max=%.0f |roll| med=%.0f " +
                    "edge=${obs.count { it.touchesFrameEdge }} " +
                    "internalSim=%s").format(
                    yaw[yaw.size / 2], yaw.last(), roll[roll.size / 2],
                    if (internal.isEmpty()) "n/a" else
                        "avg=%.3f min=%.3f".format(internal.average(), internal.min())
                )
            )
        }
        for (i in clusters.indices) {
            val row = clusters.indices.filter { it > i }.joinToString(" ") { j ->
                val sim = clusters[i].flatMap { a ->
                    clusters[j].map { b ->
                        VectorMath.cosineSim(embeddingByIndex.getValue(a), embeddingByIndex.getValue(b))
                    }
                }.average()
                "c$j=%.3f".format(sim)
            }
            if (row.isNotEmpty()) Log.i(TAG, "DIAG  c$i vs $row")
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
    private fun representativeEmbedding(tracklet: Tracklet): FloatArray {
        val best = QualityScorer.rank(tracklet.observations)
            .take(topKObservations)
            .map { it.observation.embedding }
        return if (best.isEmpty()) {
            VectorMath.mean(tracklet.observations.map { it.embedding })
        } else {
            VectorMath.mean(best)
        }
    }
}
