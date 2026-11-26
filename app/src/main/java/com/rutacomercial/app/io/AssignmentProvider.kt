package com.rutacomercial.app.io

import android.net.Uri
import androidx.core.content.FileProvider

class AssignmentProvider : FileProvider() {
    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty()
        return when {
            name.startsWith("Propuesta-") -> "application/vnd.rutacomercial.proposal"
            name.startsWith("result-") || name.startsWith("Avance-") || name.startsWith("Recorrido-final-") -> "application/vnd.rutacomercial.result"
            name.startsWith("task-") || name.startsWith("Tarea-") -> "application/vnd.rutacomercial.task"
            else -> super.getType(uri) ?: "application/json"
        }
    }
}
