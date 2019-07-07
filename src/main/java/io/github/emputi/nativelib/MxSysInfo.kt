/*
 * #%L
 * Native library loader for extracting and loading native libraries from Java.
 * %%
 * Copyright (C) 2010 - 2015 Board of Regents of the University of
 * Wisconsin-Madison and Glencoe Software, Inc.
 *
 * Copyright (c) 2019, Ruskonert (Emputi Open-source project) All rights reserved.
 *
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package io.github.emputi.nativelib

import java.io.File
import java.io.IOException
import java.util.regex.Pattern

object MxSysInfo {

    /**
     * Find the mx.sysinfo string for the current jvm
     * Can be overridden by specifying a mx.sysinfo system property
     * @return the specified mx.sysinfo or a guessed one
     */
    val mxSysInfo: String?
        get() {
            val mxSysInfo = System.getProperty("mx.sysinfo")
            return mxSysInfo ?: guessMxSysInfo()
        }

    /**
     * Make a spirited attempt at guessing what the mx.sysinfo for the current jvm
     * might be.
     *
     * @return the guessed mx.sysinfo
     */
    fun guessMxSysInfo(): String {
        val arch = System.getProperty("os.arch")
        val os = System.getProperty("os.name")
        var extra = "unknown"
        if ("Linux" == os) {
            try {
                val libc_dest = File("/lib/libc.so.6").canonicalPath
                val libc_m = Pattern.compile(".*/libc-(\\d+)\\.(\\d+)\\..*").matcher(libc_dest)
                if (!libc_m.matches())
                    throw IOException(
                            "libc symlink contains unexpected destination: $libc_dest")

                var libstdcxx_file = File("/usr/lib/libstdc++.so.6")
                if (!libstdcxx_file.exists())
                    libstdcxx_file = File("/usr/lib/libstdc++.so.5")

                val libstdcxx_dest = libstdcxx_file.canonicalPath
                val libstdcxx_m = Pattern.compile(".*/libstdc\\+\\+\\.so\\.(\\d+)\\.0\\.(\\d+)")
                        .matcher(libstdcxx_dest)
                if (!libstdcxx_m.matches())
                    throw IOException(
                            "libstdc++ symlink contains unexpected destination: $libstdcxx_dest")
                val cxxver: String
                cxxver = if ("5" == libstdcxx_m.group(1)) {
                    "5"
                } else if ("6" == libstdcxx_m.group(1)) {
                    val minorVersion = Integer.parseInt(libstdcxx_m.group(2))
                    if (minorVersion < 9) {
                        "6"
                    } else {
                        "6" + libstdcxx_m.group(2)
                    }
                } else {
                    libstdcxx_m.group(1) + libstdcxx_m.group(2)
                }

                extra = "c" + libc_m.group(1) + libc_m.group(2) + "cxx" + cxxver
            } catch (e: IOException) {
                extra = "unknown"
            }

        }

        return "$arch-$os-$extra"
    }
}
