package com.abhishek.collage.pipeline

import com.abhishek.collage.model.Tracklet
import com.abhishek.collage.pipeline.math.AgglomerativeClusterer
import com.abhishek.collage.pipeline.math.VectorMath

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
     * threshold is much lower than a raw-cosine threshold would be. Measured on a
     * real clip, 0.30 was the middle of the band that recovered the right number
     * of people; below 0.28 people merge, above 0.35 they fragment.
     */
    val similarityThreshold: Float = 0.30f,
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
        return AgglomerativeClusterer.cluster(
            items = tracklets.indices.toList(),
            embeddingOf = { embeddingByIndex.getValue(it) },
            threshold = similarityThreshold
        ).map { indices -> PersonCluster(indices.map { tracklets[it] }) }
    }

    /**
     * The centered embeddings actually used for clustering, index-aligned with
     * [tracklets]. Exposed so diagnostic logging reports the same numbers the
     * clustering decision was made on rather than raw similarities, which are
     * on a different scale and would make the threshold look wrong.
     */
    fun clusteringEmbeddings(tracklets: List<Tracklet>): List<FloatArray> =
        VectorMath.centered(tracklets.map { representativeEmbedding(it) })

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
    fun representativeEmbedding(tracklet: Tracklet): FloatArray {
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
