package com.coursemapper.ui.designsystem

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rules for the component library, checked against the source tree:
 *
 * 1. `ui/designsystem/` doesn't import the domain. Domain-aware shared
 *    components go in `ui/components/`.
 * 2. No `parseColor` outside the hex bridge.
 * 3. No literal map strip heights, use [MapStrip].
 * 4. No hand-written back-arrow `TopAppBar`, use [CmTopBar].
 *
 * Exceptions are listed by name.
 */
class DesignSystemBoundaryTest {

    private val uiRoot: File by lazy {
        // Gradle runs unit tests with the module directory as the working dir.
        val fromModule = File("src/main/java/com/coursemapper/ui")
        val root = if (fromModule.isDirectory) fromModule else File("app/${fromModule.path}")
        assertTrue(
            "cannot find the UI source tree from ${File(".").absolutePath}",
            root.isDirectory
        )
        root
    }

    private fun uiSources(): List<File> =
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.relative(): String =
        relativeTo(uiRoot).path.replace(File.separatorChar, '/')

    /** Guards the others, an empty search of a wrong directory would pass them all. */
    @Test
    fun `the source tree is actually being read`() {
        val sources = uiSources()
        assertTrue("expected the whole UI layer, found ${sources.size} files", sources.size >= 40)
        assertTrue(
            "expected both tiers under $uiRoot",
            sources.any { it.relative().startsWith("designsystem/") } &&
                sources.any { it.relative().startsWith("components/") }
        )
    }

    /** Packages that make a file domain-aware, including `ui.format`. */
    private val domainPackages = listOf(
        "com.coursemapper.domain",
        "com.coursemapper.data",
        "com.coursemapper.map",
        "com.coursemapper.offline",
        "com.coursemapper.routing",
        "com.coursemapper.gpx",
        "com.coursemapper.location",
        "com.coursemapper.sharing",
        "com.coursemapper.ui.format",
        "com.coursemapper.ui.components"
    )

    @Test
    fun `the design system does not import the domain`() {
        val offenders = uiSources()
            .filter { it.relative().startsWith("designsystem/") }
            .flatMap { file ->
                file.readLines()
                    .filter { line -> domainPackages.any { line.startsWith("import $it") } }
                    .map { "${file.relative()}: ${it.trim()}" }
            }

        assertTrue(
            "ui/designsystem must stay usable without the domain — move these to " +
                "ui/components, which is the tier that may know about courses:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The reverse is fine and expected, but state it so the direction is on record. */
    @Test
    fun `shared components may depend on the design system`() {
        val componentsUseTokens = uiSources()
            .filter { it.relative().startsWith("components/") }
            .any { file ->
                file.readLines().any { it.startsWith("import com.coursemapper.ui.designsystem") }
            }
        assertTrue(
            "ui/components is expected to build on ui/designsystem; if nothing does, " +
                "the split has stopped meaning anything",
            componentsUseTokens
        )
    }

    @Test
    fun `hex colours are parsed in one place`() {
        val offenders = uiSources()
            .filter { it.relative() != "designsystem/HexColor.kt" }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        line.contains("parseColor") && !line.trimStart().startsWith("*")
                    }
                    .map { (i, line) -> "${file.relative()}:${i + 1}  ${line.trim()}" }
            }

        assertTrue(
            "use String.toComposeColor() — it caches, and these re-parse on every " +
                "recomposition:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** Strip-sized literal heights, 190-320 dp. Starts at 190 so the 160 dp spinner box isn't caught. */
    private val stripHeight = Regex("""\.height\((19\d|2\d\d|3[01]\d|320)\.dp\)""")

    @Test
    fun `embedded map strips read the token`() {
        val offenders = uiSources().flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> stripHeight.containsMatchIn(line) }
                .map { (i, line) -> "${file.relative()}:${i + 1}  ${line.trim()}" }
        }

        assertTrue(
            "a map strip's height decides its fit zoom, which decides the RibbonLod " +
                "chunk band — use MapStrip.height so RibbonPreviewMapZoomTest guards " +
                "the height that ships:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** Home (no back) and the preset editor (closes a sheet) keep their own bar. */
    private val ownTopBarAllowed = setOf(
        "designsystem/CmTopBar.kt",
        "home/HomeScreen.kt",
        "presets/MarkerPresetScreen.kt"
    )

    @Test
    fun `back-arrow app bars come from CmTopBar`() {
        val offenders = uiSources()
            .filter { it.relative() !in ownTopBarAllowed }
            .filter { file -> file.readText().contains("TopAppBar(") }
            .map { it.relative() }

        assertTrue(
            "use CmTopBar — a hand-rolled bar is eight lines and one accessibility " +
                "string to get wrong. If this screen genuinely needs its own bar, add " +
                "it to ownTopBarAllowed with a reason:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
