import Cocoa
import FlutterMacOS

@main
class AppDelegate: FlutterAppDelegate, NSWindowDelegate {
  private var statusItem: NSStatusItem?

  override func applicationDidFinishLaunching(_ notification: Notification) {
    super.applicationDidFinishLaunching(notification)
    installWindowServices(for: mainFlutterWindow!)
  }

  func installWindowServices(for window: NSWindow) {
    window.delegate = self
    if statusItem == nil {
      installStatusItem()
    }
  }

  func windowShouldClose(_ sender: NSWindow) -> Bool {
    sender.orderOut(nil)
    return false
  }

  private func installStatusItem() {
    let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    item.button?.image = statusImage()
    item.button?.toolTip = "Pixiv Shaft"

    let menu = NSMenu()
    menu.addItem(NSMenuItem(title: "Show", action: #selector(showWindow), keyEquivalent: ""))
    menu.addItem(.separator())
    menu.addItem(NSMenuItem(title: "Exit", action: #selector(exitApplication), keyEquivalent: ""))
    for menuItem in menu.items where !menuItem.isSeparatorItem {
      menuItem.target = self
    }
    item.menu = menu
    statusItem = item
  }

  private func statusImage() -> NSImage {
    let image = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
      NSColor.black.setFill()
      NSBezierPath(ovalIn: rect.insetBy(dx: 2.7, dy: 2.7)).fill()

      let letter = NSAttributedString(
        string: "P",
        attributes: [
          .font: NSFont.systemFont(ofSize: 10.2, weight: .heavy),
          .foregroundColor: NSColor.black,
        ]
      )
      let letterSize = letter.size()
      NSGraphicsContext.current?.compositingOperation = .clear
      letter.draw(at: NSPoint(
        x: (rect.width - letterSize.width) / 2 + 0.56,
        y: (rect.height - letterSize.height) / 2
      ))
      NSGraphicsContext.current?.compositingOperation = .sourceOver
      return true
    }
    image.isTemplate = true
    return image
  }

  @objc private func showWindow() {
    mainFlutterWindow!.makeKeyAndOrderFront(nil)
    NSApp.activate(ignoringOtherApps: true)
  }

  @objc private func exitApplication() {
    NSApp.terminate(nil)
  }

  override func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
    return false
  }

  override func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
    return true
  }
}
