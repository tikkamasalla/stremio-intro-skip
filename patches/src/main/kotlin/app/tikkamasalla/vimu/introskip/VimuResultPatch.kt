package app.tikkamasalla.vimu.introskip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val VIMU_RESULT = "Lnet/gtvbox/videoplayer/VimuResult;"

/**
 * Returns an MX Player style result from Vimu so Stremio saves progress.
 *
 * Ports the two proven smali hooks: w1 normalizes "position" / "extra_position"
 * to "startfrom", and j1 puts position, duration and end_by on the result
 * Intent. Every edit reuses only registers that are free at its site.
 */
val vimuResultPatch = bytecodePatch(
    name = "External player result (Vimu)",
    description = "Returns position and duration from Vimu so Stremio saves progress. Runs with the Intro Skip (Vimu) patch.",
    default = true,
) {
    compatibleWith(Constants.VIMU_COMPATIBILITY)
    extendWith("extensions/extension.mpe")

    execute {
        // Start position: v1 holds the extras Bundle right before "startfrom" is read.
        VimuResultStartFingerprint.method.apply {
            val anchor = findInstruction(
                "start position anchor",
                { it.opcode == Opcode.CONST_STRING && it.referenceStringContains("startfrom") },
            )
            addInstructions(
                anchor,
                "invoke-static {v1}, $VIMU_RESULT->normalize(Landroid/os/Bundle;)V",
            )
        }

        // Finish: p0 is the fragment, p1 the result code, v0 the result Intent.
        VimuResultFinishFingerprint.method.apply {
            val setResult = findInstruction(
                "setResult call",
                {
                    it.opcode == Opcode.INVOKE_VIRTUAL &&
                        (it.referenceString()?.contains("Activity;->setResult(ILandroid/content/Intent;)V") == true)
                },
            )
            addInstructions(
                setResult,
                """
                invoke-static {p0, p1, v0}, $VIMU_RESULT->fill(Ljava/lang/Object;ILandroid/content/Intent;)I
                move-result p1
                """.trimIndent(),
            )
        }
    }
}

/** Index of the first instruction matching the predicate, or throws. */
private fun MutableMethod.findInstruction(
    what: String,
    predicate: (BuilderInstruction) -> Boolean,
): Int {
    val index = implementation!!.instructions.indexOfFirst(predicate)
    if (index < 0) throw PatchException("Vimu result $what not found in $definingClass->$name.")
    return index
}

private fun BuilderInstruction.referenceString(): String? =
    (this as? ReferenceInstruction)?.reference?.toString()

private fun BuilderInstruction.referenceStringContains(value: String): Boolean =
    referenceString()?.contains(value) == true
