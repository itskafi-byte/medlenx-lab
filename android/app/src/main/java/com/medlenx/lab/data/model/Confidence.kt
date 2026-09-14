package com.medlenx.lab.data.model

/**
 * Confidence thresholds.
 *
 * The two source designs disagree, so this file is the single place the boundary is
 * written down and everything else derives from it:
 *
 *  - Figma `ConfBadge` (App.tsx:70-75) — the rendered widget — flips at **85**:
 *      `if (pct >= 85) emerald "{pct}%"`
 *      `if (pct < 70)  amber  "🏴 {pct}% manual flag"`
 *      `else           orange "🤖 {pct}% AI Guess"`
 *  - The web app's caption (templates/index.html:320) describes the orange band as
 *    "AI Guess <80%". That is user-facing copy, not logic, and it is reproduced
 *    verbatim in [com.medlenx.lab.ui.screens.scan.VerifyMedicinesSection].
 *
 * The code follows the widget, because the widget is total: every value maps to
 * exactly one band. A `< 80` boundary instead leaves 80..84 in a fourth, unlabelled
 * state that matches neither design.
 */
object ConfidenceBands {
    /** At or above this the scan is high confidence: emerald, plain "NN%". */
    const val HIGH_MIN_PERCENT = 85

    /** Below this the scan needs a human: amber, "NN% manual flag". */
    const val MANUAL_FLAG_BELOW_PERCENT = 70
}

/** The three confidence states the UI must keep visually distinct. */
enum class ConfidenceBand { High, AiGuess, ManualFlag }

/** Maps a 0..100 percentage onto its band. Total: every input hits exactly one branch. */
fun confidenceBand(percent: Int): ConfidenceBand = when {
    percent >= ConfidenceBands.HIGH_MIN_PERCENT -> ConfidenceBand.High
    percent < ConfidenceBands.MANUAL_FLAG_BELOW_PERCENT -> ConfidenceBand.ManualFlag
    else -> ConfidenceBand.AiGuess
}
