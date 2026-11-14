package com.example.nasbrowser.smb

import com.example.nasbrowser.util.RecycleItem
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.util.EnumSet

/**
 * Write-side SMB operations on an already-connected [DiskShare] (SMB 2.0.2 - 3.1.1).
 * All paths use forward slashes internally and are converted to SMB backslashes here.
 *
 * Deleting goes through a recycle bin implemented as an in-place rename, because SMB2/3 renames
 * cannot move an entry to another directory. Restoring is therefore instant and copies no data.
 */
object SmbWriteOps {

    private fun smbPath(p: String): String = p.replace('/', '\\').trim('\\')

    private fun join(parent: String, name: String): String =
        if (parent.isBlank()) name else "$parent/$name"

    private val writeMasks = EnumSet.of(
        AccessMask.FILE_WRITE_DATA,
        AccessMask.FILE_APPEND_DATA,
        AccessMask.FILE_WRITE_ATTRIBUTES,
        AccessMask.FILE_READ_ATTRIBUTES
    )

    private val readMasks = EnumSet.of(AccessMask.GENERIC_READ)

    private val renameMasks = EnumSet.of(
        AccessMask.DELETE,
        AccessMask.FILE_READ_ATTRIBUTES,
        AccessMask.FILE_WRITE_ATTRIBUTES
    )

    private val shareAccess = EnumSet.of(
        SMB2ShareAccess.FILE_SHARE_READ,
        SMB2ShareAccess.FILE_SHARE_WRITE,
        SMB2ShareAccess.FILE_SHARE_DELETE
    )

