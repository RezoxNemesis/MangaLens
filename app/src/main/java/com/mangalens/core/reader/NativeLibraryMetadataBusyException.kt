package com.mangalens.core.reader

/** Retry a native metadata operation without waiting under its task publication authority. */
internal class NativeLibraryMetadataBusyException : java.io.IOException("Library metadata is being saved. This request will retry its captured revision.")
