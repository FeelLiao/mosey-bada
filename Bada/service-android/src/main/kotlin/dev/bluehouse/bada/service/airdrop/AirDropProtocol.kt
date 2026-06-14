/*
 * Copyright 2026 Bada contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.airdrop

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import dev.bluehouse.bada.protocol.connection.FileSource
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

internal object AirDropPlist {
    fun encode(dictionary: NSDictionary): ByteArray =
        ByteArrayOutputStream().use { output ->
            PropertyListParser.saveAsBinary(dictionary, output)
            output.toByteArray()
        }

    fun decode(bytes: ByteArray): NSDictionary =
        PropertyListParser.parse(bytes) as? NSDictionary
            ?: throw IllegalArgumentException("AirDrop plist root is not a dictionary")

    fun string(dictionary: NSDictionary, key: String): String? =
        (dictionary.objectForKey(key) as? NSString)?.content

    fun fileOffers(dictionary: NSDictionary): List<AirDropFileOffer> {
        val files = dictionary.objectForKey("Files") as? NSArray ?: return emptyList()
        return files.array.mapNotNull { value ->
            val item = value as? NSDictionary ?: return@mapNotNull null
            val name = string(item, "FileName") ?: return@mapNotNull null
            val size = (item.objectForKey("FileSize") as? NSNumber)?.longValue() ?: 0L
            val type = string(item, "FileType") ?: "public.data"
            val path = string(item, "FileBomPath") ?: "./$name"
            AirDropFileOffer(name, size, type, path)
        }
    }
}

internal data class AirDropFileOffer(
    val name: String,
    val size: Long,
    val uti: String,
    val bomPath: String,
)

internal object AirDropArchive {
    fun create(
        cacheDir: File,
        files: List<FileSource>,
    ): File {
        val archive = File(cacheDir, "airdrop-${UUID.randomUUID()}.cpio")
        CpioArchiveOutputStream(archive.outputStream().buffered(), CpioArchiveOutputStream.FORMAT_OLD_ASCII).use { output ->
            files.forEach { source ->
                val relative = listOf(source.parentFolder, source.name).filter { it.isNotBlank() }.joinToString("/")
                val entry = CpioArchiveEntry(CpioArchiveEntry.FORMAT_OLD_ASCII, "./$relative")
                entry.size = source.size
                entry.mode = CpioArchiveEntry.C_ISREG.toLong() or 0x1A4L
                entry.time = source.lastModifiedTimestampMillis.takeIf { it > 0 }?.div(1000L) ?: 0L
                output.putArchiveEntry(entry)
                source.openChannel().use { channel ->
                    val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                    var remaining = source.size
                    while (remaining > 0) {
                        buffer.clear()
                        buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                        val read = channel.read(buffer)
                        check(read >= 0) { "Unexpected EOF reading ${source.name}" }
                        output.write(buffer.array(), 0, read)
                        remaining -= read
                    }
                }
                output.closeArchiveEntry()
            }
            output.finish()
        }
        return archive
    }

    fun extract(
        input: InputStream,
        onFile: (path: String, size: Long, InputStream) -> Unit,
    ) {
        CpioArchiveInputStream(input.buffered()).use { archive ->
            while (true) {
                val entry = archive.nextCPIOEntry ?: break
                if (entry.isDirectory) continue
                val safePath = sanitizeArchivePath(entry.name)
                onFile(safePath, entry.size, archive)
            }
        }
    }

    internal fun sanitizeArchivePath(raw: String): String {
        val normalized = raw.removePrefix("./").replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) {
            "Unsafe AirDrop archive path: $raw"
        }
        return parts.joinToString("/")
    }
}

internal fun airDropMimeType(uti: String, fileName: String): String? =
    when {
        uti.startsWith("public.image") || fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) -> "image/*"
        uti.startsWith("public.movie") || fileName.endsWith(".mov", true) || fileName.endsWith(".mp4", true) -> "video/*"
        uti == "public.plain-text" || fileName.endsWith(".txt", true) -> "text/plain"
        uti == "com.adobe.pdf" || fileName.endsWith(".pdf", true) -> "application/pdf"
        else -> null
    }

internal fun plistString(value: String): NSString = NSString(value)
internal fun plistBoolean(value: Boolean): NSNumber = NSNumber(value)
internal fun plistLong(value: Long): NSNumber = NSNumber(value)
internal fun plistData(value: ByteArray): NSData = NSData(value)
internal fun plistArray(values: List<NSObject>): NSArray = NSArray(*values.toTypedArray())
