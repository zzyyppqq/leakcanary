package leakcanary

import shark.SharkLog

@Deprecated("Use DeletableObjectReporter instead", ReplaceWith("DeletableObjectReporter"))
fun interface ReachabilityWatcher {

  /**
   * Expects the provided [watchedObject] to become weakly reachable soon. If not,
   * [watchedObject] will be considered retained.
   */
  fun expectWeaklyReachable(
    watchedObject: Any,
    description: String
  )
  // ActivityWatcher等创建时调用此方法，等销毁时，回调此接口；进行可达性性分析对象是否泄露
  fun asDeletableObjectReporter(): DeletableObjectReporter =
    DeletableObjectReporter { target, reason ->
      // 对象销毁时，回调此方法
      SharkLog.d { "ReachabilityWatcher asDeletableObjectReporter expectWeaklyReachable" }
      expectWeaklyReachable(target, reason)
      // This exists for backward-compatibility purposes and as such is unable to return
      // an accurate [TrackedObjectReachability] implementation.
      object : TrackedObjectReachability {
        override val isStronglyReachable: Boolean
          get() = error("Use a non deprecated DeletableObjectReporter implementation instead")
        override val isRetained: Boolean
          get() = error("Use a non deprecated DeletableObjectReporter implementation instead")
      }
    }
}
