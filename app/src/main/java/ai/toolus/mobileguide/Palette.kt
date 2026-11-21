package ai.toolus.mobileguide

/**
 * 해봄's colours in one place, as hex strings so a plain JVM test can check every pair (PaletteContrastTest).
 *
 * Rules (WCAG 2.2): body text >= 4.5:1, large text (>= 24 sp, or >= 18.66 sp bold) >= 3:1, shapes people must see
 * (the ring, borders of buttons, radio marks) >= 3:1 against what is next to them.
 *
 * The ring is drawn over *other apps*, so its background is unknown (white settings screens, black video apps, a
 * yellow chat bar, a blue header...). It is three layers, outside in: a thin dark outline, a white halo, the orange
 * core. Whatever the background, at least one layer stands out from it, and the core always stands out from the halo.
 */
object Palette {
    /** Ring core, chevron: vivid orange. 3.9:1 on white, 5.4:1 on black. */
    const val RING = "#E2501A"
    /** The tap ring's line and glow: the lighter brand coral (3.2:1 on white, 6.5:1 on black). */
    const val RING_LINE = "#F2603F"
    const val RING_HALO = "#FFFFFF"
    const val RING_OUTLINE = "#2B1810"

    /** Filled buttons, tiles, the done check, the wordmark: a bright orange. White text on it is 3.36:1, so only
     *  large text (>= 18.66 sp bold) sits on it; smaller orange words use BRAND_TEXT. */
    const val BRAND = "#F25A1D"
    /** Small orange words (16-17 sp) on white or the light orange tints: a red-leaning orange, 4.55:1 on the deepest
     *  tint, 5.0:1 on TONAL, 5.6:1 on white. */
    const val BRAND_TEXT = "#C0360B"

    const val INK = "#2B1810"
    const val INK_SUB = "#5C5C5C"
    const val SURFACE = "#FFFFFF"
    const val PEACH = "#FFE3D3"
    const val TONAL = "#FFF0E8"
    /** Borders that mark a button's edge, and an unticked radio: 3:1 on white. */
    const val EDGE = "#868E96"
    /** Hairlines that only separate content (not a control's edge); no contrast rule applies. */
    const val HAIRLINE = "#E5E8EB"

    // home screen
    const val BG = "#F7F7F7"
    const val T1 = "#191F28"
    const val T2 = "#4E5968"
    const val NEU = "#E9ECEF"
    const val NEU_TX = "#333D4B"
    /** The "화면 안내가 꺼져 있어요" notice: a sunny yellow box. The box is a notice, not a control, so its edge is
     *  decoration (WCAG 1.4.11 asks 3:1 only of controls and graphics needed to understand them); the text inside and
     *  the orange button carry the contrast. */
    const val WARN_BG = "#FFF1C2"
    const val WARN_EDGE = "#FFD15C"

    /** Backgrounds the overlay meets in everyday apps; the ring must stand out on each. */
    val SCREENS = listOf("#FFFFFF", "#F7F7F7", "#000000", "#121212", "#FEE500", "#777777", "#3B82F6", "#1E88E5",
        "#4CAF50", "#FF6B2C", "#E2501A", "#FFE3D3", "#202124")

    fun luminance(hex: String): Double {
        val h = hex.removePrefix("#")
        fun ch(i: Int): Double {
            val c = h.substring(i, i + 2).toInt(16) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(0) + 0.7152 * ch(2) + 0.0722 * ch(4)
    }

    fun contrast(a: String, b: String): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