    private val normalAttrs = EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL)

    fun mkdir(ds: DiskShare, parent: String, name: String) {
        ds.mkdir(smbPath(join(parent, name)))
    }

    /** Physical removal — used only by "彻底删除" in the recycle bin. */
    fun delete(ds: DiskShare, entry: SmbEntry) {
        val path = smbPath(entry.path)
        if (entry.isDirectory) ds.rmdir(path, true) else ds.rm(path)
    }

    /** True when a file or folder with this name already exists in the share. */
    fun exists(ds: DiskShare, path: String): Boolean = try {
        val p = smbPath(path)
        ds.fileExists(p) || ds.folderExists(p)
    } catch (_: Exception) {
        false
    }

    /** Best-effort removal of a half-written destination after the user cancels a transfer. */
    fun removeFile(ds: DiskShare, parent: String, name: String) {
        try { ds.rm(smbPath(join(parent, name))) } catch (_: Exception) {}
    }

    private fun open(ds: DiskShare, path: String, isDir: Boolean): DiskEntry {
        val p = smbPath(path)
        val opts = if (isDir) {
            EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
        } else {
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        }
        return if (isDir) {
            ds.openDirectory(
                p, renameMasks, normalAttrs, shareAccess,
                SMB2CreateDisposition.FILE_OPEN, opts
            )
        } else {
            ds.openFile(
                p, renameMasks, normalAttrs, shareAccess,
                SMB2CreateDisposition.FILE_OPEN, opts
            )
        }
    }

    /**
     * SMB2 FileRenameInformation (MS-FSCC 2.4.42.2) requires a FULL pathname relative to the
     * share root whenever RootDirectory is 0, which is always the case over the network.
     * smbj copies the string straight into that field, so passing a bare file name makes the
     * server move the entry to the share root: it vanishes from the current folder, which is
     * exactly the "rename turned into delete" behaviour seen on the device.
     */
    private fun renameTo(ds: DiskShare, from: String, isDir: Boolean, target: String) {
        val handle = open(ds, from, isDir)
        try {
            handle.rename(smbPath(target), false)
        } finally {
            try { handle.close() } catch (_: Exception) {}
        }
    }

    fun rename(ds: DiskShare, entry: SmbEntry, newName: String) {
        val parent = entry.path.substringBeforeLast('/', "")
        val target = if (parent.isBlank()) newName else "$parent/$newName"
        renameTo(ds, entry.path, entry.isDirectory, target)
    }

    /** Stream a local file into a new (or overwritten) remote file, 1 MB chunks. */
    fun upload(
        ds: DiskShare,
        parent: String,
        name: String,
        input: InputStream,
        onBytes: ((Long) -> Unit)? = null
    ) {
        val remote = ds.openFile(
            smbPath(join(parent, name)),
            writeMasks,
            normalAttrs,
            shareAccess,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        try {
            remote.outputStream.use { out ->
                input.use { src ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        onBytes?.invoke(n.toLong())
                    }
                }
            }
        } finally {
            try { remote.close() } catch (_: Exception) {}
        }
    }

    /** Server-side file copy in 1 MB chunks; used by 复制 / 移动到 and clipboard paste. */
    fun copyFile(
        ds: DiskShare,
        srcPath: String,
        dstPath: String,
        onBytes: ((Long) -> Unit)? = null
    ) {
        val src = ds.openFile(
            smbPath(srcPath), readMasks, normalAttrs, shareAccess,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        val dst = ds.openFile(
            smbPath(dstPath), writeMasks, normalAttrs, shareAccess,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        try {
            src.inputStream.use { input ->
                dst.outputStream.use { out ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        onBytes?.invoke(n.toLong())
                    }
                }
            }
        } finally {
            try { src.close() } catch (_: Exception) {}
            try { dst.close() } catch (_: Exception) {}
        }
    }

    /** Create a UTF-8 text file (or replace its content) inside [parent]. */
    fun createText(ds: DiskShare, parent: String, name: String, text: String) {
        writeText(ds, join(parent, name), text)
    }

    /** Overwrite a remote text file with UTF-8 content. */
    fun writeText(ds: DiskShare, path: String, text: String) {
        val remote = ds.openFile(
            smbPath(path),
            writeMasks,
            normalAttrs,
            shareAccess,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        try {
            remote.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } finally {
            try { remote.close() } catch (_: Exception) {}
        }
    }

    /**
     * Soft-delete: rename the entry inside its own directory to ".trash_<ts>_<name>".
     * Returns (hidden name, hidden path inside the share).
     */
    fun moveToTrash(ds: DiskShare, entry: SmbEntry): Pair<String, String> {
        val parent = entry.path.substringBeforeLast('/', "")
        val base = if (entry.name.length > 180) entry.name.take(180) else entry.name
        val hiddenName = "${SmbQueryOps.TRASH_PREFIX}${System.currentTimeMillis()}_$base"
        val hiddenPath = if (parent.isBlank()) hiddenName else "$parent/$hiddenName"
        // Full path relative to the share root, otherwise the hidden file lands at the root
        // while the recycle-bin record points at the original folder.
        renameTo(ds, entry.path, entry.isDirectory, hiddenPath)
        return hiddenName to hiddenPath
    }

    /**
     * Records written by older builds stored "parent/.trash_xxx" while the buggy bare-name
     * rename had actually placed the hidden entry at the share root. Try the recorded path
     * first, then the bare name at the root, so old bin entries remain restorable / removable.
     */
    private fun trashCandidates(item: RecycleItem): List<String> {
        val base = item.trashPath.substringAfterLast('/')
        return listOf(item.trashPath, base).distinct()
    }

    /** Restore a recycle-bin item to its original path (full path relative to the share root). */
    fun restore(ds: DiskShare, item: RecycleItem) {
        var last: Exception? = null
        for (candidate in trashCandidates(item)) {
            try {
                val handle = open(ds, candidate, item.isDirectory)
                try {
                    handle.rename(smbPath(item.path), false)
                } finally {
                    try { handle.close() } catch (_: Exception) {}
                }
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("回收站中的文件已不存在")
    }

    /**
     * Permanently remove a recycle-bin item. Both candidate locations are attempted: some SMB
     * servers do not raise an error for a missing path, so returning after the first "success"
     * could leave the real hidden file behind at the share root.
     */
    fun purge(ds: DiskShare, item: RecycleItem) {
        var last: Exception? = null
        var removed = false
        for (candidate in trashCandidates(item)) {
            try {
                val p = smbPath(candidate)
                if (item.isDirectory) ds.rmdir(p, true) else ds.rm(p)
                removed = true
            } catch (e: Exception) {
                last = e
            }
        }
        if (!removed) throw last ?: IllegalStateException("回收站中的文件已不存在")
    }
}
