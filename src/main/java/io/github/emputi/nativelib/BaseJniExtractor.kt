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

// This code is derived from Richard van der Hoff's mx-native-loader project:
// http://opensource.mxtelecom.com/maven/repo/com/wapmx/native/mx-native-loader/1.7/
// See NOTICE.txt for details.

// Copyright 2006 MX Telecom Ltd

package io.github.emputi.nativelib

import java.io.*
import java.net.URL
import java.util.logging.Logger

/**
 * @author Richard van der Hoff (richardv@mxtelecom.com)
 * @author Ruskonert (Ruskonert@gmail.com)
 */
abstract class BaseJniExtractor : JniExtractor {

    private var libraryJarClass: Class<*>? = null

    /**
     * We use a resource path of the form META-INF/lib/${mx.sysinfo}/ This way
     * native builds for multiple architectures can be packaged together without
     * interfering with each other And by setting mx.sysinfo the jvm can pick the
     * native libraries appropriate for itself.
     */
    private var nativeResourcePaths: Array<String>? = null

    /**
     * this is where native dependencies are extracted to (e.g. tmplib/).
     * @return native working dir
     */
    abstract fun getNativeDir() : File

    /**
     * this is where JNI libraries are extracted to (e.g.
     * tmplib/classloaderName.1234567890000.0/).
     *
     * @return jni working dir
     */
    abstract fun getJniDir() : File

    private val leftoverMinAge: Long
        get() {
            return try {
                java.lang.Long.parseLong(System.getProperty(LEFTOVER_MIN_AGE, LEFTOVER_MIN_AGE_DEFAULT.toString()))
            } catch (e: NumberFormatException) {
                Logger.getLogger("Cannot load leftover minimal age system property")
                LEFTOVER_MIN_AGE_DEFAULT
            }
        }

    @Throws(IOException::class)
    constructor() {
        init(null)
    }

    @Throws(IOException::class)
    constructor(libraryJarClass: Class<*>?) {
        init(libraryJarClass)
    }

    private fun init(libraryJarClass: Class<*>?) {
        this.libraryJarClass = libraryJarClass

        val mxSysInfo = MxSysInfo.mxSysInfo

        nativeResourcePaths = if (mxSysInfo != null) {
            arrayOf("natives/", "META-INF/lib/$mxSysInfo/", "META-INF/lib/")
        } else {
            arrayOf("natives/", "META-INF/lib/")
        }
        // clean up leftover libraries from previous runs
        deleteLeftoverFiles()
    }

    @Throws(IOException::class)
    override fun extractJni(libPath: String, libname: String): File? {
        var mappedlibName = System.mapLibraryName(libname)
        Logger.getLogger("mappedLib is $mappedlibName")
        /*
		 * On Darwin, the default mapping is to .jnilib; but we use .dylibs so that library interdependencies are
		 * handled correctly. if we don't find a .jnilib, try .dylib instead.
		 */
        var lib: URL?

        // if no class specified look for resources in the jar of this class
        if (null == libraryJarClass) {
            libraryJarClass = this.javaClass
        }

        // foolproof
        val combinedPath = (if (libPath == "" || libPath.endsWith(NativeLibraryUtil.DELIM))
            libPath
        else
            libPath + NativeLibraryUtil.DELIM) + mappedlibName
        lib = libraryJarClass!!.classLoader.getResource(combinedPath)
        if (null == lib) {
            /*
			 * On OS X, the default mapping changed from .jnilib to .dylib as of JDK 7, so
			 * we need to be prepared for the actual library and mapLibraryName disagreeing
			 * in either direction.
			 */
            val altLibName: String?
            if (mappedlibName.endsWith(".jnilib")) {
                altLibName = mappedlibName.substring(0, mappedlibName.length - 7) + ".dylib"
            } else if (mappedlibName.endsWith(".dylib")) {
                altLibName = mappedlibName.substring(0, mappedlibName.length - 6) + ".jnilib"
            } else {
                altLibName = null
            }
            if (altLibName != null) {
                lib = javaClass.classLoader.getResource(libPath + altLibName)
                if (lib != null) {
                    mappedlibName = altLibName
                }
            }
        }

        if (null != lib) {
            Logger.getLogger("URL is $lib")
            Logger.getLogger("URL path is " + lib.path)
            return extractResource(lib, mappedlibName)
        }
        Logger.getLogger("Couldn't find resource $combinedPath")
        return null
    }

    @Throws(IOException::class)
    override fun extractRegistered() {
        Logger.getLogger("Extracting libraries registered in classloader " + this.javaClass.classLoader)
        for (nativeResourcePath in nativeResourcePaths!!) {
            val resources = this.javaClass.classLoader.getResources(
                    nativeResourcePath + "AUTOEXTRACT.LIST")
            while (resources.hasMoreElements()) {
                val res = resources.nextElement()
                extractLibrariesFromResource(res)
            }
        }
    }

