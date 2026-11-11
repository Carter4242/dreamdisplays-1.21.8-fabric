package com.dreamdisplays.shared.protocol

/** Protocol constants. */
object ProtocolVersion {
    /**
     * Current subversion of protocol. 12: displays carry a media generation (echoed back by clients), a world key, and
     * the server can ask for reports again (`ReportRequest`); clients re-report readiness and preloads on every hello.
     */
    const val CURRENT = 12
}
