package io.github.lime3ds.android.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.content.ActivityNotFoundException
import androidx.documentfile.provider.DocumentFile
import io.github.lime3ds.android.R
import io.github.lime3ds.android.LimeApplication
import android.app.AlertDialog
import android.util.Log
import java.io.File

object UserDirUtils {
    // Refresh the cache for the target directory

    fun openFileManager(context: Context, targetDir: String) {
    LimeApplication.documentsTree.refreshCache(targetDir)

        // Check if the target directory exists
        val targetDirUri = LimeApplication.documentsTree.getUri(targetDir)
        if (!FileUtil.exists(targetDirUri.toString())) {
            Toast.makeText(
                context,
                context.resources.getString(R.string.invalid_directory),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Proceed with opening the file manager
        try {
            context.startActivity(getFileManagerIntentOnDocumentProvider(context, Intent.ACTION_VIEW, targetDir))
            return
        } catch (_: ActivityNotFoundException) {
        }

        try {
            context.startActivity(getFileManagerIntentOnDocumentProvider(context, "android.provider.action.BROWSE", targetDir))
            return
        } catch (_: ActivityNotFoundException) {
        }

        // Just try to open the file manager, try the package name used on "normal" phones
        try {
            context.startActivity(getFileManagerIntent(context, "com.google.android.documentsui"))
            return
        } catch (_: ActivityNotFoundException) {
        }

        try {
            // Next, try the AOSP package name
            context.startActivity(getFileManagerIntent(context, "com.android.documentsui"))
            return
        } catch (_: ActivityNotFoundException) {
        }

        Toast.makeText(
            context,
            context.resources.getString(R.string.no_file_manager),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun getFileManagerIntent(context: Context, packageName: String): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setClassName(packageName, "com.android.documentsui.files.FilesActivity")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return intent
    }

    private fun getFileManagerIntentOnDocumentProvider(context: Context, action: String, targetDir: String): Intent {
        val intent = Intent(action)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.data = LimeApplication.documentsTree.getUri(targetDir) // Use DocumentsTree to resolve the URI
        intent.addFlags(
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return intent
    }
}
