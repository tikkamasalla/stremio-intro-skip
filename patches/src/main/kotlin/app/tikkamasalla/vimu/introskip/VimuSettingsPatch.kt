package app.tikkamasalla.vimu.introskip

import app.morphe.patcher.patch.resourcePatch

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val PREFS_XML = "res/xml/prefs.xml"
private const val ENABLED_KEY = "introskip_enabled"

/**
 * Adds the Skip intros switch to Vimu Settings.
 *
 * Appends a plain CheckBoxPreference row to the root screen of res/xml/prefs.xml.
 * It is inflated by name, like Vimu's own rows, so the obfuscated preference code
 * is never touched. The row persists itself in the default SharedPreferences file,
 * where the skip reads it. Runs with Intro Skip (Vimu).
 */
val vimuSettingsPatch = resourcePatch(
    name = "Intro Skip settings row (Vimu)",
    description = "Adds the Skip intros switch to Vimu Settings. Runs with the Intro Skip (Vimu) patch.",
    default = true,
) {
    compatibleWith(Constants.VIMU_COMPATIBILITY)

    execute {
        document(PREFS_XML).use { doc ->
            val root = doc.documentElement
            val rows = root.getElementsByTagName("CheckBoxPreference")
            for (i in 0 until rows.length) {
                val key = rows.item(i).attributes?.getNamedItem("android:key")?.nodeValue
                if (key == ENABLED_KEY) {
                    println("Vimu settings row: already present, skipped.")
                    return@execute
                }
            }
            val row = doc.createElement("CheckBoxPreference")
            row.setAttributeNS(ANDROID_NS, "android:key", ENABLED_KEY)
            row.setAttributeNS(ANDROID_NS, "android:title", "Skip intros")
            row.setAttributeNS(
                ANDROID_NS,
                "android:summary",
                "Skip intros and recaps during playback",
            )
            row.setAttributeNS(ANDROID_NS, "android:defaultValue", "true")
            root.appendChild(row)
            println("Vimu settings row: added Skip intros to $PREFS_XML.")
        }
    }
}
