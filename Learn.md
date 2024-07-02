


1、Leakcanary启动入口
MainProcessAppWatcherInstaller

2、手动初始化
AppWatcher.manualInstall(application)

3、通过反射调用InternalLeakCanary.invoke(application)，初始化泄露检测相关对象
LeakCanaryDelegate.loadLeakCanary(application)

4、启用组件销毁监听
```kotlin
val watchersToInstall = listOf(
  ActivityWatcher(application, deletableObjectReporter),
  FragmentAndViewModelWatcher(application, deletableObjectReporter),
  RootViewWatcher(deletableObjectReporter, WindowTypeFilter(watchDismissedDialogs)),
  ServiceWatcher(deletableObjectReporter)
)
watchersToInstall.forEach {
  it.install()
}
```

