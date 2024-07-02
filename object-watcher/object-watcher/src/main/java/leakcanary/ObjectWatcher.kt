package leakcanary

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.milliseconds

/**
 * [ObjectWatcher] can be passed objects to [watch]. It will create [KeyedWeakReference] instances
 * that reference watches objects, and check if those references have been cleared as expected on
 * the [checkRetainedExecutor] executor. If not, these objects are considered retained and
 * [ObjectWatcher] will then notify registered [OnObjectRetainedListener]s on that executor thread.
 *
 * [checkRetainedExecutor] is expected to run its tasks on a background thread, with a significant
 * delay to give the GC the opportunity to identify weakly reachable objects.
 *
 * [ObjectWatcher] is thread safe.
 */
@Deprecated(
  "Use a RetainedObjectTracker implementation instead"
)
class ObjectWatcher private constructor(
  private val checkRetainedExecutor: Executor,
  private val clock: Clock,
  private val isEnabled: () -> Boolean,
  private val onObjectRetainedListeners: MutableSet<OnObjectRetainedListener> = CopyOnWriteArraySet(),
  private val retainedObjectTracker: ReferenceQueueRetainedObjectTracker = ReferenceQueueRetainedObjectTracker(
    { clock.uptimeMillis().milliseconds }) {
    onObjectRetainedListeners.forEach { it.onObjectRetained() }
  },
) : RetainedObjectTracker by retainedObjectTracker, ReachabilityWatcher {

  constructor(
    clock: Clock,
    checkRetainedExecutor: Executor,
    /**
     * Calls to [] will be ignored when [isEnabled] returns false
     */
    isEnabled: () -> Boolean = { true },
  ) : this(
    checkRetainedExecutor, clock, isEnabled
  )

  /**
   * Returns true if there are watched objects that aren't weakly reachable, even
   * if they haven't been watched for long enough to be considered retained.
   */
  val hasWatchedObjects: Boolean
    get() = hasTrackedObjects

  /**
   * Returns the objects that are currently considered retained. Calling this method will
   * end up creating local references to the objects, preventing them from becoming weakly
   * reachable, and creating a leak.
   */
  val retainedObjects: List<Any>
    get() = retainedObjectTracker.retainedWeakReferences.mapNotNull { it.getAndLeakReferent() }

  fun addOnObjectRetainedListener(listener: OnObjectRetainedListener) {
    onObjectRetainedListeners.add(listener)
  }

  fun removeOnObjectRetainedListener(listener: OnObjectRetainedListener) {
    onObjectRetainedListeners.remove(listener)
  }

  /**
   * Identical to [watch] with an empty string reference name.
   */
  fun watch(watchedObject: Any) {
    expectWeaklyReachable(watchedObject, "unknown: reason not provided")
  }

  fun watch(
    watchedObject: Any,
    description: String
  ) {
    expectWeaklyReachable(watchedObject, description)
  }

  // 外部可调用此方法，检测任意对象的泄露情况
  override fun expectWeaklyReachable(
    watchedObject: Any,
    description: String
  ) {
    if (!isEnabled()) {
      return
    }
    // 开始跟踪提供的target对象，期望它很快就能进行自动垃圾收集
    // 即，在返回的RetainTrigger上调用RetainTrigger.markRetainedIfStronglyReachable时，它不应该是强可达的。
    // 如果target保持强有力地实现，它将被视为“保留”。
    val retainTrigger =
      retainedObjectTracker.expectDeletionOnTriggerFor(watchedObject, description)
    checkRetainedExecutor.execute {
      // 延时5s在UI线程执行
      // 如果跟踪的对象仍然可强访问，则将其标记为“保留”。
      retainTrigger.markRetainedIfStronglyReachable()
    }
  }

  /**
   * Clears all [KeyedWeakReference] that were created before [heapDumpUptimeMillis] (based on
   * [clock] [Clock.uptimeMillis])
   */
  fun clearObjectsWatchedBefore(heapDumpUptimeMillis: Long) {
    clearObjectsTrackedBefore(heapDumpUptimeMillis.milliseconds)
  }

  /**
   * Clears all [KeyedWeakReference]
   */
  fun clearWatchedObjects() {
    clearAllObjectsTracked()
  }
}
