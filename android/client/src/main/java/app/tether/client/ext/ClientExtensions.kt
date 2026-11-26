package app.tether.client.ext

import app.tether.client.ClientApp
import app.tether.core.ext.EchoExtension
import app.tether.core.ext.FilesExtension
import app.tether.core.ext.TetherExtension

/**
 * The extensions the Android client runs (protocol §7, docs/EXTENDING.md). Add yours here: ClientApp registers this
 * list, announces it in every sync and routes the Android phone's extension messages to it (main thread).
 *
 * `x.tether.files` derives its one-time keys from the live session's transcript hash, which the link hands out
 * per session (there is only ever one session here).
 */
fun clientExtensions(app: ClientApp): List<TetherExtension> = listOf(
    EchoExtension(),
    FilesExtension(keyMaterial = { app.link.transcriptHash() }),
)
