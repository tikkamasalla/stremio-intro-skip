package app.tikkamasalla.vimu.introskip

import app.morphe.patcher.patch.rawResourcePatch

private const val SO_PATH = "lib/armeabi-v7a/libmediaengine.so"
private const val PATCH_OFFSET = 0x135db0
private const val EXPECTED_BYTE_0 = 0x10
private const val EXPECTED_BYTE_1 = 0xD0.toByte()
private const val PATCHED_BYTE_0 = 0x1F

/**
 * Skips a null dereference crash on rapid seeks in the Vimu media engine.
 *
 * Changes 2 bytes of lib/armeabi-v7a/libmediaengine.so at file offset 0x135db0
 * from 10 d0 to 1f d0. The original bytes are checked first: when they differ
 * (another engine build) the file is left alone and the patch still succeeds,
 * so the skip keeps working without the crash fix. Runs with Intro Skip (Vimu).
 */
val vimuNativePatch = rawResourcePatch(
    name = "Intro Skip native fix (Vimu)",
    description = "Fixes a native crash on rapid seeks in the Vimu media engine. Runs with the Intro Skip (Vimu) patch.",
    default = true,
) {
    compatibleWith(Constants.VIMU_COMPATIBILITY)

    execute {
        val file = try {
            get(SO_PATH)
        } catch (error: Exception) {
            println("Vimu native fix: $SO_PATH not in this APK, skipped.")
            return@execute
        }
        val bytes = file.readBytes()
        if (PATCH_OFFSET + 2 > bytes.size) {
            println("Vimu native fix: $SO_PATH too small, skipped.")
            return@execute
        }
        val have0 = bytes[PATCH_OFFSET].toInt() and 0xFF
        val have1 = bytes[PATCH_OFFSET + 1]
        if (have0 != EXPECTED_BYTE_0 || have1 != EXPECTED_BYTE_1) {
            println(
                "Vimu native fix: unexpected bytes at 0x135db0 " +
                    "(found ${have0.toString(16)} ${have1.toUByte().toString(16)}), skipped.",
            )
            return@execute
        }
        bytes[PATCH_OFFSET] = PATCHED_BYTE_0.toByte()
        file.writeBytes(bytes)
        println("Vimu native fix: patched $SO_PATH at 0x135db0 (10 d0 -> 1f d0).")
    }
}
