/*
 * Copyright 2026 Bada contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.airdrop

import com.dd.plist.NSDictionary
import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.connection.FileSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.channels.Channels
import java.nio.file.Files

class AirDropProtocolTest {
    @Test
    fun `binary ask plist round trips file metadata`() {
        val file = NSDictionary().apply {
            put("FileName", plistString("hello.txt"))
            put("FileSize", plistLong(5))
            put("FileType", plistString("public.plain-text"))
            put("FileBomPath", plistString("./hello.txt"))
        }
        val ask = NSDictionary().apply {
            put("SenderComputerName", plistString("Mac"))
            put("Files", plistArray(listOf(file)))
        }

        val decoded = AirDropPlist.decode(AirDropPlist.encode(ask))

        assertThat(AirDropPlist.string(decoded, "SenderComputerName")).isEqualTo("Mac")
        assertThat(AirDropPlist.fileOffers(decoded)).containsExactly(
            AirDropFileOffer("hello.txt", 5, "public.plain-text", "./hello.txt"),
        )
    }

    @Test
    fun `old ASCII CPIO round trips file bytes and folder path`() {
        val root = Files.createTempDirectory("airdrop-test").toFile()
        val bytes = "hello".encodeToByteArray()
        val source = FileSource(
            name = "hello.txt",
            size = bytes.size.toLong(),
            mimeType = "text/plain",
            lastModifiedTimestampMillis = 0,
            payloadId = 1,
            parentFolder = "Folder",
        ) { Channels.newChannel(bytes.inputStream()) }

        val archive = AirDropArchive.create(root, listOf(source))
        var path: String? = null
        var result: ByteArray? = null
        archive.inputStream().use { input ->
            AirDropArchive.extract(input) { entryPath, _, entry ->
                path = entryPath
                result = entry.readNBytes(bytes.size)
            }
        }

        assertThat(path).isEqualTo("Folder/hello.txt")
        assertThat(result).isEqualTo(bytes)
    }

    @Test
    fun `archive traversal path is rejected`() {
        assertThrows<IllegalArgumentException> { AirDropArchive.sanitizeArchivePath("../secret") }
    }
}
