import Cocoa
import FlutterMacOS
import macos_window_utils

class MainFlutterWindow: NSWindow {
  override func awakeFromNib() {
    let windowFrame = self.frame
    let windowUtilsController = MacOSWindowUtilsViewController()
    self.contentViewController = windowUtilsController
    self.setFrame(windowFrame, display: true)

    MainFlutterWindowManipulator.start(mainFlutterWindow: self)
    self.styleMask.insert(.fullSizeContentView)
    self.titlebarAppearsTransparent = true
    self.titleVisibility = .hidden
    self.minSize = NSSize(width: 480, height: 400)

    RegisterGeneratedPlugins(registry: windowUtilsController.flutterViewController)

    super.awakeFromNib()
    (NSApp.delegate as! AppDelegate).installWindowServices(for: self)
  }
}
