package se.sensnology.spotnav.ui.history

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
 * Hands one exported CSV to the share sheet. The app has no AndroidX, so this is the small piece a
 * file provider would be: it serves only the files in this app's own export folder, read-only, and
 * only to the app the share sheet picked (the grant travels with the intent).
 */
class CsvExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/csv"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val file = fileFor(context ?: return null, uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray<Any?>())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val file = fileFor(context ?: return null, uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,100}")

        private fun authority(context: Context) = context.packageName + ".exports"

        private fun folder(context: Context) = File(context.cacheDir, "exports")

        /** A file name that is safe to put in a path: the answer's own when it is plain, else a fixed one. */
        fun safeName(proposed: String?): String =
            proposed?.takeIf { SAFE_NAME.matches(it) && !it.startsWith(".") } ?: "spotnav-sessions.csv"

        /** Write [csv] as [filename] (replacing earlier exports) and give the address to share it by. */
        fun write(context: Context, filename: String?, csv: String): Uri {
            val dir = folder(context)
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val name = safeName(filename)
            File(dir, name).writeText(csv, Charsets.UTF_8)
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(name).build()
        }

        private fun fileFor(context: Context, uri: Uri): File? {
            val name = uri.pathSegments.singleOrNull()?.takeIf { SAFE_NAME.matches(it) } ?: return null
            val file = File(folder(context), name)
            return file.takeIf { it.isFile }
        }
    }
}