    @Throws(IOException::class)
    private fun extractLibrariesFromResource(resource: URL) {
        Logger.getLogger("Extracting libraries listed in $resource")
        var reader: BufferedReader? = null
        try {
            reader = BufferedReader(
                    InputStreamReader(resource.openStream(), "UTF-8"))
            var line: String
            while (true) {
                line = reader.readLine()
                if(line == null) break
                var lib: URL? = null
                for (nativeResourcePath in nativeResourcePaths!!) {
                    lib = this.javaClass.classLoader.getResource(
                            nativeResourcePath + line)
                    if (lib != null) break
                }
                lib?.let { extractResource(it, line) }
                        ?: throw IOException("Couldn't find native library " + line +
                                "on the classpath")
            }
        } finally {
            reader?.close()
        }
    }

    /**
     * Extract a resource to the tmp dir (this entry point is used for unit
     * testing)
     *
     * @param dir the directory to extract the resource to
     * @param resource the resource on the classpath
     * @param outputName the filename to copy to (within the tmp dir)
     * @return the extracted file
     * @throws IOException
     */
    @Throws(IOException::class)
    internal fun extractResource(resource: URL, outputName: String): File {
        var inputStream: InputStream? = null
        try {
            inputStream = resource.openStream()
            // TODO there's also a getResourceAsStream

            // make a lib file with exactly the same lib name
            val outfile = File(this.getJniDir(), outputName)
            Logger.getLogger("Extracting '" + resource + "' to '" +
                    outfile.absolutePath + "'")

            // copy resource stream to temporary file
            var out: FileOutputStream? = null
            try {
                out = FileOutputStream(outfile)
                copy(inputStream!!, out)
            } finally {
                out?.close()
            }

            // note that this doesn't always work:
            outfile.deleteOnExit()

            return outfile
        } finally {
            inputStream?.close()
        }
    }

    /**
     * Looks in the temporary directory for leftover versions of temporary shared
     * libraries.
     *
     *
     * If a temporary shared library is in use by another instance it won't
     * delete.
     *
     *
     * An old library will be deleted only if its last modified date is at least
     * LEFTOVER_MIN_AGE milliseconds old (default to 5 minutes)
     * This was introduced to avoid a possible race condition when two instances (JVMs) run the same unpacking code
     * and one of which manage to delete the extracted file of the other before the other gets a chance to load it
     *
     *
     * Another issue is that createTempFile only guarantees to use the first three
     * characters of the prefix, so I could delete a similarly-named temporary
     * shared library if I haven't loaded it yet.
     */
    private fun deleteLeftoverFiles() {
        val tmpDirectory = File(System.getProperty(JAVA_TMPDIR, ALTR_TMPDIR))
        val folders = tmpDirectory.listFiles { _, name -> name.startsWith(TMP_PREFIX) } ?: return
        val leftoverMinAge = leftoverMinAge
        for (folder in folders) {
            // attempt to delete
            val age = System.currentTimeMillis() - folder.lastModified()
            if (age < leftoverMinAge) {
                Logger.getLogger("Not deleting leftover folder " + folder + ": is " + age + "ms old")
                continue
            }
            Logger.getLogger("Deleting leftover folder: $folder")
            deleteRecursively(folder)
        }
    }

    companion object {
        protected const val JAVA_TMPDIR = "java.io.tmpdir"
        protected val ALTR_TMPDIR = "." + NativeLibraryUtil.DELIM + "tmplib"
        protected const val TMP_PREFIX = "nativelib-loader_"
        private const val LEFTOVER_MIN_AGE = "org.scijava.nativelib.leftoverMinAgeMs"
        private const val LEFTOVER_MIN_AGE_DEFAULT = (5 * 60 * 1000).toLong() // 5 minutes

        // creates a temporary directory for hosting extracted files
        // If system tempdir is not available, use tmplib
        @Throws(IOException::class)
        fun getTempDir() : File
        {
            val tmpDir = File(System.getProperty(JAVA_TMPDIR, ALTR_TMPDIR))
            if (!tmpDir.isDirectory) {
                tmpDir.mkdirs()
                if (!tmpDir.isDirectory) throw IOException("Unable to create temporary directory $tmpDir")
            }
            val tempFile = File.createTempFile(TMP_PREFIX, "")
            tempFile.delete()
            return tempFile
        }

        private fun deleteRecursively(directory: File?): Boolean {
            if (directory == null) return true
            val list = directory.listFiles() ?: return true
            for (file in list) {
                if (file.isFile) {
                    if (!file.delete()) return false
                } else if (file.isDirectory) {
                    if (!deleteRecursively(file)) return false
                }
            }
            return directory.delete()
        }

        /**
         * copy an InputStream to an OutputStream.
         *
         * @param in InputStream to copy from
         * @param out OutputStream to copy to
         * @throws IOException if there's an error
         */
        @Throws(IOException::class)
        internal fun copy(`in`: InputStream, out: OutputStream) {
            val tmp = ByteArray(8192)
            var len: Int
            while (true) {
                len = `in`.read(tmp)
                if (len <= 0) {
                    break
                }
                out.write(tmp, 0, len)
            }
        }
    }
}
