package com.abhishek.collage.pipeline

import com.abhishek.collage.model.FaceObservation
import kotlin.math.abs

/**
 * Picks the best representative shot for a person out of all their observed
 * faces across every merged appearance. Combines frontality, sharpness, eyes
 * open, and smiling into one score, favouring frontal / crisp / eyes-open /
 * pleasant shots per the assignment brief. Also prefers non-clipped faces.
 */
object QualityScorer {

    data class ScoredObservation(val observation: FaceObservation, val score: Float)

    private const val W_FRONTALITY = 0.35f
    private const val W_SHARPNESS = 0.25f
    private const val W_EYES_OPEN = 0.20f
    private const val W_SMILING = 0.20f

    fun pickBest(observations: List<FaceObservation>): ScoredObservation? {
        if (observations.isEmpty()) return null

        val nonClipped = observations.filter { !it.touchesFrameEdge }
        val pool = if (nonClipped.isNotEmpty()) nonClipped else observations

        val maxSharpness = pool.maxOf { it.sharpness }.coerceAtLeast(1e-3f)

        return pool
            .map { ScoredObservation(it, score(it, maxSharpness)) }
            .maxByOrNull { it.score }
    }

    private fun score(obs: FaceObservation, maxSharpness: Float): Float {
        val frontality = frontality(obs.eulerY, obs.eulerZ)
        val sharpnessNorm = (obs.sharpness / maxSharpness).coerceIn(0f, 1f)
        val eyesOpen = meanOrDefault(obs.leftEyeOpenProb, obs.rightEyeOpenProb)
        val smiling = obs.smilingProb ?: 0.5f

        return W_FRONTALITY * frontality +
            W_SHARPNESS * sharpnessNorm +
            W_EYES_OPEN * eyesOpen +
            W_SMILING * smiling
    }

    private fun frontality(eulerY: Float, eulerZ: Float): Float {
        val deviation = (abs(eulerY) + abs(eulerZ)) / 90f
        return (1f - deviation).coerceIn(0f, 1f)
    }

    private fun meanOrDefault(a: Float?, b: Float?): Float {
        return when {
            a != null && b != null -> (a + b) / 2f
            a != null -> a
            b != null -> b
            else -> 0.5f
        }
    }
}
