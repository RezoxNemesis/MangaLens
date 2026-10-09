package com.mangalens.download

/** A caller's captured request headers describe its exact original URL, not a fresh resolver result. */
internal fun resolvedDownloadHeaders(requestedUrl: String, resolvedUrl: String,
    resolverHeaders: Map<String, String>, capturedHeaders: Map<String, String>): Map<String, String> =
    resolverHeaders + capturedHeaders.takeIf { requestedUrl == resolvedUrl }.orEmpty()
