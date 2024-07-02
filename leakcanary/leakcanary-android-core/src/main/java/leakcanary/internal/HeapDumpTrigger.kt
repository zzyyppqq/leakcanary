package leakcanary.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.squareup.leakcanary.core.R
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import leakcanary.AppWatcher
import leakcanary.EventListener.Event.DumpingHeap
import leakcanary.EventListener.Event.HeapDump
import leakcanary.EventListener.Event.HeapDumpFailed
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.LeakCanary.Config
import leakcanary.RetainedObjectTracker
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Nope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.NotifyingNope
import leakcanary.internal.InternalLeakCanary.onRetainInstanceListener
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import leakcanary.internal.RetainInstanceEvent.CountChanged.BelowThreshold
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpHappenedRecently
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpingDisabled
import leakcanary.internal.RetainInstanceEvent.NoMoreObjects
import leakcanary.internal.friendly.measureDurationMillis
import shark.AndroidResourceIdNames
import shark.SharkLog

internal class HeapDumpTrigger(
  private val application: Application,
  private val backgroundHandler: Handler,
  private val retainedObjectTracker: RetainedObjectTracker,
  private val gcTrigger: GcTrigger,
  private val configProvider: () -> Config
) {

  private val notificationManager
    get() =
      application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val applicationVisible
    get() = applicationInvisibleAt == -1L

  @Volatile
  private var checkScheduledAt: Long = 0L

  private var lastDisplayedRetainedObjectCount = 0

  private var lastHeapDumpUptimeMillis = 0L

  private val scheduleDismissRetainedCountNotification = {
    dismissRetainedCountNotification()
  }

  private val scheduleDismissNoRetainedOnTapNotification = {
    dismissNoRetainedOnTapNotification()
  }

  /**
   * When the app becomes invisible, we don't dump the heap immediately. Instead we wait in case
   * the app came back to the foreground, but also to wait for new leaks that typically occur on
   * back press (activity destroy).
   */
  private val applicationInvisibleLessThanWatchPeriod: Boolean
    get() {
      val applicationInvisibleAt = applicationInvisibleAt
      return applicationInvisibleAt != -1L && SystemClock.uptimeMillis() - applicationInvisibleAt < AppWatcher.retainedDelayMillis
    }

  @Volatile
  private var applicationInvisibleAt = -1L

  // Needs to be lazy because on Android 16, UUID.randomUUID().toString() will trigger a disk read
  // violation by calling RandomBitsSupplier.getUnixDeviceRandom()
  // Can't be lazy because this is a var.
  private var currentEventUniqueId: String? = null

  fun onApplicationVisibilityChanged(applicationVisible: Boolean) {
    if (applicationVisible) {
      applicationInvisibleAt = -1L
    } else {
      applicationInvisibleAt = SystemClock.uptimeMillis()
      // Scheduling for after watchDuration so that any destroyed activity has time to become
      // watch and be part of this analysis.
      scheduleRetainedObjectCheck(
        delayMillis = AppWatcher.retainedDelayMillis
      )
    }
  }

  private fun checkRetainedObjects() {
    // 检测保留的对象
    SharkLog.d { "HeapDumpTrigger checkRetainedObjects" }
    val iCanHasHeap = HeapDumpControl.iCanHasHeap()

    val config = configProvider()
    // 无法堆转储
    if (iCanHasHeap is Nope) {
      if (iCanHasHeap is NotifyingNope) {
        // Before notifying that we can't dump heap, let's check if we still have retained object.
        var retainedReferenceCount = retainedObjectTracker.retainedObjectCount
        SharkLog.d { "HeapDumpTrigger checkRetainedObjects NotifyingNope retainedReferenceCount: $retainedReferenceCount" }
        if (retainedReferenceCount > 0) {
          SharkLog.d { "HeapDumpTrigger checkRetainedObjects NotifyingNope gc start" }
          gcTrigger.runGc()
          SharkLog.d { "HeapDumpTrigger checkRetainedObjects NotifyingNope gc end" }
          // gc后再次检测保留对象个数
          retainedReferenceCount = retainedObjectTracker.retainedObjectCount
          SharkLog.d { "HeapDumpTrigger checkRetainedObjects NotifyingNope gc after retainedReferenceCount: $retainedReferenceCount" }
        }

        val nopeReason = iCanHasHeap.reason()
        val wouldDump = !checkRetainedCount(
          retainedReferenceCount, config.retainedVisibleThreshold, nopeReason
        )

        if (wouldDump) {
          val uppercaseReason = nopeReason[0].toUpperCase() + nopeReason.substring(1)
          onRetainInstanceListener.onEvent(DumpingDisabled(uppercaseReason))
          showRetainedCountNotification(
            objectCount = retainedReferenceCount,
            contentText = uppercaseReason
          )
        }
      } else {
        SharkLog.d {
          application.getString(
            R.string.leak_canary_heap_dump_disabled_text, iCanHasHeap.reason()
          )
        }
      }
      return
    }

    var retainedReferenceCount = retainedObjectTracker.retainedObjectCount
    SharkLog.d { "HeapDumpTrigger checkRetainedObjects retainedReferenceCount: $retainedReferenceCount" }
    if (retainedReferenceCount > 0) {
      SharkLog.d { "HeapDumpTrigger checkRetainedObjects gc start" }
      gcTrigger.runGc()
      SharkLog.d { "HeapDumpTrigger checkRetainedObjects gc end" }
      retainedReferenceCount = retainedObjectTracker.retainedObjectCount
      SharkLog.d { "HeapDumpTrigger checkRetainedObjects gc after retainedReferenceCount: $retainedReferenceCount" }
    }

    if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

    val now = SystemClock.uptimeMillis()
    val elapsedSinceLastDumpMillis = now - lastHeapDumpUptimeMillis
    if (elapsedSinceLastDumpMillis < WAIT_BETWEEN_HEAP_DUMPS_MILLIS) {
      onRetainInstanceListener.onEvent(DumpHappenedRecently)
      showRetainedCountNotification(
        objectCount = retainedReferenceCount,
        contentText = application.getString(R.string.leak_canary_notification_retained_dump_wait)
      )
      scheduleRetainedObjectCheck(
        delayMillis = WAIT_BETWEEN_HEAP_DUMPS_MILLIS - elapsedSinceLastDumpMillis
      )
      return
    }

    dismissRetainedCountNotification()
    val visibility = if (applicationVisible) "visible" else "not visible"
    SharkLog.d { "HeapDumpTrigger checkRetainedObjects dumpHeap start" }
    dumpHeap(
      retainedReferenceCount = retainedReferenceCount,
      retry = true,
      reason = "$retainedReferenceCount retained objects, app is $visibility"
    )
    SharkLog.d { "HeapDumpTrigger checkRetainedObjects dumpHeap end" }
  }

  private fun dumpHeap(
    retainedReferenceCount: Int,
    retry: Boolean,
    reason: String
  ) {
    val directoryProvider =
      InternalLeakCanary.createLeakDirectoryProvider(InternalLeakCanary.application)
    val heapDumpFile = directoryProvider.newHeapDumpFile()

    val durationMillis: Long
    if (currentEventUniqueId == null) {
      currentEventUniqueId = UUID.randomUUID().toString()
    }
    try {
      InternalLeakCanary.sendEvent(DumpingHeap(currentEventUniqueId!!))
      if (heapDumpFile == null) {
        throw RuntimeException("Could not create heap dump file")
      }
      saveResourceIdNamesToMemory()
      val heapDumpUptimeMillis = SystemClock.uptimeMillis()
      KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
      durationMillis = measureDurationMillis {
        configProvider().heapDumper.dumpHeap(heapDumpFile)
      }
      if (heapDumpFile.length() == 0L) {
        throw RuntimeException("Dumped heap file is 0 byte length")
      }
      lastDisplayedRetainedObjectCount = 0
      lastHeapDumpUptimeMillis = SystemClock.uptimeMillis()
      retainedObjectTracker.clearObjectsTrackedBefore(heapDumpUptimeMillis.milliseconds)
      currentEventUniqueId = UUID.randomUUID().toString()
      SharkLog.d { "InternalLeakCanary.sendEvent HeapDump" }
      InternalLeakCanary.sendEvent(HeapDump(currentEventUniqueId!!, heapDumpFile, durationMillis, reason))
    } catch (throwable: Throwable) {
      InternalLeakCanary.sendEvent(HeapDumpFailed(currentEventUniqueId!!, throwable, retry))
      if (retry) {
        scheduleRetainedObjectCheck(
          delayMillis = WAIT_AFTER_DUMP_FAILED_MILLIS
        )
      }
      showRetainedCountNotification(
        objectCount = retainedReferenceCount,
        contentText = application.getString(
          R.string.leak_canary_notification_retained_dump_failed
        )
      )
      return
    }
  }

  /**
   * Stores in memory the mapping of resource id ints to their corresponding name, so that the heap
   * analysis can label views with their resource id names.
   */
  private fun saveResourceIdNamesToMemory() {
    val resources = application.resources
    AndroidResourceIdNames.saveToMemory(
      getResourceTypeName = { id ->
        try {
          resources.getResourceTypeName(id)
        } catch (e: NotFoundException) {
          null
        }
      },
      getResourceEntryName = { id ->
        try {
          resources.getResourceEntryName(id)
        } catch (e: NotFoundException) {
          null
        }
      })
  }

  fun onDumpHeapReceived(forceDump: Boolean) {
    backgroundHandler.post {
      dismissNoRetainedOnTapNotification()
      gcTrigger.runGc()
      val retainedReferenceCount = retainedObjectTracker.retainedObjectCount
      if (!forceDump && retainedReferenceCount == 0) {
        SharkLog.d { "Ignoring user request to dump heap: no retained objects remaining after GC" }
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(application)
          .setContentTitle(
            application.getString(R.string.leak_canary_notification_no_retained_object_title)
          )
          .setContentText(
            application.getString(
              R.string.leak_canary_notification_no_retained_object_content
            )
          )
          .setAutoCancel(true)
          .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
        val notification =
          Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
        notificationManager.notify(
          R.id.leak_canary_notification_no_retained_object_on_tap, notification
        )
        backgroundHandler.postDelayed(
          scheduleDismissNoRetainedOnTapNotification,
          DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
        )
        lastDisplayedRetainedObjectCount = 0
        return@post
      }

      SharkLog.d { "Dumping the heap because user requested it" }
      dumpHeap(retainedReferenceCount, retry = false, "user request")
    }
  }

  private fun checkRetainedCount(
    retainedKeysCount: Int, // gc后保留的对象
    retainedVisibleThreshold: Int, // 默认保留对象阀值
    nopeReason: String? = null
  ): Boolean {
    val countChanged = lastDisplayedRetainedObjectCount != retainedKeysCount
    lastDisplayedRetainedObjectCount = retainedKeysCount
    if (retainedKeysCount == 0) {
      if (countChanged) {
        SharkLog.d { "All retained objects have been garbage collected" }
        onRetainInstanceListener.onEvent(NoMoreObjects)
        showNoMoreRetainedObjectNotification()
      }
      return true
    }

    val applicationVisible = applicationVisible
    val applicationInvisibleLessThanWatchPeriod = applicationInvisibleLessThanWatchPeriod

    if (countChanged) {
      val whatsNext = if (applicationVisible) {
        if (retainedKeysCount < retainedVisibleThreshold) {
          "not dumping heap yet (app is visible & < $retainedVisibleThreshold threshold)"
        } else {
          if (nopeReason != null) {
            "would dump heap now (app is visible & >=$retainedVisibleThreshold threshold) but $nopeReason"
          } else {
            "dumping heap now (app is visible & >=$retainedVisibleThreshold threshold)"
          }
        }
      } else if (applicationInvisibleLessThanWatchPeriod) { // 当应用变得不可见时，我们不会立即转储堆。相反，我们会等待应用返回前台，同时也等待通常在按下返回键（活动销毁）时发生的新泄漏
        val wait =
          AppWatcher.retainedDelayMillis - (SystemClock.uptimeMillis() - applicationInvisibleAt)
        if (nopeReason != null) {
          "would dump heap in $wait ms (app just became invisible) but $nopeReason"
        } else {
          "dumping heap in $wait ms (app just became invisible)"
        }
      } else {
        if (nopeReason != null) {
          "would dump heap now (app is invisible) but $nopeReason"
        } else {
          "dumping heap now (app is invisible)"
        }
      }

      SharkLog.d {
        val s = if (retainedKeysCount > 1) "s" else ""
        "Found $retainedKeysCount object$s retained, $whatsNext"
      }
    }

    if (retainedKeysCount < retainedVisibleThreshold) {
      if (applicationVisible || applicationInvisibleLessThanWatchPeriod) {
        if (countChanged) {
          onRetainInstanceListener.onEvent(BelowThreshold(retainedKeysCount))
        }
        showRetainedCountNotification(
          objectCount = retainedKeysCount,
          contentText = application.getString(
            R.string.leak_canary_notification_retained_visible, retainedVisibleThreshold
          )
        )
        // 有一次泄露后，循环gc检查对象，每2s执行一次
        scheduleRetainedObjectCheck(
          delayMillis = WAIT_FOR_OBJECT_THRESHOLD_MILLIS
        )
        return true
      }
    }
    return false
  }

  fun scheduleRetainedObjectCheck(
    delayMillis: Long = 0L
  ) {
    val checkCurrentlyScheduledAt = checkScheduledAt
    //SharkLog.d { "HeapDumpTrigger scheduleRetainedObjectCheck checkCurrentlyScheduledAt: $checkCurrentlyScheduledAt \r\n${Log.getStackTraceString(Throwable())}" }
    SharkLog.d { "HeapDumpTrigger scheduleRetainedObjectCheck checkCurrentlyScheduledAt: $checkCurrentlyScheduledAt" }
    if (checkCurrentlyScheduledAt > 0) {
      // 避免执行太频繁
      return
    }
    checkScheduledAt = SystemClock.uptimeMillis() + delayMillis
    backgroundHandler.postDelayed({
      checkScheduledAt = 0
      checkRetainedObjects()
    }, delayMillis)
  }

  private fun showNoMoreRetainedObjectNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    val builder = Notification.Builder(application)
      .setContentTitle(
        application.getString(R.string.leak_canary_notification_no_retained_object_title)
      )
      .setContentText(
        application.getString(
          R.string.leak_canary_notification_no_retained_object_content
        )
      )
      .setAutoCancel(true)
      .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
    backgroundHandler.postDelayed(
      scheduleDismissRetainedCountNotification, DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
    )
  }

  private fun showRetainedCountNotification(
    objectCount: Int,
    contentText: String
  ) {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    @Suppress("DEPRECATION")
    val builder = Notification.Builder(application)
      .setContentTitle(
        application.getString(R.string.leak_canary_notification_retained_title, objectCount)
      )
      .setContentText(contentText)
      .setAutoCancel(true)
      .setContentIntent(NotificationReceiver.pendingIntent(application, DUMP_HEAP))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
  }

  private fun dismissRetainedCountNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    notificationManager.cancel(R.id.leak_canary_notification_retained_objects)
  }

  private fun dismissNoRetainedOnTapNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissNoRetainedOnTapNotification)
    notificationManager.cancel(R.id.leak_canary_notification_no_retained_object_on_tap)
  }

  companion object {
    internal const val WAIT_AFTER_DUMP_FAILED_MILLIS = 5_000L
    private const val WAIT_FOR_OBJECT_THRESHOLD_MILLIS = 2_000L
    private const val DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS = 30_000L
    private const val WAIT_BETWEEN_HEAP_DUMPS_MILLIS = 60_000L
  }
}
