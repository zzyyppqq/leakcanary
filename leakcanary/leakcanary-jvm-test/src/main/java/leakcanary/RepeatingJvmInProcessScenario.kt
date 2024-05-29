package leakcanary

import java.io.File
import shark.HeapDiff
import shark.ObjectGrowthDetector
import shark.RepeatingScenarioObjectGrowthDetector
import shark.forJvmHeap

/**
 * Creates a [RepeatingScenarioObjectGrowthDetector] suitable for JVM in process tests.
 *
 * Dumps the heap by leveraging Hotspot APIs, running an in process GC right before dumping.
 * Deletes the heap dump file as soon as we're done traversing it.
 *
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects]
 */
fun HeapDiff.Companion.repeatingJvmInProcessScenario(
  objectGrowthDetector: ObjectGrowthDetector = ObjectGrowthDetector.forJvmHeap(),
  heapDumpDirectoryProvider: HeapDumpDirectoryProvider = WorkingDirectoryHeapDumpDirectoryProvider(
    "heap_dumps_object_growth"
  ),
  heapDumper: HeapDumper = HeapDumper.forJvmInProcess()
    .withGc(gcTrigger = GcTrigger.inProcess())
    .withDetectorWarmup(objectGrowthDetector, androidHeap = false),
  heapDumpDeletionStrategy: HeapDumpDeletionStrategy = HeapDumpDeletionStrategy.DeleteOnHeapDumpClose(),
): RepeatingScenarioObjectGrowthDetector {
  return repeatingDumpingTestScenario(
    objectGrowthDetector = objectGrowthDetector,
    heapDumpDirectoryProvider = heapDumpDirectoryProvider,
    heapDumper = heapDumper,
    heapDumpDeletionStrategy = heapDumpDeletionStrategy,
  )
}
