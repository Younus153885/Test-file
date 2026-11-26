package com.example.data.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StandaloneBuilderLargeApkTest {

    private fun buildMinimalValidDex(sortedStrings: List<String>): ByteArray {
        val sorted = sortedStrings.sortedWith { a, b ->
            val minLen = minOf(a.length, b.length)
            var cmp = 0
            for (i in 0 until minLen) {
                val d = a[i].code - b[i].code
                if (d != 0) {
                    cmp = d
                    break
                }
            }
            if (cmp != 0) cmp else (a.length - b.length)
        }
        val stringDataOut = ByteArrayOutputStream()
        val stringDataOffsets = IntArray(sorted.size)
        val stringIdsOff = 112
        val dataBaseOff = stringIdsOff + sorted.size * 4
        for (i in sorted.indices) {
            stringDataOffsets[i] = dataBaseOff + stringDataOut.size()
            val s = sorted[i]
            // ULEB128 of UTF-16 length (all ASCII < 128 in test strings)
            stringDataOut.write(s.length and 0x7F)
            stringDataOut.write(s.toByteArray(StandardCharsets.UTF_8))
            stringDataOut.write(0)
        }
        val totalSize = dataBaseOff + stringDataOut.size()
        val dex = ByteArray(totalSize)
        val header = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        // magic "dex\n035\u0000"
        val magic = "dex\n035\u0000".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(magic, 0, dex, 0, 8)
        header.putInt(32, totalSize)
        header.putInt(36, 112) // header_size
        header.putInt(40, 0x12345678) // endian_tag
        header.putInt(56, sorted.size) // string_ids_size
        header.putInt(60, stringIdsOff) // string_ids_off
        for (i in sorted.indices) {
            header.putInt(stringIdsOff + i * 4, stringDataOffsets[i])
        }
        val sd = stringDataOut.toByteArray()
        System.arraycopy(sd, 0, dex, dataBaseOff, sd.size)

        // Compute SHA-1 and Adler32
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(dex, 32, dex.size - 32)
        System.arraycopy(sha1.digest(), 0, dex, 12, 20)
        val adler = Adler32()
        adler.update(dex, 12, dex.size - 12)
        header.putInt(8, adler.value.toInt())
        return dex
    }

    private fun readDexStrings(dex: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(56)
        val off = buf.getInt(60)
        val res = ArrayList<String>(count)
        for (i in 0 until count) {
            val sOff = buf.getInt(off + i * 4)
            var p = sOff
            while (p < dex.size && (dex[p].toInt() and 0x80) != 0) p++
            p++ // skip last uleb128 byte
            var end = p
            while (end < dex.size && dex[end].toInt() != 0) end++
            res.add(String(dex, p, end - p, StandardCharsets.UTF_8))
        }
        return res
    }

    private fun assertDexChecksumsValid(dex: ByteArray) {
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(dex, 32, dex.size - 32)
        val expectedSha1 = sha1.digest()
        assertArrayEquals("DEX SHA-1 mismatch", expectedSha1, dex.copyOfRange(12, 32))

        val adler = Adler32()
        adler.update(dex, 12, dex.size - 12)
        assertEquals("DEX Adler32 mismatch", adler.value.toInt(), buf.getInt(8))
    }

    @Test
    fun `patchTargetDexBytecode preserves strict string_ids sort order when adjacent prefix string exists`() {
        val rawDex = buildMinimalValidDex(
            listOf(
                "Landroid/os/Looper;",
                "Landroid/os/SystemClock;",
                "Landroid/os/SystemClockCompat;",
                "Landroid/view/animation/AnimationUtils;",
                "Landroid/view/animation/AnimationUtilsCompat;",
                "android.os.SystemProperties",
                "android.os.SystemProperties.get"
            )
        )
        val patched = StandaloneApkBuilderEngine.patchTargetDexBytecode(rawDex, "com.facebook.katana")
        val strings = readDexStrings(patched)
        // Because mutating any of these 3 strings in-place violates strict prev < cur < next order
        // against its *Compat / .get neighbor, all 3 must be left untouched rather than corrupting ART DEX verification.
        assertEquals(readDexStrings(rawDex), strings)
        assertDexChecksumsValid(patched)
    }

    @Test
    fun `patchTargetDexBytecode mutates safe strings and keeps string_ids strictly sorted`() {
        val rawDex = buildMinimalValidDex(
            listOf(
                "Landroid/os/Looper;",
                "Landroid/os/SystemClock;",
                "Landroid/os/SystemProperties;",
                "Landroid/view/View;",
                "Landroid/view/animation/AnimationUtils;",
                "android.os.Build",
                "android.os.SystemProperties",
                "java.lang.String"
            )
        )
        val patched = StandaloneApkBuilderEngine.patchTargetDexBytecode(rawDex, "com.example.game")
        val strings = readDexStrings(patched)
        assertTrue(strings.contains("Landroid/os/SystemClocl;"))
        assertTrue(strings.contains("Landroid/os/SystemPropertiet;"))
        assertTrue(strings.contains("Landroid/view/animation/AnimationUtilt;"))
        assertTrue(strings.contains("android.os.SystemPropertiet"))
        // Strict lexicographical ascending order invariant required by ART DexFileVerifier
        for (i in 0 until strings.size - 1) {
            assertTrue(
                "Out-of-order string_ids at $i: ${strings[i]} >= ${strings[i + 1]}",
                strings[i] < strings[i + 1]
            )
        }
        assertDexChecksumsValid(patched)
    }

    @Test
    fun `repackageAndPatchZip preserves out-of-order and non-standard multi-dex entries without collisions`() {
        val tmpDir = java.nio.file.Files.createTempDirectory("phantom_multidex_test").toFile()
        try {
            val baseApk = File(tmpDir, "base.apk")
            val splitApk = File(tmpDir, "split_feature.apk")
            val unsignedOut = File(tmpDir, "unsigned.apk")
            val signedOut = File(tmpDir, "signed.apk")

            val dex1 = buildMinimalValidDex(listOf("Lcom/bigapp/Dex1;"))
            val dex2 = buildMinimalValidDex(listOf("Lcom/bigapp/Dex2;"))
            val dex3 = buildMinimalValidDex(listOf("Lcom/bigapp/Dex3;"))
            val customDex = buildMinimalValidDex(listOf("Lcom/bigapp/DexCustom;"))
            val splitDex = buildMinimalValidDex(listOf("Lcom/bigapp/SplitDex;"))
            val runtimeDex = buildMinimalValidDex(listOf("Lcom/example/standalone/StandaloneRuntimeEngine;"))

            // Intentionally write DEX entries out-of-order and with a non-standard name
            ZipOutputStream(FileOutputStream(baseApk)).use { zout ->
                fun put(name: String, data: ByteArray) {
                    zout.putNextEntry(ZipEntry(name))
                    zout.write(data)
                    zout.closeEntry()
                }
                put("AndroidManifest.xml", buildMinimalAxml("com.bigapp.test"))
                put("resources.arsc", ByteArray(64))
                put("classes.dex", dex1)
                put("classes_secondary.dex", customDex)
                put("classes3.dex", dex3)
                put("classes2.dex", dex2)
            }

            ZipOutputStream(FileOutputStream(splitApk)).use { zout ->
                zout.putNextEntry(ZipEntry("classes.dex"))
                zout.write(splitDex)
                zout.closeEntry()
            }

            val cfg = """{"originalPackageName":"com.bigapp.test","standalonePackageName":"com.bigapp.test.phantom"}""".toByteArray()
            StandaloneApkBuilderEngine.repackageAndPatchZip(
                sourceApkFile = baseApk,
                splitSourceDirs = arrayOf(splitApk.absolutePath),
                nativeLibraryDir = null,
                originalPkg = "com.bigapp.test",
                standalonePkg = "com.bigapp.test.phantom",
                configJsonBytes = cfg,
                injectedRuntimeDexes = listOf(runtimeDex),
                unsignedOutFile = unsignedOut
            )

            StandaloneApkBuilderEngine.signApkV1AndV2(unsignedOut, signedOut)
            assertTrue(signedOut.exists() && signedOut.length() > 1000L)

            // Verify every single DEX (4 base DEX + 1 split DEX + 1 runtime DEX = 6 DEXes) is present & intact!
            val foundDexMarkers = HashSet<String>()
            ZipFile(signedOut).use { zf ->
                for (i in 1..6) {
                    val name = if (i == 1) "classes.dex" else "classes${i}.dex"
                    val entry = zf.getEntry(name)
                    assertNotNull("Missing expected DEX slot $name in output APK", entry)
                    val entryBytes = zf.getInputStream(entry).use { it.readBytes() }
                    foundDexMarkers.addAll(readDexStrings(entryBytes))
                }
                assertNotNull("Missing META-INF/CERT.RSA", zf.getEntry("META-INF/CERT.RSA"))
                assertNotNull("Missing META-INF/CERT.SF", zf.getEntry("META-INF/CERT.SF"))
                assertNotNull("Missing META-INF/MANIFEST.MF", zf.getEntry("META-INF/MANIFEST.MF"))
            }

            assertTrue(foundDexMarkers.contains("Lcom/bigapp/Dex1;"))
            assertTrue(foundDexMarkers.contains("Lcom/bigapp/Dex2;"))
            assertTrue(foundDexMarkers.contains("Lcom/bigapp/Dex3;"))
            assertTrue(foundDexMarkers.contains("Lcom/bigapp/DexCustom;"))
            assertTrue(foundDexMarkers.contains("Lcom/bigapp/SplitDex;"))
            assertTrue(foundDexMarkers.contains("Lcom/example/standalone/StandaloneRuntimeEngine;"))

            // Verify APK Signature Scheme v2 magic ("APK Sig Block 42") & 4096 alignment
            RandomAccessFile(signedOut, "r").use { raf ->
                val len = raf.length()
                val tail = ByteArray(256.coerceAtMost(len.toInt()))
                raf.seek(len - tail.size)
                raf.readFully(tail)
                val magic = "APK Sig Block 42".toByteArray(StandardCharsets.UTF_8)
                val cdOff = ByteBuffer.wrap(tail, tail.size - 22 + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
                val blockFooter = ByteArray(24)
                raf.seek(cdOff - 24L)
                raf.readFully(blockFooter)
                assertArrayEquals(magic, blockFooter.copyOfRange(8, 24))
                val sizeOfBlock = ByteBuffer.wrap(blockFooter, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                val fullV2Size = sizeOfBlock + 8L
                assertEquals("APK Signing Block v2 must be 4096-byte aligned", 0L, fullV2Size % 4096L)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `repackageAndPatchZip stores all native library entries uncompressed and 16KB aligned`() {
        val tmpDir = java.nio.file.Files.createTempDirectory("phantom_so_align_test").toFile()
        try {
            val baseApk = File(tmpDir, "base.apk")
            val splitAbiApk = File(tmpDir, "split_config.arm64_v8a.apk")
            val natDir = File(tmpDir, "arm64")
            natDir.mkdirs()
            val unsignedOut = File(tmpDir, "unsigned.apk")

            // Create minimal valid ELF header (EM_AARCH64 = 0x00B7) for nativeLibraryDir .so
            val elfNatSo = ByteArray(1024).apply {
                this[0] = 0x7f
                this[1] = 'E'.code.toByte()
                this[2] = 'L'.code.toByte()
                this[3] = 'F'.code.toByte()
                this[18] = 0xB7.toByte()
                this[19] = 0x00
            }
            File(natDir, "libcronet.so").writeBytes(elfNatSo)

            val dex1 = buildMinimalValidDex(listOf("Lcom/google/android/youtube/Main;"))
            val runtimeDex = buildMinimalValidDex(listOf("Lcom/example/standalone/StandaloneRuntimeEngine;"))

            ZipOutputStream(FileOutputStream(baseApk)).use { zout ->
                zout.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zout.write(buildMinimalAxml("com.google.android.youtube"))
                zout.closeEntry()
                zout.putNextEntry(ZipEntry("resources.arsc"))
                zout.write(ByteArray(64))
                zout.closeEntry()
                zout.putNextEntry(ZipEntry("classes.dex"))
                zout.write(dex1)
                zout.closeEntry()
            }

            // Split APK containing a DEFLATED .so that must become STORED + 16KB aligned
            ZipOutputStream(FileOutputStream(splitAbiApk)).use { zout ->
                val e = ZipEntry("lib/arm64-v8a/libelements.so")
                e.method = ZipEntry.DEFLATED
                zout.putNextEntry(e)
                zout.write(elfNatSo)
                zout.closeEntry()
            }

            val cfg = """{"originalPackageName":"com.google.android.youtube","standalonePackageName":"com.google.android.youtube.ghostdevice"}""".toByteArray()
            StandaloneApkBuilderEngine.repackageAndPatchZip(
                sourceApkFile = baseApk,
                splitSourceDirs = arrayOf(splitAbiApk.absolutePath),
                nativeLibraryDir = natDir.absolutePath,
                originalPkg = "com.google.android.youtube",
                standalonePkg = "com.google.android.youtube.ghostdevice",
                configJsonBytes = cfg,
                injectedRuntimeDexes = listOf(runtimeDex),
                unsignedOutFile = unsignedOut
            )

            ZipFile(unsignedOut).use { zf ->
                val cronetEntry = zf.getEntry("lib/arm64-v8a/libcronet.so")
                val elementsEntry = zf.getEntry("lib/arm64-v8a/libelements.so")
                assertNotNull("libcronet.so missing", cronetEntry)
                assertNotNull("libelements.so missing", elementsEntry)
                assertEquals("libcronet.so must be STORED", ZipEntry.STORED, cronetEntry.method)
                assertEquals("libelements.so must be STORED", ZipEntry.STORED, elementsEntry.method)
            }

            // Verify actual local file data offsets via Central Directory (handles DEFLATED data-descriptor entries accurately)
            RandomAccessFile(unsignedOut, "r").use { raf ->
                val bytes = ByteArray(raf.length().toInt())
                raf.readFully(bytes)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                // Locate End of Central Directory (0x06054B50)
                var eocdPos = bytes.size - 22
                while (eocdPos >= 0 && buf.getInt(eocdPos) != 0x06054B50) {
                    eocdPos--
                }
                assertTrue("Missing EOCD", eocdPos >= 0)
                val cdOffset = buf.getInt(eocdPos + 16)
                val cdCount = buf.getShort(eocdPos + 10).toInt() and 0xFFFF
                var cdPos = cdOffset
                var checked = 0
                for (i in 0 until cdCount) {
                    assertEquals("Expected CEN header", 0x02014B50, buf.getInt(cdPos))
                    val compSize = buf.getInt(cdPos + 20)
                    val uncompSize = buf.getInt(cdPos + 24)
                    val nameLen = buf.getShort(cdPos + 28).toInt() and 0xFFFF
                    val cdExtraLen = buf.getShort(cdPos + 30).toInt() and 0xFFFF
                    val cdCommentLen = buf.getShort(cdPos + 32).toInt() and 0xFFFF
                    val localHdrOff = buf.getInt(cdPos + 42)
                    val name = String(bytes, cdPos + 46, nameLen, StandardCharsets.UTF_8)
                    if (name.endsWith(".so")) {
                        assertEquals("Expected LOC header for $name", 0x04034B50, buf.getInt(localHdrOff))
                        val locNameLen = buf.getShort(localHdrOff + 26).toInt() and 0xFFFF
                        val locExtraLen = buf.getShort(localHdrOff + 28).toInt() and 0xFFFF
                        val dataOffset = localHdrOff + 30 + locNameLen + locExtraLen
                        assertEquals("$name must be 16384-byte aligned (dataOffset=$dataOffset)", 0, dataOffset % 16384)
                        assertEquals("$name must be uncompressed", uncompSize, compSize)
                        checked++
                    }
                    cdPos += 46 + nameLen + cdExtraLen + cdCommentLen
                }
                assertEquals(2, checked)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `AxmlManifestPatcher handles resId 0 attributes and keeps application attrs sorted by unsigned resId`() {
        val rawAxml = buildMinimalAxml(
            pkgName = "com.bigapp.test",
            includeCustomZeroResAttrOnApp = true,
            includeSplitAttrs = true
        )
        val patchedAxml = AxmlManifestPatcher.patchManifest(
            axmlBytes = rawAxml,
            originalPkg = "com.bigapp.test",
            standalonePkg = "com.bigapp.test.phantom"
        )

        val buf = ByteBuffer.wrap(patchedAxml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, buf.getShort(0).toInt() and 0xFFFF)
        // Parse StringPool & ResourceMap
        val spSize = buf.getInt(12)
        val rmOff = 8 + spSize
        val rmSize = buf.getInt(rmOff + 4)
        val resCount = (rmSize - 8) / 4
        val resIds = IntArray(resCount) { buf.getInt(rmOff + 8 + it * 4) }

        val spStringsStart = buf.getInt(8 + 20)
        val spCount = buf.getInt(8 + 8)
        val strings = ArrayList<String>(spCount)
        for (i in 0 until spCount) {
            val strOff = buf.getInt(8 + 28 + i * 4)
            var p = 8 + spStringsStart + strOff
            val charLen = ((patchedAxml[p].toInt() and 0xFF) or ((patchedAxml[p + 1].toInt() and 0xFF) shl 8))
            p += 2
            val sb = StringBuilder(charLen)
            for (c in 0 until charLen) {
                val ch = ((patchedAxml[p].toInt() and 0xFF) or ((patchedAxml[p + 1].toInt() and 0xFF) shl 8)).toChar()
                sb.append(ch)
                p += 2
            }
            strings.add(sb.toString())
        }

        // Scan <application> attributes and verify no isSplitRequired, allowBackup + extractNativeLibs present,
        // and resIds on <application> remain in valid order with customAttr (resId=0) AFTER framework resIds!
        var pos = rmOff + rmSize
        var foundApplication = false
        var sawProvider = false
        while (pos + 8 <= patchedAxml.size) {
            val cType = buf.getShort(pos).toInt() and 0xFFFF
            val cSize = buf.getInt(pos + 4)
            if (cType == 0x0102 && cSize >= 36) {
                val elemName = strings[buf.getInt(pos + 20)]
                if (elemName == "application") {
                    foundApplication = true
                    val attrCount = buf.getShort(pos + 28).toInt() and 0xFFFF
                    val attrResIds = ArrayList<Long>()
                    val attrNames = ArrayList<String>()
                    for (a in 0 until attrCount) {
                        val aOff = pos + 36 + a * 20
                        val aNameIdx = buf.getInt(aOff + 4)
                        attrNames.add(strings[aNameIdx])
                        val rid = if (aNameIdx < resIds.size) resIds[aNameIdx] else 0
                        attrResIds.add(if (rid == 0) 0xFFFFFFFFL else (rid.toLong() and 0xFFFFFFFFL))
                    }
                    assertFalse(attrNames.contains("isSplitRequired"))
                    assertTrue(attrNames.contains("allowBackup"))
                    assertTrue(attrNames.contains("extractNativeLibs"))
                    assertTrue(attrNames.contains("customAttr"))
                    for (i in 0 until attrResIds.size - 1) {
                        assertTrue(
                            "Manifest <application> attributes out of resId order: ${attrNames[i]}=${attrResIds[i]} > ${attrNames[i + 1]}=${attrResIds[i + 1]}",
                            attrResIds[i] <= attrResIds[i + 1]
                        )
                    }
                } else if (elemName == "provider") {
                    sawProvider = true
                }
            }
            pos += cSize
        }
        assertTrue(foundApplication)
        assertTrue(sawProvider)
        assertTrue(strings.contains("com.bigapp.test.phantom"))
    }

    private fun buildMinimalAxml(
        pkgName: String,
        includeCustomZeroResAttrOnApp: Boolean = false,
        includeSplitAttrs: Boolean = false
    ): ByteArray {
        val strings = mutableListOf(
            "http://schemas.android.com/apk/res/android", // 0
            "manifest", // 1
            "application", // 2
            "package", // 3
            pkgName // 4
        )
        val resIds = mutableListOf(
            0, // 0
            0, // 1
            0, // 2
            0  // 3 ("package" has no android resId)
        )
        var idxCustomAttr = -1
        var idxCustomVal = -1
        var idxIsSplit = -1
        if (includeCustomZeroResAttrOnApp) {
            idxCustomAttr = strings.size
            strings.add("customAttr")
            resIds.add(0)
            idxCustomVal = strings.size
            strings.add("customValue")
            resIds.add(0)
        }
        if (includeSplitAttrs) {
            // Ensure resIds covers up to idxIsSplit matching strings indices
            while (resIds.size < strings.size) resIds.add(0)
            idxIsSplit = strings.size
            strings.add("isSplitRequired")
            resIds.add(0x01010591)
        }

        // Build UTF-16LE StringPool
        val offsets = IntArray(strings.size)
        val strData = ByteArrayOutputStream()
        for (i in strings.indices) {
            offsets[i] = strData.size()
            val s = strings[i]
            strData.write(s.length and 0xFF)
            strData.write((s.length ushr 8) and 0xFF)
            for (ch in s) {
                strData.write(ch.code and 0xFF)
                strData.write((ch.code ushr 8) and 0xFF)
            }
            strData.write(0)
            strData.write(0)
        }
        while (strData.size() % 4 != 0) strData.write(0)
        val spHeaderSize = 28
        val stringsStart = spHeaderSize + strings.size * 4
        val spSize = stringsStart + strData.size()
        val sp = ByteBuffer.allocate(spSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0001)
            putShort(spHeaderSize.toShort())
            putInt(spSize)
            putInt(strings.size)
            putInt(0)
            putInt(0) // UTF-16
            putInt(stringsStart)
            putInt(0)
            offsets.forEach { putInt(it) }
            put(strData.toByteArray())
        }.array()

        val rmSize = 8 + resIds.size * 4
        val rm = ByteBuffer.allocate(rmSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0180)
            putShort(8)
            putInt(rmSize)
            resIds.forEach { putInt(it) }
        }.array()

        fun startElem(nameIdx: Int, attrs: List<Triple<Int, Int, Pair<Int, Int>>>): ByteArray {
            val size = 36 + attrs.size * 20
            return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x0102)
                putShort(16)
                putInt(size)
                putInt(1)
                putInt(-1)
                putInt(-1)
                putInt(nameIdx)
                putShort(20)
                putShort(20)
                putShort(attrs.size.toShort())
                putShort(0); putShort(0); putShort(0)
                for ((ns, attrName, typeVal) in attrs) {
                    putInt(ns)
                    putInt(attrName)
                    putInt(if (typeVal.first == 0x03) typeVal.second else -1)
                    putShort(0x0008)
                    put(0)
                    put(typeVal.first.toByte())
                    putInt(typeVal.second)
                }
            }.array()
        }

        fun endElem(nameIdx: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0103)
            putShort(16)
            putInt(24)
            putInt(1)
            putInt(-1)
            putInt(-1)
            putInt(nameIdx)
        }.array()

        val appAttrs = mutableListOf<Triple<Int, Int, Pair<Int, Int>>>()
        if (includeCustomZeroResAttrOnApp) {
            appAttrs.add(Triple(0, idxCustomAttr, 0x03 to idxCustomVal))
        }
        if (includeSplitAttrs) {
            appAttrs.add(Triple(0, idxIsSplit, 0x12 to -1))
        }

        val body = ByteArrayOutputStream().apply {
            write(startElem(1, listOf(Triple(-1, 3, 0x03 to 4)))) // <manifest package="pkgName">
            write(startElem(2, appAttrs)) // <application ...>
            write(endElem(2))
            write(endElem(1))
        }.toByteArray()

        val total = 8 + sp.size + rm.size + body.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003)
            putShort(8)
            putInt(total)
            put(sp)
            put(rm)
            put(body)
        }.array()
    }
}
