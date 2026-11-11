package com.music.spotui.data.offline

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Exports downloaded tracks from app storage to the public phone storage (Music/GhostSpot/).
 * Songs exported here appear directly in the user's mobile file manager and music players.
 */
object PublicStorageExporter {
    private const val TAG = "PublicStorageExporter"
    private const val FOLDER_NAME = "GhostSpot"

    /**
     * Exports an [OfflineTrack] to the user's public Music/GhostSpot folder.
     * Returns the exported [File] on success, or null on failure.
     */
    fun exportTrack(context: Context, track: OfflineTrack): File? {
        val audioDir = File(context.filesDir, "offline/audio")
        val sourceFile = File(audioDir, track.file)
        if (!sourceFile.exists()) {
            Log.e(TAG, "Source file does not exist: ${sourceFile.absolutePath}")
            return null
        }

        // Target public Music/GhostSpot folder
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val ghostSpotDir = File(musicDir, FOLDER_NAME)
        if (!ghostSpotDir.exists() && !ghostSpotDir.mkdirs()) {
            Log.e(TAG, "Failed to create directory: ${ghostSpotDir.absolutePath}")
            return null
        }

        // Sanitize file name: "Artist - Title.m4a" or .mp3
        val cleanArtist = track.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val cleanTitle = track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val extension = when {
            track.file.endsWith(".mp3", ignoreCase = true) -> ".mp3"
            track.file.endsWith(".flac", ignoreCase = true) -> ".flac"
            else -> ".m4a"
        }
        val targetName = if (cleanArtist.isNotBlank()) "$cleanArtist - $cleanTitle$extension" else "$cleanTitle$extension"
        val targetFile = File(ghostSpotDir, targetName)

        return try {
            if (OfflineCrypto.isEncrypted(sourceFile)) {
                decryptTo(context, sourceFile, targetFile)
            } else {
                copyTo(sourceFile, targetFile)
            }

            // Trigger MediaScanner so the song shows up in Android MediaStore & music players immediately
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(targetFile.absolutePath),
                null
            ) { path, uri ->
                Log.i(TAG, "Scanned $path into MediaStore: $uri")
            }

            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export track ${track.title}: ${e.message}", e)
            targetFile.delete()
            null
        }
    }

    private fun decryptTo(context: Context, source: File, destination: File) {
        val key = OfflineCrypto.key(context)
        FileInputStream(source).use { input ->
            // Skip 4-byte MSE1 magic
            val magic = ByteArray(4)
            if (input.read(magic) != 4) throw IOException("File too short for magic")
            // Read 16-byte IV
            val iv = ByteArray(16)
            if (input.read(iv) != 16) throw IOException("File too short for IV")

            val cipher = OfflineCrypto.cipher(key, iv, 0)
            FileOutputStream(destination).buffered(256 * 1024).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    output.write(cipher.update(buffer, 0, bytesRead))
                }
                cipher.doFinal().takeIf { it.isNotEmpty() }?.let(output::write)
            }
        }
    }

    private fun copyTo(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}
