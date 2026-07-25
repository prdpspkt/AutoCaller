package com.example.aicallresponder

import android.content.Context
import java.io.File

/**
 * Where downloaded models live: app-specific external storage
 * (/storage/emulated/0/Android/data/<pkg>/files/models). No storage permission needed, and it's
 * removed automatically on uninstall. The absolute path is what [WhisperSttEngine] loads.
 */
object ModelStore {

    fun modelsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun fileFor(context: Context, model: WhisperModel): File =
        File(modelsDir(context), model.fileName)

    fun partFor(context: Context, model: WhisperModel): File =
        File(modelsDir(context), model.fileName + ".part")

    /** A completed download: the final file exists and is plausibly non-truncated. */
    fun isDownloaded(context: Context, model: WhisperModel): Boolean =
        fileFor(context, model).let { it.exists() && it.length() > 1_000_000L }

    fun delete(context: Context, model: WhisperModel) {
        fileFor(context, model).delete()
        partFor(context, model).delete()
    }
}
