package com.redcoralstudios.pocketllm.llm

import kotlin.math.min
import kotlin.math.roundToInt

/** Sampler settings handed to the native layer for one generation. */
data class GenerationParams(
    val temp: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float,
    val maxTokens: Int,
)

/**
 * Translates the app's two user-facing dials into sampler values and a system
 * prompt.
 *
 * - **creativity** loosens sampling: higher temperature, wider nucleus.
 * - **factuality** tightens grounding: it rewrites the system prompt *and*
 *   caps how far creativity is allowed to push the temperature.
 *
 * The cap is deliberate. The two dials pull in opposite directions, and a user
 * who sets both to maximum means "be imaginative but do not invent facts" --
 * which in sampler terms has to resolve as low temperature plus a strict
 * prompt. Without the cap, the fact-check setting would be silently overridden.
 */
object Dials {

    const val MIN = 0
    const val MAX = 100

    /** Sensible default: moderately creative, moderately grounded. */
    const val DEFAULT_CREATIVITY = 45
    const val DEFAULT_FACTUALITY = 55

    /**
     * At factuality 0 creativity is unconstrained; at 100 it may reach only a
     * quarter of its range.
     */
    fun effectiveCreativity(creativity: Int, factuality: Int): Float {
        val c = creativity.coerceIn(MIN, MAX) / 100f
        val f = factuality.coerceIn(MIN, MAX) / 100f
        val ceiling = 1f - f * 0.75f
        return min(c, ceiling)
    }

    fun params(creativity: Int, factuality: Int, maxTokens: Int = 1024): GenerationParams {
        val c = effectiveCreativity(creativity, factuality)
        return GenerationParams(
            temp = 0.05f + c * 1.35f,
            topP = 0.80f + c * 0.20f,
            topK = (20 + c * 80f).roundToInt(),
            minP = 0.10f - c * 0.09f,
            repeatPenalty = 1.10f - c * 0.10f,
            maxTokens = maxTokens,
        )
    }

    /**
     * Gemma templates carry no system role, so this text is prepended to the
     * first user turn. Changing either dial therefore requires a chat reset --
     * see [LlmEngine.applyDials].
     */
    fun systemPrompt(creativity: Int, factuality: Int): String {
        val f = factuality.coerceIn(MIN, MAX)
        val c = creativity.coerceIn(MIN, MAX)

        val grounding = when {
            f >= 75 -> """
                Accuracy is the priority. Assert only what you are confident is correct.
                Never invent specifics: no made-up names, numbers, dates, citations, URLs,
                API signatures, or quotations. If you do not know something, say so plainly
                rather than guessing. Separate what you know from what you are inferring,
                and label estimates as estimates.
            """.trimIndent()

            f >= 50 -> """
                Prefer accuracy over completeness. Flag anything you are unsure about
                instead of stating it as fact, and say when you are inferring rather
                than recalling. Do not fabricate specifics.
            """.trimIndent()

            f >= 25 -> """
                Aim to be correct, and note when you are uncertain.
            """.trimIndent()

            else -> ""
        }

        val tone = when {
            c >= 75 -> """
                Think expansively. Offer unusual angles, analogies, and more than one
                possible direction. Speculation is welcome as long as it is marked as such.
            """.trimIndent()

            c >= 40 -> """
                Be direct and concrete. Add a useful observation beyond the literal question
                when it helps.
            """.trimIndent()

            else -> """
                Be concise and literal. Answer what was asked, without elaboration.
            """.trimIndent()
        }

        return listOf(grounding, tone)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    /** Short label for the settings UI. */
    fun describe(creativity: Int, factuality: Int): String {
        val c = when {
            creativity >= 75 -> "exploratory"
            creativity >= 40 -> "balanced"
            else -> "literal"
        }
        val f = when {
            factuality >= 75 -> "strict"
            factuality >= 50 -> "careful"
            factuality >= 25 -> "relaxed"
            else -> "unconstrained"
        }
        val capped = effectiveCreativity(creativity, factuality) < creativity / 100f
        return if (capped) "$c / $f (fact-check is capping creativity)" else "$c / $f"
    }
}
