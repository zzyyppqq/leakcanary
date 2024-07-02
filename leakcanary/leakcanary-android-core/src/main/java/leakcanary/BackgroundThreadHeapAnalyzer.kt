package leakcanary

import android.os.Handler
import android.os.HandlerThread
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.AndroidDebugHeapAnalyzer
import leakcanary.internal.InternalLeakCanary
import shark.SharkLog

/**
 * Starts heap analysis on a background [HandlerThread] when receiving a [HeapDump] event.
 */
object BackgroundThreadHeapAnalyzer : EventListener {

  internal val heapAnalyzerThreadHandler by lazy {
    val handlerThread = HandlerThread("HeapAnalyzer")
    handlerThread.start()
    Handler(handlerThread.looper)
  }

  override fun onEvent(event: Event) {
    if (event is HeapDump) {
      SharkLog.d { "BackgroundThreadHeapAnalyzer onEvent" }
      heapAnalyzerThreadHandler.post {
        val doneEvent = AndroidDebugHeapAnalyzer.runAnalysisBlocking(event) { event ->
          SharkLog.d { "BackgroundThreadHeapAnalyzer onEvent: $event" }
          InternalLeakCanary.sendEvent(event)
        }
        SharkLog.d { "BackgroundThreadHeapAnalyzer onEvent: $doneEvent" }
        InternalLeakCanary.sendEvent(doneEvent)
      }
    }
  }
}
