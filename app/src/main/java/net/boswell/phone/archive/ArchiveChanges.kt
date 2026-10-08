package net.boswell.phone.archive

import java.util.concurrent.atomic.AtomicLong

/**
 * A count of changes to the files the archive is built from (clip sidecars,
 * clip audio, transcripts), so [Archive.sync] can tell at once that nothing
 * changed and skip looking at every file: thousands of stats on every sync,
 * and processing, the screens and the assistant each sync per clip.
 *
 * Everything that writes or deletes those files calls [bump] after it does:
 * writeAtomically (every sidecar and transcript write goes through it), the
 * clip audio's own writes and deletes (ClipAudio, OggOpus), and the places
 * that delete or move them directly (ClipActions, ProcessingWorker). It only
 * counts within this process -- the app is one process, workers included --
 * so the archive also compares the folders' own modification times.
 */
object ArchiveChanges {
    private val count = AtomicLong()

    /** A clip's sidecar, audio or transcript was just written, moved or deleted. */
    fun bump() { count.incrementAndGet() }

    val generation: Long get() = count.get()
}
