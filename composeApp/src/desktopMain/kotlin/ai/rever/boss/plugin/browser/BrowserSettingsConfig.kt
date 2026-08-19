package ai.rever.boss.plugin.browser

import androidx.compose.runtime.compositionLocalOf
import java.awt.Window

// User agent settings
object BrowserSettings {
    var userAgent: String? = null
    var customUserAgent: String? = null
    var currentProfile: String = "browser-profile"
    val availableProfiles = mutableListOf("browser-profile")

    // Browser initialization retry settings (configurable via Settings)
    var maxInitRetries: Int = 3
    var maxRecoveryAttempts: Int = 3

    // JavaScript dialog settings (configurable via Settings > Browser)
    // Due to JxBrowser threading limitations, dialogs must be auto-handled
    enum class JsConfirmBehavior { AUTO_CONFIRM, AUTO_CANCEL }

    var jsConfirmBehavior: JsConfirmBehavior = JsConfirmBehavior.AUTO_CONFIRM
    var jsPromptDefaultValue: String = "" // Empty string or user-configured default
    var jsPromptUsePageDefault: Boolean = true // Use page's default value if true, else use jsPromptDefaultValue

    // Secret Manager settings (configurable via Settings > Browser > Secret Manager).
    //
    // Mirrored to a JVM system property for the same reason [SHOW_SHARE_BUTTON_PROP] is: the
    // credential fill moved out of the host and into the fluck-browser plugin, which loads in a
    // separate classloader and cannot read this object. Without the mirror the Settings toggle
    // would still flip a field nothing reads - a switch that silently does nothing.
    const val DISCRETE_PASSWORD_FILL_PROP = "boss.fluck.discretePasswordFill"
    var discretePasswordFill: Boolean = true // Hide filled passwords with blur effect for privacy
        set(value) {
            field = value
            System.setProperty(DISCRETE_PASSWORD_FILL_PROP, value.toString())
        }

    // Tab sharing (configurable via Settings > Browser > Tab Sharing). OFF by default:
    // the co-browse share (QR) button stays hidden in the browser toolbar until the
    // user opts in. The toolbar is rendered by the fluck-browser plugin in a separate
    // classloader, so the value is mirrored to a JVM system property the plugin reads.
    const val SHOW_SHARE_BUTTON_PROP = "boss.fluck.showShareButton"
    var showShareButton: Boolean = false
        set(value) {
            field = value
            System.setProperty(SHOW_SHARE_BUTTON_PROP, value.toString())
        }

    init {
        // Publish the defaults up front. A Kotlin property initializer does not run through the
        // custom setter, so without this the properties stay unset until the settings file is
        // loaded - and a first launch with no settings file, or a failed load, would leave the
        // plugin reading nothing. It defaults `discretePasswordFill` back to true on its own, but
        // relying on two places to agree on a privacy default is how they drift apart.
        System.setProperty(DISCRETE_PASSWORD_FILL_PROP, discretePasswordFill.toString())
        System.setProperty(SHOW_SHARE_BUTTON_PROP, showShareButton.toString())
    }
}

/**
 * CompositionLocal providing the current AWT Window for this Compose window.
 * Used by JxBrowser to get the correct window handle for BrowserViewState.
 *
 * This fixes the multi-window crash where browsers in window 2 would reference
 * window 1's handle because getValidComposeWindow() returned the first window.
 */
val LocalAwtWindow = compositionLocalOf<Window?> { null }
