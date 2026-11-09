package com.example.nasbrowser.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Minimal ZIP support using the platform zip implementation (no extra dependency). */
object ZipUtils {

    fun unzip(zip: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        val root = destDir.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zip)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val target = File(destDir, entry.name)
                // Guard against zip-slip path traversal.
                if (!target.canonicalPath.startsWith(root)) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zis.copyTo(out, 64 * 1024) }
                }
                zis.closeEntry()
            }
        }
    }

    fun zipFiles(files: List<File>, out: File) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            files.filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
            }
        }
    }
}
