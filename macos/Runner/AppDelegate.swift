import Cocoa
import FlutterMacOS

@main
class AppDelegate: FlutterAppDelegate {
  // 关闭窗口不退出应用：主窗口隐藏后仍保留托盘与系统菜单栏，与旧版本行为一致。
  override func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
    return false
  }

  override func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
    return true
  }
}
