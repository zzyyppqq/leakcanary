package com.example.leakcanary;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

/**
 * WeakReference
 * 弱引用对象，不会阻止其引用对象被设置为可终结、已终结并随后被回收。弱引用最常用于实现规范化映射。
 * 假设垃圾收集器在某个时间点确定某个对象是弱可达的。此时，它将自动清除对该对象的所有弱引用以及对任何其他弱可达对象的所有弱引用（通过强引用和软引用链可从这些对象到达）。
 * 同时，它将声明所有以前的弱可达对象为可终结的。同时或稍后，它将把那些新清除的、已注册到引用队列的弱引用加入队列。
 *
 * SoftReference
 * 软引用对象，由垃圾收集器根据内存需求自行清除。
 * 假设垃圾收集器在某个时间点确定某个对象是软可达的。此时，它可能会选择自动清除对该对象的所有软引用以及对任何其他软可达对象的所有软引用（通过强引用链可从这些对象到达）。同时或稍后，它会将那些已注册到引用队列的新清除的软引用加入队列。
 * 在虚拟机抛出OutOfMemoryError之前，保证清除对软可访问对象的所有软引用。否则，对清除软引用的时间或清除对不同对象的一组此类引用的顺序没有任何限制。但是，鼓励虚拟机实现避免清除最近创建或最近使用的软引用。
 * 避免缓存的软引用
 * 实际上，软引用对于缓存来说效率很低。运行时没有足够的信息来决定哪些引用需要清除，哪些需要保留。最致命的是，当需要在清除软引用和增加堆之间做出选择时，它不知道该怎么做。
 * 由于缺乏关于每个引用对于应用程序的价值的信息，软引用的实用性受到了限制。清除得太早的引用会导致不必要的工作；清除得太晚的引用则会浪费内存。
 * 大多数应用程序应使用android.util.LruCache而不是软引用。LruCache 具有有效的驱逐策略，并允许用户调整分配的内存量
 *
 * PhantomReference
 * 虚引用对象，在收集器确定其引用对象可能被回收后，这些对象会被加入队列。虚引用最常用于安排事后清理操作。
 * 假设垃圾收集器在某个时间点确定某个对象是幻像可访问的。此时，它将自动清除对该对象的所有虚引用以及对该对象可访问的任何其他幻像可访问对象的所有虚引用。
 * 同时或稍后，它将把这些新清除的虚引用加入引用队列。
 * 为了确保可回收对象保持可回收状态，不能检索虚引用的引用对象：虚引用的get方法始终返回null 。
 */
public class ReferenceQueueExample {
    public static void main(String[] args) {
        Object object = new Object();
        ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
        WeakReference<Object> weakReference = new WeakReference<>(object, referenceQueue);

        object = null; // 去除强引用，使对象成为垃圾回收的候选对象

        // 执行垃圾回收
        System.gc();

        // 检查引用队列中的引用对象
        Reference<?> reference = referenceQueue.poll();
        if (reference != null) {
            System.out.println("Object has been garbage collected.");
        }

      Object object2 = new Object();
      new SoftReference(object2, referenceQueue);
      new PhantomReference(object2, referenceQueue);
    }
}
