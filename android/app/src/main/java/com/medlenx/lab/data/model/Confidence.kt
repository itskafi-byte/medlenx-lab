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

    /**
     * The Rx Audit drawer uses a *second, looser* threshold, and it is not a
     * contradiction of the badge - they answer different questions.
     *
     *  - 85 decides how the confidence badge is coloured ([confidenceBand]).
     *  - 80 decides which rows the drawer treats as needing follow-up: it drives the
     *    "<80%" filter pill, the #FFF7ED row wash, and the "Verify against Medex"
     *    link (App.tsx:844, 866, 868).
     *
     * Both come from the web app, which shows a caption reading "AI Guess <80%"
     * (templates/index.html:320) while its ConfBadge flips at 85. A row at 82% is
     * therefore emerald-badged *and* flagged for verification, which is intentional
     * and matches what the rep sees on the web.
     */
    const val AUDIT_FOLLOW_UP_BELOW_PERCENT = 80
}

/** The three confidence states the UI must keep visually distinct. */
enum class ConfidenceBand { High, AiGuess, ManualFlag }

/** Maps a 0..100 percentage onto its band. Total: every input hits exactly one branch. */
fun confidenceBand(percent: Int): ConfidenceBand = when {
    percent >= ConfidenceBands.HIGH_MIN_PERCENT -> ConfidenceBand.High
    percent < ConfidenceBands.MANUAL_FLAG_BELOW_PERCENT -> ConfidenceBand.ManualFlag
    else -> ConfidenceBand.AiGuess
}

/**
 * Whether the audit drawer flags this row for follow-up - the "<80%" filter, the
 * orange row wash and the "Verify against Medex" link. Deliberately *not* the same
 * question as [confidenceBand]; see [ConfidenceBands.AUDIT_FOLLOW_UP_BELOW_PERCENT].
 */
fun needsAuditFollowUp(percent: Int): Boolean =
    percent < ConfidenceBands.AUDIT_FOLLOW_UP_BELOW_PERCENT
