package com.tokalot.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Pictures the keyboard pastes (a new screenshot, a copied picture), copied into Tokalot's cache and served to
 * the app being typed in by [KeyboardImageProvider], which only lets that app read the one picture it was given.
 */
object KeyboardImages {
    private const val DIR = "kb_paste"

    fun authority(ctx: Context) = ctx.packageName + ".kbimages"

    /** Copies [from] into the cache and returns the address the text box's app is given. */
    fun share(ctx: Context, from: Uri, mime: String): Uri {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        // Only the latest few are kept; an app reads the picture as it's pasted.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val ext = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
        val file = File(dir, "picture-${System.currentTimeMillis()}.$ext")
        ctx.contentResolver.openInputStream(from)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(file.name).build()
    }

    fun file(ctx: Context, uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(File(ctx.cacheDir, DIR), name).takeIf { it.isFile }
    }

    fun mime(name: String) = when (name.substringAfterLast('.')) {
        "jpg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/png"
    }
}

/** Read-only, not exported: an app can open a picture only through the permission the keyboard grants it. */
class KeyboardImageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = uri.lastPathSegment?.let { KeyboardImages.mime(it) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw java.io.FileNotFoundException()
        val f = KeyboardImages.file(ctx, uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        val f = KeyboardImages.file(ctx, uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
