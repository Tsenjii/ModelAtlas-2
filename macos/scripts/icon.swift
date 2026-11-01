import AppKit
let target = URL(fileURLWithPath: CommandLine.arguments[1])
let image = NSImage(size: NSSize(width: 1024, height: 1024))
image.lockFocus()
let background = NSBezierPath(roundedRect: NSRect(x: 42, y: 42, width: 940, height: 940), xRadius: 218, yRadius: 218)
NSGradient(starting: NSColor(red: 0.02, green: 0.68, blue: 0.60, alpha: 1), ending: NSColor(red: 0.01, green: 0.30, blue: 0.32, alpha: 1))!.draw(in: background, angle: -60)
let path = NSBezierPath()
path.move(to: NSPoint(x: 270, y: 290)); path.line(to: NSPoint(x: 470, y: 730))
path.curve(to: NSPoint(x: 554, y: 730), controlPoint1: NSPoint(x: 490, y: 770), controlPoint2: NSPoint(x: 534, y: 770))
path.line(to: NSPoint(x: 754, y: 290)); path.lineWidth = 64; path.lineCapStyle = .round
NSColor.white.setStroke(); path.stroke()
let cross = NSBezierPath(); cross.move(to: NSPoint(x: 365, y: 430)); cross.line(to: NSPoint(x: 662, y: 430)); cross.lineWidth = 54; cross.lineCapStyle = .round; cross.stroke()
image.unlockFocus()
let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
try bitmap.representation(using: .png, properties: [:])!.write(to: target)
