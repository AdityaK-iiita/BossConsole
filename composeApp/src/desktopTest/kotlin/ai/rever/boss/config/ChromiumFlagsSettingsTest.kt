package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the settings→config bridge in [ChromiumFlagsSettings].
 *
 * The bridge is the part that can fail silently. A setting is written to disk, published as a
 * system property, and read back somewhere else entirely through [ConfigLoader] — so a field with
 * no corresponding entry in [ChromiumFlagsSettings.publishedValue], or a key that is not in
 * [ChromiumFlagKeys.PUBLISHED], produces a control that appears to work, persists, and changes
 * nothing. Nothing else in the codebase would notice.
 */
class ChromiumFlagsSettingsTest {
    @Test
    fun `defaults express no opinion at all`() {
        val defaults = ChromiumFlagsSettings()
        assertTrue(defaults.isDefault)
        // Every published key must be absent, not false or empty: a user who has never opened the
        // screen must get byte-identical engine options to the ones they got before it existed.
        for (key in ChromiumFlagKeys.PUBLISHED) {
            assertNull(defaults.publishedValue(key), "expected no value for $key by default")
        }
    }

    @Test
    fun `every published key is actually produced by some field`() {
        // The coverage guard the KDoc on publishedValue promises. Populated with a value for each
        // field, EVERY published key must resolve — otherwise a field exists that the bridge drops
        // on the floor, and its Settings row is decorative.
        val populated =
            ChromiumFlagsSettings(
                renderingMode = "OFF_SCREEN",
                skikoRenderApi = "METAL",
                topInsetDp = 12,
                browserPrewarm = false,
                rendererProcessLimit = 4,
                enableSkiaGraphite = true,
                disableSandbox = true,
                extraSwitches = "--custom",
            )
        for (key in ChromiumFlagKeys.PUBLISHED) {
            assertNotNull(
                populated.publishedValue(key),
                "no value published for $key - the field feeding it is missing from publishedValue()",
            )
        }
    }

    @Test
    fun `published values use the spellings the read sites parse`() {
        val settings =
            ChromiumFlagsSettings(
                renderingMode = "OFF_SCREEN",
                topInsetDp = 24,
                browserPrewarm = false,
                rendererProcessLimit = 4,
                enableSkiaGraphite = true,
                disableSandbox = true,
            )
        assertEquals("OFF_SCREEN", settings.publishedValue(ChromiumFlagKeys.RENDERING_MODE))
        assertEquals("24", settings.publishedValue(ChromiumFlagKeys.TOP_INSET_DP))
        assertEquals("4", settings.publishedValue(ChromiumFlagKeys.RENDERER_PROCESS_LIMIT))

        // Booleans go out as "true"/"false", which is what FluckEngine's isTruthyFlag and
        // isFalsyFlag accept. Asserted explicitly because these two are read by DIFFERENT
        // predicates: prewarm opts out on a falsy value, the other two opt in on a truthy one, so
        // a single wrong spelling would break one direction and not the other.
        assertEquals("false", settings.publishedValue(ChromiumFlagKeys.PREWARM))
        assertEquals("true", settings.publishedValue(ChromiumFlagKeys.SKIA_GRAPHITE))
        assertEquals("true", settings.publishedValue(ChromiumFlagKeys.DISABLE_SANDBOX))
    }

    @Test
    fun `a blank extra-switches field publishes nothing rather than an empty override`() {
        // Blank is what a user leaves behind after clearing the box. Publishing "" would set the
        // system property, which then OUTRANKS local.properties in ConfigLoader - so clearing the
        // field in the UI would suppress a value the user had deliberately configured elsewhere.
        for (blank in listOf("", "   ", "\n")) {
            assertNull(ChromiumFlagsSettings(extraSwitches = blank).publishedValue(ChromiumFlagKeys.EXTRA_SWITCHES))
        }
        assertEquals(
            "--ok",
            ChromiumFlagsSettings(extraSwitches = "--ok").publishedValue(ChromiumFlagKeys.EXTRA_SWITCHES),
        )
    }

    @Test
    fun `the DevTools port is deliberately not published as a system property`() {
        // It is read straight off the settings object by FluckEngine. Publishing it would put it
        // in ConfigLoader's chain, and from there a line in someone's local.properties could open
        // a DevTools endpoint - full control of the browser profile - on every future run of that
        // checkout, with nothing in the app to reveal it.
        assertFalse(ChromiumFlagKeys.REMOTE_DEBUGGING_PORT in ChromiumFlagKeys.PUBLISHED)
        assertNull(
            ChromiumFlagsSettings(remoteDebuggingPort = 9222)
                .publishedValue(ChromiumFlagKeys.REMOTE_DEBUGGING_PORT),
        )
    }

    @Test
    fun `settings-only fields are not published either`() {
        // These four are consumed directly by FluckEngine.applyPerformanceSwitches, not through a
        // config key, so there is nothing for them to publish. Asserted so that adding a key for
        // one later is a deliberate edit here rather than a surprise.
        val settings =
            ChromiumFlagsSettings(
                diskCacheMb = 128,
                noPings = false,
                disableDomainReliability = false,
                disableWinOcclusion = false,
                enableVaapi = false,
            )
        assertFalse(settings.isDefault)
        for (key in ChromiumFlagKeys.PUBLISHED) {
            assertNull(settings.publishedValue(key), "expected $key to stay unpublished")
        }
    }

    @Test
    fun `any single change is enough to stop looking default`() {
        // isDefault gates whether "Reset" is offered, so it must not be satisfied by a partial
        // match on the fields that happen to be checked first.
        val changes =
            listOf(
                ChromiumFlagsSettings(renderingMode = "OFF_SCREEN"),
                ChromiumFlagsSettings(skikoRenderApi = "SOFTWARE"),
                ChromiumFlagsSettings(topInsetDp = 0),
                ChromiumFlagsSettings(browserPrewarm = true),
                ChromiumFlagsSettings(rendererProcessLimit = 1),
                ChromiumFlagsSettings(enableSkiaGraphite = false),
                ChromiumFlagsSettings(disableSandbox = false),
                ChromiumFlagsSettings(diskCacheMb = 512),
                ChromiumFlagsSettings(noPings = true),
                ChromiumFlagsSettings(disableDomainReliability = true),
                ChromiumFlagsSettings(disableWinOcclusion = true),
                ChromiumFlagsSettings(enableVaapi = true),
                ChromiumFlagsSettings(remoteDebuggingPort = 9222),
                ChromiumFlagsSettings(extraSwitches = "--x"),
            )
        for (changed in changes) {
            assertFalse(changed.isDefault, "expected non-default: $changed")
        }
        // One per field, so a field added to the class without a case here leaves the count short.
        assertEquals(14, changes.size)
    }
}
