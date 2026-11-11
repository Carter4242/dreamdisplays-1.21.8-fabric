package com.dreamdisplays.shared.protocol

/** Protocol constants. */
object ProtocolVersion {
    /**
     * Current subversion of protocol. 12: displays carry a media generation (echoed back by clients), a world key, and
     * the server can ask for reports again (`ReportRequest`); clients re-report readiness and preloads on every hello.
     */
    const val CURRENT = 12

    /**
     * What a hello without a version reads as: every client and server before 12 (and 12 builds before the version was
     * put on the wire). Lower than any version a feature check compares against.
     */
    const val UNKNOWN = 0
}
