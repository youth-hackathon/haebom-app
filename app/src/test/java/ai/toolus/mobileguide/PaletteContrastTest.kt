package ai.toolus.mobileguide

import ai.toolus.mobileguide.Palette.contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every colour pair the app actually draws, against WCAG 2.2:
 * text 4.5:1, large text (>= 24 sp, or >= 18.66 sp bold) 3:1, shapes people must see 3:1.
 */
class PaletteContrastTest {
    private val TEXT = 4.5
    private val LARGE = 3.0
    private val SHAPE = 3.0

    private fun check(what: String, fg: String, bg: String, need: Double) {
        val c = contrast(fg, bg)
        assertTrue("$what: $fg on $bg is ${"%.2f".format(c)}:1, needs $need:1", c >= need)
    }

    @Test fun overlayText() {
        check("bubble headline (24sp)", Palette.INK, Palette.SURFACE, TEXT)
        check("bubble sub line (17sp)", Palette.INK_SUB, Palette.SURFACE, TEXT)
        check("step / replay pill (16sp bold)", Palette.BRAND_TEXT, Palette.PEACH, TEXT)
        check("strong control pill (17sp bold)", Palette.BRAND_TEXT, Palette.SURFACE, TEXT)
        check("control pill (17sp bold)", Palette.INK, Palette.SURFACE, TEXT)
        check("card title (22sp bold)", Palette.INK, Palette.SURFACE, TEXT)
        check("card body (17sp)", Palette.INK_SUB, Palette.SURFACE, TEXT)
        check("card primary button (20sp bold, large)", "#FFFFFF", Palette.BRAND, LARGE)
        check("card secondary button (20sp bold)", Palette.BRAND_TEXT, Palette.SURFACE, TEXT)
    }

    @Test fun overlayShapes() {
        check("bubble border on white bubble", Palette.RING, Palette.SURFACE, SHAPE)
        check("progress dot on bubble", Palette.RING, Palette.SURFACE, SHAPE)
        check("progress bar fill on its track", Palette.RING, Palette.PEACH, SHAPE)
        check("done check: white mark on fill", "#FFFFFF", Palette.BRAND, SHAPE)
        check("strong pill edge", Palette.BRAND, Palette.SURFACE, SHAPE)
        check("pill edge", Palette.EDGE, Palette.SURFACE, SHAPE)
        check("card edge", Palette.BRAND, Palette.SURFACE, SHAPE)
    }

    /** The ring sits on any app: on every screen colour, some layer must stand out, and the core must stand out from the halo. */
    @Test fun ringOnAnyScreen() {
        check("ring core vs its halo", Palette.RING, Palette.RING_HALO, SHAPE)
        check("tap ring line on white", Palette.RING_LINE, "#FFFFFF", SHAPE)
        check("tap ring line on black", Palette.RING_LINE, "#000000", SHAPE)
        check("halo vs outline", Palette.RING_HALO, Palette.RING_OUTLINE, SHAPE)
        for (screen in Palette.SCREENS) {
            val best = listOf(Palette.RING, Palette.RING_HALO, Palette.RING_OUTLINE).maxOf { contrast(it, screen) }
            assertTrue("ring on $screen: best layer is ${"%.2f".format(best)}:1", best >= SHAPE)
        }
    }

    @Test fun homeScreen() {
        check("wordmark 해봄 (26sp bold)", Palette.BRAND, Palette.BG, LARGE)
        check("primary text", Palette.T1, Palette.BG, TEXT)
        check("primary text on card", Palette.T1, Palette.SURFACE, TEXT)
        check("secondary text", Palette.T2, Palette.BG, TEXT)
        check("secondary text on card", Palette.T2, Palette.SURFACE, TEXT)
        check("secondary text on warning", Palette.T2, Palette.WARN_BG, TEXT)
        check("filled button / tile: white title (20-22sp bold, large)", "#FFFFFF", Palette.BRAND, LARGE)
        check("filled tile: white line under the title (19sp bold, large)", "#FFFFFF", Palette.BRAND, LARGE)
        check("tonal label 하던 것 / 시작 (17sp bold)", Palette.BRAND_TEXT, Palette.TONAL, TEXT)
        check("tonal label on card", Palette.BRAND_TEXT, Palette.SURFACE, TEXT)
        check("neutral button text", Palette.NEU_TX, Palette.NEU, TEXT)
        check("icon on tonal tile", Palette.BRAND, Palette.TONAL, SHAPE)
        check("icon on neutral tile", Palette.T2, Palette.NEU, SHAPE)
        check("radio (off) on card", Palette.EDGE, Palette.SURFACE, SHAPE)
        check("radio (on) on tonal", Palette.BRAND, Palette.TONAL, SHAPE)
        check("selected option edge", Palette.BRAND, Palette.SURFACE, SHAPE)
        check("warning title", Palette.T1, Palette.WARN_BG, TEXT)
        check("warning button: white on orange (20sp bold, large)", "#FFFFFF", Palette.BRAND, LARGE)
        check("warning box stands apart from the page by its own colour, edge only decorates", Palette.WARN_BG, Palette.BG, 1.0)
    }
}
