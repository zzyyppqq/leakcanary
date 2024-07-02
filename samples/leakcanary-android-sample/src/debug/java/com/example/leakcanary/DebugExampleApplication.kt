package com.example.leakcanary

import android.util.Log
import leakcanary.EventListener
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.LeakCanary
import org.leakcanary.internal.LeakUiAppClient
import shark.SharkLog

class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()

    // TODO We need to decide whether to show the activity icon based on whether
    //  the app library is here (?). Though ideally the embedded activity is also a separate
    //  optional module.
    LeakCanary.config = LeakCanary.config.run {
      this.objectInspectors
      copy(eventListeners = eventListeners + EventListener {
        // TODO Move this into an EventListener class, maybe the standard one
        //  TODO Detect if app installed or not and delegate to std leakcanary if not.
        if (it is HeapAnalysisDone<*>) {
          SharkLog.d { "DebugExampleApplication eventListeners it: $it" }
          LeakUiAppClient(this@DebugExampleApplication).sendHeapAnalysis(it.heapAnalysis)
        }
      })
    }
  }
}
