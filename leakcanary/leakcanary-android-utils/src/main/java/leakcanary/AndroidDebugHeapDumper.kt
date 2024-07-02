package leakcanary

import android.os.Debug
import java.io.File
import shark.SharkLog

/**
 * Dumps the Android heap using [Debug.dumpHprofData].
 *
 * Note: despite being part of the Debug class, [Debug.dumpHprofData] can be called from non
 * debuggable non profileable builds.
 */
object AndroidDebugHeapDumper : HeapDumper {
  override fun dumpHeap(heapDumpFile: File) {
    SharkLog.d { "AndroidDebugHeapDumper dumpHeap start heapDumpFile: ${heapDumpFile.absolutePath}" }
    Debug.dumpHprofData(heapDumpFile.absolutePath)
    SharkLog.d { "AndroidDebugHeapDumper dumpHeap end" }
  }
}

fun HeapDumper.Companion.forAndroidInProcess() = AndroidDebugHeapDumper
