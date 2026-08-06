package ai.rever.boss.plugin.sandbox

import java.util.Collections
import java.util.WeakHashMap

/**
 * Remembers which plugin the host was executing when a throwable escaped.
 *
 * ### Why the stack is not enough
 *
 * [ai.rever.boss.plugin.sandbox.ui.PluginCrashInterceptor.attributeToPlugin] and
 * `CrashHandler.attributePluginId` both work backwards from a throwable: thread
 * names, class-name prefixes, defining classloaders. That works while plugin
 * frames are still on the stack, and stops working the moment the plugin hands
 * control back:
 *
 * ```
 * IllegalStateException: boom
 *   at ai.rever.boss.components.overlays.ContextMenuKt…invoke   <- host frame
 *   at androidx.compose.foundation.ClickableNode…
 * ```
 *
 * A plugin's context-menu action is a lambda the plugin *registered* and the host
 * *invokes*; by the time the exception reaches the uncaught handler the plugin's
 * own frames may be gone, inlined, or replaced by a coroutine resumption. Guessing
 * from a package prefix then attributes the crash to the host and the whole app
 * is torn down for one plugin's bug.
 *
 * ### What this does instead
 *
 * The host wraps each call *into* plugin code, so the plugin id is known before
 * anything throws:
 *
 * 1. [runAttributed] records the id in a thread-local for the duration of the
 *    call, so anything that asks *during* the call gets an answer.
 * 2. On the way out it [tag]s the escaping throwable with that id. The tag
 *    outlives the stack, which is the whole point: the uncaught handler runs
 *    after every frame has unwound and the thread-local has already been popped.
 *
 * ### Memory
 *
 * Tags live in a weak-keyed map, so a tagged throwable is collected normally and
 * the entry goes with it. Keys are [Throwable]s, which do not override `equals`
 * or `hashCode`, so lookup is identity-based — two structurally identical
 * exceptions are still two entries.
 *
 * Thread-safe: crashes arrive on whichever thread was running plugin code.
 */
object PluginExecutionBoundary {
    /**
     * How far [attributionFor] walks a cause chain. Matches the bound
     * `CrashHandler.isIgnorable` and `attributePluginId` already use, and stops a
     * self-referential chain from spinning.
     */
    private const val MAX_CAUSE_DEPTH = 12

    /**
     * Plugin ids currently being executed on this thread, innermost last.
     *
     * A stack rather than a single slot because plugin code calls back into the
     * host, which can call another plugin: a panel of plugin A rendering a
     * status-bar item contributed by plugin B. Popping must restore A rather than
     * clear the marker outright.
     */
    private val executing = ThreadLocal.withInitial { ArrayDeque<String>() }

    /** Weak-keyed so a tag can never keep the throwable (and its stack) alive. */
    private val tags: MutableMap<Throwable, String> =
        Collections.synchronizedMap(WeakHashMap<Throwable, String>())

    /**
     * Run [block] as [pluginId], tagging anything that escapes.
     *
     * The tag is attached in a `catch` rather than a `finally` because only an
     * escaping throwable needs one — and because `finally` has no access to it.
     * Rethrown unchanged: this records blame, it does not change control flow.
     */
    // Throwable, not Exception, and deliberately so: binary incompatibility in a
    // plugin surfaces as NoSuchMethodError / NoClassDefFoundError, which is one of
    // the crashes most worth attributing. It is rethrown untouched.
    @Suppress("TooGenericExceptionCaught")
    fun <T> runAttributed(
        pluginId: String,
        block: () -> T,
    ): T {
        val stack = executing.get()
        stack.addLast(pluginId)
        try {
            return block()
        } catch (t: Throwable) {
            tag(t, pluginId)
            throw t
        } finally {
            stack.removeLastOrNull()
            // ThreadLocals on pooled threads (EDT, dispatchers) outlive the call,
            // so an empty stack is removed rather than left as an empty deque.
            if (stack.isEmpty()) executing.remove()
        }
    }

    /**
     * Record [pluginId] as responsible for [throwable].
     *
     * First tag wins. A throwable crossing several boundaries on its way out
     * (plugin panel factory → sandboxed registry → plugin lifecycle) should keep
     * the innermost attribution, which is the one closest to the fault.
     */
    fun tag(
        throwable: Throwable,
        pluginId: String,
    ) {
        // putIfAbsent, not put: see above. Also cheap enough for the crash path,
        // which is the only caller that matters for latency.
        tags.putIfAbsent(throwable, pluginId)
    }

    /**
     * The plugin blamed for [throwable], walking the cause chain.
     *
     * Wrapping is routine — a plugin's exception arrives inside an
     * `InvocationTargetException`, a `CompletionException`, or Compose's own
     * wrapper — so a tag on any link in the chain answers for the whole chain.
     * Nearest tag first, so an inner plugin outranks an outer wrapper.
     */
    fun attributionFor(throwable: Throwable): String? {
        var current: Throwable? = throwable
        var depth = 0
        val seen = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.none { it === current }) {
            tags[current]?.let { return it }
            seen.add(current)
            current = current.cause
            depth++
        }
        return null
    }

    /** The plugin this thread is executing right now, innermost first, or null. */
    fun currentPluginId(): String? = executing.get().lastOrNull()

    /**
     * The plugin that defined [owner], or null when the host did.
     *
     * Used to attribute callbacks the host holds but did not write — a
     * context-menu `onClick`, a registered action — where the lambda's own class
     * was loaded by the plugin's classloader even though nothing in the *stack*
     * says so at call time.
     *
     * Resolved reflectively against a `pluginId` field, exactly as
     * [ai.rever.boss.plugin.sandbox.ui.PluginCrashInterceptor.register] does, so
     * this module keeps no dependency on `plugin-loader`.
     */
    fun pluginIdOfOwner(owner: Any?): String? {
        val loader = owner?.javaClass?.classLoader ?: return null
        // Swallowed wholesale: a host classloader simply has no `pluginId` field
        // (NoSuchFieldException), and attribution must never be able to break the
        // call it is only describing.
        return runCatching { loader.javaClass.getField("pluginId").get(loader) as? String }.getOrNull()
    }

    /**
     * Wrap a plugin-supplied callback so a throwable escaping it is attributed.
     *
     * Returns [action] **unchanged** when it is host-owned, so wrapping every
     * menu item costs one classloader lookup and no extra frame for host items.
     */
    fun wrapPluginCallback(action: () -> Unit): () -> Unit {
        val pluginId = pluginIdOfOwner(action) ?: return action
        return { runAttributed(pluginId) { action() } }
    }

    /** Drop every recorded tag. For tests; production entries expire with their throwable. */
    internal fun resetForTest() {
        tags.clear()
        executing.remove()
    }
}
