package io.github.fgozxy.await.sync

import java.security.MessageDigest

/** Repeated syncs keep their revision; changed payloads must advance beyond the server's copy. */
object SyncRevision {
    fun fingerprint(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun resolve(revision: Long, syncedRevision: Long, previousFingerprint: String?, fingerprint: String,
                previousRevision: Long = revision): Long =
        if (previousFingerprint == fingerprint) revision.coerceAtLeast(1)
        else maxOf(revision, syncedRevision + 1, previousRevision + 1, 1)
}
