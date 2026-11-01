package expo.modules.mpvplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFontTest {
    @Test
    fun systemNamesAnInstalledFamilyInsteadOfAGenericOne() {
        // libass has no fontconfig aliases on Android, so "sans-serif" is not a
        // real family and fontselect settles on an arbitrary system font.
        assertEquals("Roboto", mpvSubtitleFont("System"))
    }

    @Test
    fun mapsTheSettingsChoicesToInstalledFamilies() {
        assertEquals("Roboto", mpvSubtitleFont("sans-serif"))
        assertEquals("Noto Serif", mpvSubtitleFont("serif"))
        assertEquals("Droid Sans Mono", mpvSubtitleFont("monospace"))
        assertEquals("OpenDyslexic", mpvSubtitleFont("opendyslexic"))
    }

    @Test
    fun passesUnknownFamiliesThrough() {
        assertEquals("Comic Neue", mpvSubtitleFont("Comic Neue"))
    }
}
