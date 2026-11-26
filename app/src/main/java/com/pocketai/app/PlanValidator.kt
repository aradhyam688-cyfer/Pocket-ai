package com.pocketai.app

data class ValidationResult(
    val isValid: Boolean,
    val validatedPlan: List<PlannedStep>,
    val error: String? = null
)

object PlanValidator {
    private val validDirs = setOf("up", "down", "left", "right")

    fun validate(steps: List<PlannedStep>): ValidationResult {
        if (steps.isEmpty()) return ValidationResult(false, emptyList(), "No valid actions found in plan")
        if (steps.size > 10) return ValidationResult(false, emptyList(), "Plan exceeds 10-step safety limit")

        val normalized = mutableListOf<PlannedStep>()
        for (step in steps) {
            when (step.action) {
                TaskAction.OPEN_APP -> {
                    if (step.target.isBlank()) return ValidationResult(false, emptyList(), "OPEN_APP missing app name")
                    normalized.add(step.copy(requiresConfirmation = true))
                }
                TaskAction.SEARCH, TaskAction.WEB_SEARCH_NARRATE -> {
                    if (step.target.isBlank()) return ValidationResult(false, emptyList(), "${step.action} missing query")
                    normalized.add(step)
                }
                TaskAction.MESSAGE_CONTACT -> {
                    if (step.target.isBlank() || step.extra.isBlank()) {
                        return ValidationResult(false, emptyList(), "MESSAGE_CONTACT missing person or text")
                    }
                    normalized.add(step)
                }
                TaskAction.TAP_TARGET, TaskAction.CALL_CONTACT, TaskAction.SUMMARIZE_MESSAGES -> {
                    if (step.target.isBlank()) return ValidationResult(false, emptyList(), "TAP_TARGET missing label")
                    normalized.add(step)
                }
                TaskAction.TAP_ORDINAL -> {
                    val idx = step.target.toIntOrNull()
                    if (idx == null || idx !in 1..10) return ValidationResult(false, emptyList(), "Invalid ordinal index")
                    normalized.add(step)
                }
                TaskAction.SCROLL -> {
                    val dir = step.target.lowercase().trim()
                    if (dir !in validDirs) return ValidationResult(false, emptyList(), "Invalid scroll direction: $dir")
                    normalized.add(step.copy(target = dir))
                }
                else -> normalized.add(step)
            }
        }
        return ValidationResult(true, normalized)
    }
}
