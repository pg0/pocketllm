package com.redcoralstudios.pocketllm.llm

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
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
     * Tells the model what day it is.
     *
     * Without this a local model has no clock, only a training cutoff, and
     * silently treats "now" as whenever its data ended. Asked about an event in
     * 2026 it will call it upcoming with complete confidence -- not a
     * hallucination exactly, but wrong in a way no amount of fact-checking
     * prompt can catch, because the model has no way to know it is wrong.
     *
     * Deliberately part of every system prompt, including a custom one: the
     * date is a fact about the world, not a matter of style.
     */
    fun dateLine(today: LocalDate = LocalDate.now()): String {
        val stamp = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
        return "Today's date is $stamp. Your training data ends well before this, " +
            "so treat your own sense of \"current\" or \"upcoming\" as out of date. " +
            "Anything dated before today has already happened, even if it is in the " +
            "future as far as your training goes. If a question turns on recent " +
            "events, say what you do and do not know rather than assuming nothing " +
            "has happened since your training."
    }

    /**
     * Stops the model refusing to read what the app already fetched for it.
     *
     * Left to itself, an instruction-tuned model answers anything time-shaped
     * with "I am a large language model and cannot provide real-time updates" -
     * boilerplate learned in training, and it fires even when a `<web_context>`
     * block containing today's answer is sitting in the same prompt. The
     * retrieval instructions live in the user turn, so they lose to a habit
     * this strong; the correction has to be in the system prompt.
     */
    const val WEB_CONTEXT_RULE: String =
        "This app can retrieve web pages, run searches and read Wikipedia, and it does " +
            "so before you are asked. Any <web_context> block in a message was fetched " +
            "from the live internet moments ago. Treat it as current fact and answer " +
            "from it. Never reply that you are a language model, that you cannot access " +
            "the internet, or that you cannot provide real-time information when such a " +
            "block is present - the retrieval already happened. If a block is present " +
            "but does not contain the answer, say precisely that instead."

    /**
     * Keeps the answer in the language of the question.
     *
     * Three things push a multilingual model towards English here and they add
     * up: this system prompt is written in English, Wikipedia is queried in
     * English first because those articles are longer and better cited, and
     * search results are mostly English too. A German question can end up as
     * the only German text in the whole prompt, and the model follows the
     * majority. Naming the rule explicitly is cheaper than translating the
     * prompt or giving up the English sources.
     */
    const val LANGUAGE_RULE: String =
        "Always answer in the language the user wrote their message in. These " +
            "instructions are in English and retrieved sources are often in English; " +
            "neither has any bearing on the language of your reply. Translate anything " +
            "you quote into the user's language, and leave proper names as they are."

    /**
     * Gemma 4 has a real system role, so this text becomes its own turn at the
     * head of the conversation. When a dial moves mid-chat the prompt is
     * restated inline rather than resetting the conversation.
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

        return listOf(dateLine(), LANGUAGE_RULE, WEB_CONTEXT_RULE, grounding, tone)
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
