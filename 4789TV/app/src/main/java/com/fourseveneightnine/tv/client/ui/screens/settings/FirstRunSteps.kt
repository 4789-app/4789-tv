package com.fourseveneightnine.tv.client.ui.screens.settings

/** A source's progress, mapped from `SnapshotState` at the call site so this file stays pure. */
internal enum class SourcePhase { Idle, Loading, Ready, Failed }

/** The four glyph states of a first-run row, spec §2.3. */
internal enum class StepState { Pending, Running, Done, Failed }

internal data class FirstRunStep(val label: String, val state: StepState)

internal data class FirstRunProgress(
    val steps: List<FirstRunStep>,
    /** Every row has settled, pass or fail. "Start watching" turns enabled here (spec §2.8.7). */
    val settled: Boolean,
    /** The line under the footer when something went wrong, or null. */
    val note: String?,
)

/**
 * The first-refresh list, spec §2.3. Pure, so the whole ladder is testable without a receiver.
 *
 * A failed row stays red and never disappears (spec §2.8.6), and a failed add-on does not trap
 * the viewer on this screen — settling is what enables the button, not success.
 */
internal object FirstRunSteps {

    fun derive(
        settingsSaved: Boolean,
        addonsTotal: Int,
        addonsResolved: Int,
        addonsFailed: Int,
        catalogs: SourcePhase,
        posterItems: Int,
    ): FirstRunProgress {
        val settings = if (settingsSaved) StepState.Done else StepState.Running

        val addonsSettled = addonsResolved + addonsFailed >= addonsTotal
        val addonState = when {
            !settingsSaved -> StepState.Pending
            addonsTotal == 0 -> StepState.Done
            !addonsSettled -> StepState.Running
            addonsFailed > 0 -> StepState.Failed
            else -> StepState.Done
        }
        val addonLabel =
            if (addonsTotal == 0) "Add-ons" else "Add-ons $addonsResolved of $addonsTotal"

        val catalogState = when {
            !settingsSaved -> StepState.Pending
            catalogs == SourcePhase.Idle -> StepState.Pending
            catalogs == SourcePhase.Loading -> StepState.Running
            catalogs == SourcePhase.Failed -> StepState.Failed
            else -> StepState.Done
        }

        val catalogSettled = catalogState == StepState.Done || catalogState == StepState.Failed
        val posterState = when {
            catalogState == StepState.Failed -> StepState.Failed
            !catalogSettled -> StepState.Pending
            posterItems > 0 -> StepState.Done
            else -> StepState.Running
        }

        val steps = listOf(
            FirstRunStep("Settings saved", settings),
            FirstRunStep(addonLabel, addonState),
            FirstRunStep("Catalogs", catalogState),
            FirstRunStep("Posters", posterState),
        )
        return FirstRunProgress(
            steps = steps,
            settled = steps.all { it.state == StepState.Done || it.state == StepState.Failed },
            note = note(addonsFailed, catalogState),
        )
    }

    private fun note(addonsFailed: Int, catalogs: StepState): String? = when {
        addonsFailed == 1 -> "Couldn't reach 1 add-on. You can fix this later in Settings."
        addonsFailed > 1 -> "Couldn't reach $addonsFailed add-ons. You can fix this later in Settings."
        catalogs == StepState.Failed -> "Couldn't load the catalogs. You can refresh later in Settings."
        else -> null
    }
}
