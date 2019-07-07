package io.github.emputi.nativelib

import java.io.IOException
import java.util.*
import java.util.logging.Logger

/**
 * This class is a utility for loading native libraries.
 *
 *
 * Native libraries should be packaged into a single jar file, with the
 * following directory and file structure:
 *
 * <pre>
 * natives
 * linux_32
 * libxxx[-vvv].so
 * linux_64
 * libxxx[-vvv].so
 * linux_arm
 * libxxx[-vvv].so
 * linux_arm64
 * libxxx[-vvv].so
 * osx_32
 * libxxx[-vvv].dylib
 * osx_64
 * libxxx[-vvv].dylib
 * windows_32
 * xxx[-vvv].dll
 * windows_64
 * xxx[-vvv].dll
 * aix_32
 * libxxx[-vvv].so
 * libxxx[-vvv].a
 * aix_64
 * libxxx[-vvv].so
 * libxxx[-vvv].a
</pre> *
 *
 *
 * Here "xxx" is the name of the native library and "-vvv" is an optional
 * version number.
 *
 *
 * Current approach is to unpack the native library into a temporary file and
 * load from there.
 *
 * @author Aivar Grislis
 */
object NativeLibraryUtil {

    val DELIM = "/"
    val DEFAULT_SEARCH_PATH = "natives$DELIM"

    private var architecture = Architecture.UNKNOWN
    private var archStr: String? = null

    /**
     * Determines what processor is in use.
     *
     * @return The processor in use.
     */
    // Note that this is actually the architecture of the installed JVM.
    private fun getProcessor(): Processor {
        var processor = Processor.UNKNOWN
        var bits: Int
        val arch = System.getProperty("os.arch").toLowerCase()
        if (arch.contains("arm")) {
            processor = Processor.ARM
        }
        else if (arch.contains("aarch64")) {
            processor = Processor.AARCH_64
        }
        else if (arch.contains("ppc")) {
            bits = 32
            if (arch.contains("64")) {
                bits = 64
            }
            processor = if (32 == bits) Processor.PPC else Processor.PPC_64
        }
        else if (arch.contains("86") || arch.contains("amd")) {
            bits = 32
            if (arch.contains("64")) {
                bits = 64
            }
            processor = if (32 == bits) Processor.INTEL_32 else Processor.INTEL_64
        }
        Logger.getLogger("processor is " + processor + " os.arch is " + System.getProperty("os.arch").toLowerCase())
        return processor
    }

    enum class Architecture {
        UNKNOWN, LINUX_32, LINUX_64, LINUX_ARM, LINUX_ARM64, WINDOWS_32, WINDOWS_64, OSX_32,
        OSX_64, OSX_PPC, AIX_32, AIX_64
    }

    private enum class Processor {
        UNKNOWN, INTEL_32, INTEL_64, PPC, PPC_64, ARM, AARCH_64
    }

    /**
     * Determines the underlying hardware platform and architecture.
     *
     * @return enumerated architecture value
     */
    @JvmStatic
    fun getArchitecture(): Architecture {
        if (Architecture.UNKNOWN == architecture) {
            val processor = getProcessor()
            if (Processor.UNKNOWN != processor) {
                val name = System.getProperty("os.name").toLowerCase()
                if (name.contains("nix") || name.contains("nux")) {
                    when (processor) {
                        Processor.INTEL_32 -> architecture = Architecture.LINUX_32
                        Processor.INTEL_64 -> architecture = Architecture.LINUX_64
                        Processor.ARM -> architecture = Architecture.LINUX_ARM
                        Processor.AARCH_64 -> architecture = Architecture.LINUX_ARM64
                        else -> Architecture.UNKNOWN
                    }
                } else if (name.contains("aix")) {
                    if (Processor.PPC == processor) {
                        architecture = Architecture.AIX_32
                    } else if (Processor.PPC_64 == processor) {
                        architecture = Architecture.AIX_64
                    }
                } else if (name.contains("win")) {
                    if (Processor.INTEL_32 == processor) {
                        architecture = Architecture.WINDOWS_32
                    } else if (Processor.INTEL_64 == processor) {
                        architecture = Architecture.WINDOWS_64
                    }
                } else if (name.contains("mac")) {
                    when (processor) {
                        Processor.INTEL_32 -> architecture = Architecture.OSX_32
                        Processor.INTEL_64 -> architecture = Architecture.OSX_64
                        Processor.PPC -> architecture = Architecture.OSX_PPC
                        else -> Architecture.UNKNOWN
                    }
                }
            }
        }
        Logger.getLogger("architecture is " + architecture + " os.name is " + System.getProperty("os.name").toLowerCase())
        return architecture
    }

