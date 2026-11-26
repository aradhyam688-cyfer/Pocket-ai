package com.pocketai.app

import java.util.UUID

enum class TaskStatus {
    PLANNING, RUNNING, PAUSED, WAITING_CONFIRMATION, COMPLETED, FAILED, CANCELLED
}

enum class TaskAction {
    OPEN_APP, SEARCH, MESSAGE_CONTACT, DOOMSCROLL, TAP_ORDINAL, TAP_TARGET,
    FIND_OPEN_PDF, GO_APP_HOME, WEB_SEARCH_NARRATE, ANALYZE_SCREEN,
    COMPOSE_EMAIL, FLASHLIGHT, SET_ALARM, SET_TIMER, SCROLL, BACK
}

data class PlannedStep(
    val action: TaskAction,
    val target: String = "",
    val extra: String = "",
    val requiresConfirmation: Boolean = false
)

data class AgentTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val originalInstruction: String,
    val objective: String,
    val plan: List<PlannedStep>,
    val currentStepIndex: Int = 0,
    val observations: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val successCondition: String = "All planned steps verified",
    val failureCondition: String = "Retry limit exceeded or user cancelled",
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val status: TaskStatus = TaskStatus.PLANNING
)
