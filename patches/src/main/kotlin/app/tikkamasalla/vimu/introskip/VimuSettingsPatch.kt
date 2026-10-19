package app.tikkamasalla.vimu.introskip

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val PREFS_XML = "res/xml/prefs.xml"
private const val ARRAYS_XML = "res/values/arrays.xml"
private const val STRINGS_XML = "res/values/strings.xml"

private const val ENABLED_KEY = "introskip_enabled"
private const val ANIME_KEY = "introskip_anime"
private const val MODE_KEY = "introskip_mode"
private const val RECAPS_KEY = "introskip_recaps"

private const val MODE_ENTRIES_ARRAY = "introskip_mode_entries"
private const val MODE_VALUES_ARRAY = "introskip_mode_values"

/**
 * Adds the four Intro Skip preference rows to Vimu Settings:
 * - Skip intros (CheckBoxPreference, default true)
 * - Skip anime openings (AniSkip) (CheckBoxPreference, default true)
 * - Skip mode (ListPreference: Auto / Manual, default Auto)
 * - Skip recaps (CheckBoxPreference, default true)
 *
 * Appends standard Preference rows to res/xml/prefs.xml and the mode arrays to
 * res/values/arrays.xml (falling back to res/values/strings.xml). All rows persist
 * in the default SharedPreferences file read by VimuIntroSkipPrefs.
 */
val vimuSettingsPatch = resourcePatch(
    name = "Intro Skip settings row (Vimu)",
    description = "Adds the Intro Skip settings to Vimu Settings. Runs with the Intro Skip (Vimu) patch.",
    default = true,
) {
    compatibleWith(Constants.VIMU_COMPATIBILITY)

    execute {
        val valuesDoc = try {
            document(ARRAYS_XML)
        } catch (_: Throwable) {
            document(STRINGS_XML)
        }
        valuesDoc.use { doc ->
            ensureStringArray(doc, MODE_ENTRIES_ARRAY, listOf("Auto", "Manual"))
            ensureStringArray(doc, MODE_VALUES_ARRAY, listOf("auto", "manual"))
        }

        document(PREFS_XML).use { doc ->
            val root = doc.documentElement

            if (!hasPreferenceKey(doc, ENABLED_KEY)) {
                root.appendChild(
                    createCheckBox(
                        doc = doc,
                        key = ENABLED_KEY,
                        title = "Skip intros",
                        summary = "Skip intros and recaps during playback",
                        defaultValue = "true",
                    ),
                )
            }

            if (!hasPreferenceKey(doc, ANIME_KEY)) {
                root.appendChild(
                    createCheckBox(
                        doc = doc,
                        key = ANIME_KEY,
                        title = "Skip anime openings (AniSkip)",
                        summary = "Use AniSkip to find anime openings and recaps",
                        defaultValue = "true",
                    ),
                )
            }

            if (!hasPreferenceKey(doc, MODE_KEY)) {
                val modeRow = doc.createElement("ListPreference")
                modeRow.setAttributeNS(ANDROID_NS, "android:key", MODE_KEY)
                modeRow.setAttributeNS(ANDROID_NS, "android:title", "Skip mode")
                modeRow.setAttributeNS(
                    ANDROID_NS,
                    "android:summary",
                    "Auto skips immediately; Manual shows a Skip button",
                )
                modeRow.setAttributeNS(ANDROID_NS, "android:entries", "@array/$MODE_ENTRIES_ARRAY")
                modeRow.setAttributeNS(ANDROID_NS, "android:entryValues", "@array/$MODE_VALUES_ARRAY")
                modeRow.setAttributeNS(ANDROID_NS, "android:defaultValue", "auto")
                root.appendChild(modeRow)
            }

            if (!hasPreferenceKey(doc, RECAPS_KEY)) {
                root.appendChild(
                    createCheckBox(
                        doc = doc,
                        key = RECAPS_KEY,
                        title = "Skip recaps",
                        summary = "Also skip episode recaps",
                        defaultValue = "true",
                    ),
                )
            }

            println("Vimu settings row: ensured Intro Skip preferences in $PREFS_XML.")
        }
    }
}

private fun hasPreferenceKey(doc: Document, targetKey: String): Boolean {
    val nodes = doc.getElementsByTagName("*")
    for (i in 0 until nodes.length) {
        val attrs = nodes.item(i).attributes ?: continue
        val key = attrs.getNamedItem("android:key")?.nodeValue
            ?: attrs.getNamedItemNS(ANDROID_NS, "key")?.nodeValue
        if (key == targetKey) return true
    }
    return false
}

private fun createCheckBox(
    doc: Document,
    key: String,
    title: String,
    summary: String,
    defaultValue: String,
): Element {
    val row = doc.createElement("CheckBoxPreference")
    row.setAttributeNS(ANDROID_NS, "android:key", key)
    row.setAttributeNS(ANDROID_NS, "android:title", title)
    row.setAttributeNS(ANDROID_NS, "android:summary", summary)
    row.setAttributeNS(ANDROID_NS, "android:defaultValue", defaultValue)
    return row
}

private fun ensureStringArray(doc: Document, name: String, items: List<String>) {
    val root = doc.documentElement
    val existing = root.getElementsByTagName("string-array")
    for (i in 0 until existing.length) {
        val attr = existing.item(i).attributes?.getNamedItem("name")?.nodeValue
        if (attr == name) return
    }
    val array = doc.createElement("string-array")
    array.setAttribute("name", name)
    for (value in items) {
        val item = doc.createElement("item")
        item.textContent = value
        array.appendChild(item)
    }
    root.appendChild(array)
}
