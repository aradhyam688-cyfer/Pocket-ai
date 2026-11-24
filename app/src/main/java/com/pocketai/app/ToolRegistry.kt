package com.pocketai.app

import android.content.Context
import android.content.Intent

object ToolRegistry {

    private val toolRegex = Regex("""\[TOOL:\s*([a-zA-Z0-9_]+)(?:\|([^\]]*))?\]""")

    fun executeIfPresent(
        context: Context,
        modelOutput: String,
        onSaveMemory: ((String) -> Unit)? = null
    ): String? {
        val match = toolRegex.find(modelOutput) ?: return null
        val name = match.groupValues[1].trim()
        val rawArgs = match.groupValues[2]
        val args = rawArgs.split("|")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx > 0) part.substring(0, idx).trim() to part.substring(idx + 1).trim() else null
            }.toMap()

        val svc = PocketAccessibilityService.instance

        return when (name) {
            "open_app" -> {
                val query = args["package"] ?: args["app"] ?: return "Missing package parameter"
                launchAppByQuery(context, query)
            }
            "read_screen" -> svc?.getCompressedScreen() ?: "Accessibility service not enabled"
            "tap" -> {
                val id = args["id"]?.toIntOrNull() ?: return "Invalid id"
                svc?.tap(id) ?: "Accessibility service not enabled"
            }
            "type_text" -> {
                val id = args["id"]?.toIntOrNull() ?: return "Invalid id"
                val text = args["text"] ?: return "Missing text"
                val submit = args["submit"]?.toBooleanStrictOrNull() ?: true
                svc?.typeText(id, text, submit) ?: "Accessibility service not enabled"
            }
            "back" -> svc?.pressBack() ?: "Accessibility service not enabled"
            "save_memory" -> {
                val fact = args["fact"] ?: return "Missing fact"
                onSaveMemory?.invoke(fact)
                "Saved memory: $fact"
            }
            else -> "Unknown tool: $name"
        }
    }

    fun resolveFallbackApp(context: Context, userText: String): Pair<String, String>? {
        val match = Regex("""(?i)\bopen\s+([a-z0-9._+-]+(?:\s+[a-z0-9._+-]+)?)(?=\s+(?:and|then|to)\b|$)""")
            .find(userText.trim()) ?: return null
        val query = match.groupValues[1].trim()
        return resolveApp(context, query)
    }

    private fun resolveApp(context: Context, query: String): Pair<String, String>? {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(query)?.let { return query to query }

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val qNorm = query.lowercase().replace(Regex("[^a-z0-9]"), "")
        val hit = apps.firstOrNull {
            it.loadLabel(pm).toString().equals(query, ignoreCase = true)
        } ?: apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "").contains(qNorm)
        } ?: return null

        return hit.loadLabel(pm).toString() to hit.activityInfo.packageName
    }

    private fun launchAppByQuery(context: Context, query: String): String {
        val pm = context.packageManager
        val resolved = resolveApp(context, query) ?: return "App not found: $query"
        val (label, pkg) = resolved
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return "Cannot launch $pkg"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Opened $label ($pkg)"
    }
}
