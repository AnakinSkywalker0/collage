package com.abhishek.collage.pipeline

import com.abhishek.collage.model.Appearance
import com.abhishek.collage.pipeline.math.GreedyClusterer

/**
 * Merges per-video Appearances that belong to the same real person. A thin
 * Appearance-shaped adapter over the framework-free GreedyClusterer -- see
 * that class for the actual algorithm.
 *
 * similarityThreshold is cosine similarity on L2-normalized embeddings
 * (equivalent to a dot product). 0.5 is a starting point, not a final
 * answer -- the right value depends entirely on how separated your actual
 * embedding model's genuine (same-person) vs impostor (different-person)
 * similarity distributions are, which you can only know by running it. To
 * calibrate against a real video: log cosine similarity between every pair
 * of appearance mean-embeddings along with whether they're truly the same
 * person (from the worked example / your own eyeballing), then pick a
 * threshold between the top of the impostor range and the bottom of the
 * genuine range. Simulating this exact algorithm against synthetic data
 * shaped like the Sample 1 worked example (5 people x 4 appearances, two
 * overlapping segments) with a realistic genuine/impostor gap consistently
 * recovered 5 clusters of 4 across a 0.35-0.55 threshold band -- 0.67 (an
 * earlier guess) was too high and never merged anything. 0.5 is the safer
 * default; re-tune once real embeddings are in hand.
 *
 * Clusters on each appearance's BEST-quality observation embedding, not the
 * mean of every observation in the track. Verified against a real 5-person
 * clip: single clean, well-aligned frames for all 5 people separated with a
 * solid margin (max pairwise similarity 0.47, everyone else well below), yet
 * the on-device run merged them into 3 clusters. The gap is exactly what a
 * plain mean predicts -- an appearance's track legitimately contains blurry,
 * off-angle, and mid-turn frames alongside good ones, and averaging their
 * embeddings in drags the appearance's representative point away from the
 * person's true identity centre and toward whichever other person it's
 * closest to. QualityScorer already ranks observations by frontality and
 * sharpness (the exact properties that also make an embedding trustworthy)
 * to pick the collage shot; reusing that same pick as the clustering key
 * uses the cleanest evidence available instead of diluting it with noise.
 */
class IdentityClusterer(
    val similarityThreshold: Float = 0.5f
) {

    class PersonCluster(val appearances: List<Appearance>)

    fun cluster(appearances: List<Appearance>): List<PersonCluster> {
        val sorted = appearances.sortedBy { it.startMs }
        val clusters = GreedyClusterer.cluster(
            items = sorted,
            embeddingOf = { representativeEmbedding(it) },
            threshold = similarityThreshold
        )
        return clusters.map { PersonCluster(it.members) }
    }

    /**
     * The embedding actually used to place this appearance in identity space
     * -- its best-quality observation, not the noisy mean of every
     * observation in the track (see class doc). Exposed so diagnostic
     * logging reflects the same numbers the clustering decision was
     * actually made on.
     */
    fun representativeEmbedding(appearance: Appearance): FloatArray {
        return QualityScorer.pickBest(appearance.observations)?.observation?.embedding
            ?: appearance.meanEmbedding
    }
}