    /**
     * Returns the path to the native library.
     *
     * @param searchPath the path to search for &lt;platform&gt; directory.
     * Pass in `null` to get default path
     * (natives/&lt;platform&gt;).
     *
     * @return path
     */
    @JvmStatic
    fun getPlatformLibraryPath(searchPath: String): String {
        if (archStr == null)
            archStr = NativeLibraryUtil.getArchitecture().name.toLowerCase()

        // foolproof
        val fullSearchPath = (if (searchPath == "" || searchPath.endsWith(DELIM))
            searchPath
        else
            searchPath + DELIM) + archStr + DELIM
        Logger.getLogger("platform specific path is $fullSearchPath")
        return fullSearchPath
    }

    /**
     * Returns the full file name (without path) of the native library.
     *
     * @param libName name of library
     * @return file name
     */
    @JvmStatic
    fun getPlatformLibraryName(libName: String): String? {
        var name: String? = null
        when (getArchitecture()) {
            NativeLibraryUtil.Architecture.AIX_32, NativeLibraryUtil.Architecture.AIX_64, NativeLibraryUtil.Architecture.LINUX_32, NativeLibraryUtil.Architecture.LINUX_64, NativeLibraryUtil.Architecture.LINUX_ARM, NativeLibraryUtil.Architecture.LINUX_ARM64 -> name = "lib$libName.so"
            NativeLibraryUtil.Architecture.WINDOWS_32, NativeLibraryUtil.Architecture.WINDOWS_64 -> name = "$libName.dll"
            NativeLibraryUtil.Architecture.OSX_32, NativeLibraryUtil.Architecture.OSX_64 -> name = "lib$libName.dylib"
            else -> {
            }
        }
        Logger.getLogger("native library name " + name!!)
        return name
    }

    /**
     * Returns the Maven-versioned file name of the native library. In order for
     * this to work Maven needs to save its version number in the jar manifest.
     * The version of the library-containing jar and the version encoded in the
     * native library names should agree.
     *
     * <pre>
     * `<build>
     * <plugins>
     * <plugin>
     * <artifactId>maven-jar-plugin</artifactId>
     * <inherited>true</inherited> *
     * <configuration>
     * <archive>
     * <manifest>
     * <packageName>com.example.package</packageName>
     * <addDefaultImplementationEntries>true</addDefaultImplementationEntries> *
     * </manifest>
     * </archive>
     * </configuration>
     * </plugin>
     * </plugins>
     * </build>
     *
     * * = necessary to save version information in manifest
    ` *
    </pre> *
     *
     * @param libraryJarClass any class within the library-containing jar
     * @param libName name of library
     * @return The Maven-versioned file name of the native library.
     */
    @JvmStatic
    fun getVersionedLibraryName(libraryJarClass: Class<*>,
                                libName: String): String {
        var name = libName
        val version = libraryJarClass.getPackage().implementationVersion
        if (null != version && version.isNotEmpty()) { name += "-$version" }
        return name
    }

    /**
     * Loads the native library.
     *
     * @param jniExtractor the extractor to use
     * @param libName name of library
     * @param searchPaths a list of additional paths to search for the library
     * @return whether or not successful
     */
    @JvmStatic
    fun loadNativeLibrary(jniExtractor: JniExtractor, libName: String, vararg searchPaths: String): Boolean
    {
        if (Architecture.UNKNOWN == getArchitecture()) {
            Logger.getLogger("No native library available for this platform.")
        }
        else
        {
            try
            {
                val libPaths = LinkedList(Arrays.asList(*searchPaths))
                libPaths.add(0, DEFAULT_SEARCH_PATH)

                // for backward compatibility
                libPaths.add(1, "")
                libPaths.add(2, "META-INF" + DELIM + "lib")

                // NB: Although the documented behavior of this method is to load
                // native library from META-INF/lib/, what it actually does is
                // to load from the root dir. See: https://github.com/scijava/
                // native-lib-loader/blob/6c303443cf81bf913b1732d42c74544f61aef5d1/
                // src/main/java/org/scijava/nativelib/NativeLoader.java#L126
                // search in each path in {natives/, /, META-INF/lib/, ...}
                for (libPath in libPaths) {
                    val extracted = jniExtractor.extractJni(getPlatformLibraryPath(libPath), libName)
                            ?: throw IOException("Problem with extracted is null")
                    System.load(extracted.absolutePath)
                    return true
                }
            } catch (e: UnsatisfiedLinkError) {
                Logger.getLogger("Problem with library")
            } catch (e: IOException) {
                Logger.getLogger("Problem with extracting the library")
            }

        }
        return false
    }
}
