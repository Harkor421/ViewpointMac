// Prints the window number of the game's main window, for `screencapture -l` (captures only it).
import CoreGraphics
func area(_ w: [String: Any]) -> Double {
    let b = w[kCGWindowBounds as String] as? [String: Double] ?? [:]
    return (b["Width"] ?? 0) * (b["Height"] ?? 0)
}
let list = CGWindowListCopyWindowInfo([.optionAll], kCGNullWindowID) as? [[String: Any]] ?? []
// Only windows of the game's own process (pid in argv[1]), never another app's.
let pid = CommandLine.arguments.count > 1 ? Int(CommandLine.arguments[1]) ?? -1 : -1
let game = list.filter { ($0[kCGWindowOwnerPID as String] as? Int ?? -2) == pid }
    .filter { ($0[kCGWindowLayer as String] as? Int ?? 1) == 0 }
    .max { area($0) < area($1) }
if let n = game?[kCGWindowNumber as String] as? Int { print(n) }
