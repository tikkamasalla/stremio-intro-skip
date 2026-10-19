package app.tikkamasalla.stremio.progress

import app.morphe.patcher.Fingerprint

private const val CONTRACT = "Lcom/stremio/common/players/ExternalPlayerContract;"
private const val INPUT = "Lcom/stremio/common/players/ExternalPlayerContract\$ExternalPlayerInput;"
private const val RESULT = "Lcom/stremio/common/players/ExternalPlayerContract\$ExternalPlayerResult;"

/** "Play in external player" inside the player: snapshot media and subtitle state first. */
object OpenInExternalPlayerFingerprint : Fingerprint(
    definingClass = "Lcom/stremio/tv/views/player/PlayerFragment;",
    name = "openInExternalPlayer",
    parameters = listOf("Ljava/lang/Long;"),
)

/** Builds the Intent that starts the external player. */
object CreateIntentFingerprint : Fingerprint(
    definingClass = CONTRACT,
    name = "createIntent",
    parameters = listOf("Landroid/content/Context;", INPUT),
)

/** Turns the external player's result Intent into position, duration and completed. */
object ParseResultFingerprint : Fingerprint(
    definingClass = CONTRACT,
    name = "parseResult",
    returnType = RESULT,
    parameters = listOf("I", "Landroid/content/Intent;"),
)

/** The single call that records progress in Stremio's core (also used by the internal player). */
object PlayerTimeChangedFingerprint : Fingerprint(
    definingClass = "Lcom/stremio/common/api/StremioApi;",
    name = "playerTimeChanged",
    parameters = listOf("J", "J"),
)
