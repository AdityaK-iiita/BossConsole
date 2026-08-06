package ai.rever.boss.crash

/**
 * How far any cause walk in this package goes.
 *
 * One fact, not four. This bound existed in four near-identical loops -
 * `isIgnorable`, `attributePluginId`, the uncontainable check and the render
 * route - three of which carried a comment saying they matched the others, which
 * is what a duplicated invariant looks like just before it stops matching. It did
 * stop matching: the cause-chain fix landed on one of the two uncontainable checks
 * and left its twin flat, so the same wrapped `OutOfMemoryError` escalated in one
 * place and was contained in the other.
 */
internal const val MAX_CAUSE_DEPTH = 12

/**
 * This throwable and its causes, nearest first, bounded and cycle-guarded.
 *
 * Wrapping is routine on these paths - an `InvocationTargetException` from a
 * reflective call, a `CompletionException` from a future, Compose's own wrappers -
 * so a question worth asking about a throwable is nearly always worth asking about
 * its causes too.
 *
 * The cycle guard is identity-based and not optional: `initCause` cannot build a
 * loop but an overridden `getCause` can, and a crash handler that hangs is worse
 * than one that misattributes. Eager rather than a `Sequence` because every caller
 * consumes the whole thing and one of them needs it reversed.
 */
internal fun Throwable.causeChain(max: Int = MAX_CAUSE_DEPTH): List<Throwable> {
    val chain = ArrayList<Throwable>(max)
    var current: Throwable? = this
    while (current != null && chain.size < max && chain.none { it === current }) {
        chain.add(current)
        current = current.cause
    }
    return chain
}
