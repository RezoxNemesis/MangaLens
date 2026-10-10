package com.mangalens.core.io

/** Flags passed atomically to Android's Linux open(2), including on API 26. */
internal object AndroidFileOpenFlags {
    // O_CLOEXEC is public in OsConstants only from API 27, but the Linux ABI flag
    // is already supported on API 26. Both shipped ABIs (arm64-v8a and x86_64)
    // use asm-generic/fcntl.h: O_CLOEXEC = 02000000 = 0x80000.
    // Keep it in open(2); setting FD_CLOEXEC after opening introduces a race.
    const val CLOSE_ON_EXEC = 0x80000
}
