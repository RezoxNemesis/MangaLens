package com.mangalens.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replaces mutable CA data atomically; it never writes code or truncates active readers. */
internal object NativeSystemTrustBundleStore {
    fun install(target: File, pem: ByteArray, checkActive: () -> Unit = {}): Boolean {
        checkActive()
        if (target.isFile && target.length() == pem.size.toLong() && target.readBytes().contentEquals(pem)) return false
        val parent = target.parentFile ?: throw IOException("Native CA bundle has no private parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create the native CA directory")
        val staged = File.createTempFile(".system-ca-", ".partial", parent)
        try {
            FileOutputStream(staged).use { it.write(pem); it.fd.sync() }
            checkActive()
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            staged.delete()
        }
    }
}
