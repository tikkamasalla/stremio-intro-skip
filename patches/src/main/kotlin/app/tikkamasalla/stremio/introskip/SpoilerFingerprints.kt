package app.tikkamasalla.stremio.introskip

import app.morphe.patcher.Fingerprint

/** Card row bind that loads Video.getThumbnail for each episode. */
object CardVideoItemBindFingerprint : Fingerprint(
    definingClass = "Lcom/stremio/tv/databinding/CardVideoItemBindingImpl;",
    name = "executeBindings",
    parameters = listOf(),
)

/** Header bind that loads Video.getOverview for the selected episode. */
object MetaDetailsBindFingerprint : Fingerprint(
    definingClass = "Lcom/stremio/tv/databinding/FragmentMetaDetailsBindingImpl;",
    name = "executeBindings",
    parameters = listOf(),
)

/** Episode list resume, used to redraw after returning from Settings. */
object VideosListOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/stremio/tv/views/metadetails/videos/VideosListFragment;",
    name = "onResume",
    parameters = listOf(),
)

/** Episode list state observer that calls setItems after each load. */
object VideosListOnCreateObserverFingerprint : Fingerprint(
    definingClass = "Lcom/stremio/tv/views/metadetails/videos/VideosListFragment\$onCreate\$3;",
    name = "invokeSuspend",
    parameters = listOf("Ljava/lang/Object;"),
)
