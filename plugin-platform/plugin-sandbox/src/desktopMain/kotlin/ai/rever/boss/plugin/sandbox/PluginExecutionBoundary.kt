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
     *
     * A plain ThreadLocal, not `withInitial`: [currentPluginId] is read from the
     * crash path on arbitrary threads, and an initialised one would plant an empty
     * deque on every thread that ever asks and never take it back.
     */
    private val executing = ThreadLocal<ArrayDeque<String>>()

    /** Weak-keyed so a tag can never keep the throwable (and its stack) alive. */
    private val tags: MutableMap<Throwable, String> =
        Collections.synchronizedMap(WeakHashMap<Throwable, String>())

    /**
     * Cache of owning-class → owning plugin, including **misses**.
     *
     * [wrapPluginCallback] runs per context-menu item per recomposition, on the
     * event thread, and for a host-owned lambda - the common case - an uncached
     * miss builds and throws a reflective exception, whose cost is filling in a
     * stack trace.
     *
     * A [ClassValue] rather than a synchronized map: lookups are lock-free, so the
     * hit path takes no global monitor on the EDT, and each entry is held by the
     * Class it is keyed on, so it dies with the class and its classloader when a
     * plugin is unloaded. A `ConcurrentHashMap<ClassLoader, …>` would have pinned
     * unloaded plugin classloaders; a `WeakHashMap` fixed that but put a lock back
     * on the hot path. Null values are cached, which is the point.
     */
    private val ownerPluginIds =
        object : ClassValue<String?>() {
            override fun computeValue(type: Class<*>): String? = type.classLoader?.let(::resolvePluginId)
        }

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
        val stack = executing.get() ?: ArrayDeque<String>().also { executing.set(it) }
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
     *
     * **Caveat: a reused throwable keeps its first tag.** Some libraries throw a
     * cached, stackless singleton, and a strongly-held one never leaves the weak
     * map either. It would carry whichever plugin threw it first, for the life of
     * the process. Low probability, and the failure direction is "wrong plugin
     * disabled, app survives" rather than "session lost" - but if a real case turns
     * up, keying on identity plus a timestamp is the way out.
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

    /**
     * The plugin this thread is executing right now, innermost first, or null.
     *
     * **Not re-entrancy safe against a nested event pump.** Swing runs a secondary
     * event loop on the same EDT for modal dialogs, popups and drag loops. If
     * plugin code inside [runAttributed] opens one, an unrelated *host* exception
     * dispatched during that pump sees this scope still on the deque and is
     * attributed to the plugin. Left as is: the tag is consulted first and is
     * exact, this is only the fallback ahead of the classloader scan, and the
     * failure direction is "blames a plugin for a host bug" rather than "loses the
     * session". Worth revisiting if a real case turns up.
     */
    fun currentPluginId(): String? = executing.get()?.lastOrNull()

    /**
     * The plugin that defined [owner], or null when the host did.
     *
     * Used to attribute callbacks the host holds but did not write — a
     * context-menu `onClick`, a registered action — where the lambda's own class
     * was loaded by the plugin's classloader even though nothing in the *stack*
     * says so at call time.
     *
     * Resolved reflectively so this module keeps no dependency on
     * `plugin-loader`, and cached per classloader - see [ownerPluginIds].
     */
    fun pluginIdOfOwner(owner: Any?): String? = owner?.let { ownerPluginIds.get(it.javaClass) }

    /**
     * Ask a classloader for its plugin id: **getter first**, then a public field.
     *
     * The getter is not a fallback, it is the production shape.
     * `PluginClassLoader` declares `val pluginId: String` as a constructor
     * property, which compiles to a *private* backing field plus a public
     * `getPluginId()`, so `getField("pluginId")` throws `NoSuchFieldException` for
     * every real plugin classloader. Field-only lookup therefore returned null for
     * exactly the plugins it existed to identify, and the crash it was meant to
     * attribute would have terminated the app.
     *
     * That is not a hypothetical: it shipped in the first version of this file,
     * and the unit test passed because the fixture declared `@JvmField val
     * pluginId` - the one form `getField` can see, and the one production does not
     * use. The fixture now mirrors production and
     * `CrashHandlerAttributionTest` asserts against a real `PluginClassLoader`.
     *
     * The field branch is kept for a loader that does use `@JvmField` or is
     * written in Java. Failures are swallowed on both branches: a host classloader
     * has neither member, and attribution must never break the call it describes.
     */
    private fun resolvePluginId(loader: ClassLoader): String? =
        runCatching { loader.javaClass.getMethod("getPluginId").invoke(loader) as? String }.getOrNull()
            ?: runCatching { loader.javaClass.getField("pluginId").get(loader) as? String }.getOrNull()

    /**
     * Invoke a plugin-supplied callback with its plugin in scope, allocating nothing.
     *
     * The counterpart to [wrapPluginCallback], and the better shape wherever the
     * host owns the *call* rather than merely holding the reference. Wrapping at
     * mapping time hands back a fresh closure every time, which for a context menu
     * means a new one per item per recomposition - the items then compare unequal
     * and Compose cannot skip the subtree. Attributing at the moment of invocation
     * costs one cached class lookup and no allocation at all, and covers every
     * callback that reaches the call site rather than only those that happened to
     * be built through a wrapping factory.
     */
    fun invokeAttributed(action: () -> Unit) {
        val pluginId = pluginIdOfOwner(action)
        if (pluginId == null) action() else runAttributed(pluginId) { action() }
    }

    /**
     * Wrap a plugin-supplied callback so a throwable escaping it is attributed.
     *
     * Returns [action] **unchanged** when it is host-owned, so wrapping every menu
     * item costs one cached class lookup and no extra frame for host items.
     *
     * A plugin-owned action does get a fresh wrapper per call, which costs Compose
     * the ability to skip a subtree whose items would otherwise compare equal.
     * Caching wrappers is not as easy as it looks: a wrapper captures the action it
     * wraps, so an action-keyed weak map is a strong reference from value to key
     * and never collects. The durable fix is to wrap once at registration time
     * rather than per mapping call; until then the per-call cost is one allocation.
     */
    fun wrapPluginCallback(action: () -> Unit): () -> Unit {
        val pluginId = pluginIdOfOwner(action) ?: return action
        return { runAttributed(pluginId) { action() } }
    }

    /** Drop every recorded tag. For tests; production entries expire with their throwable. */
    internal fun resetForTest() {
        tags.clear()
        // ownerPluginIds is deliberately not reset: a ClassValue entry is keyed on
        // the Class, so it can only be stale if the class itself changed, which
        // cannot happen within a run. Clearing it would need a per-class remove and
        // buy nothing.
        executing.remove()
    }
}
