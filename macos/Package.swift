// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ModelAtlasMac",
    platforms: [.macOS(.v12)],
    products: [.executable(name: "ModelAtlas", targets: ["ModelAtlas"])],
    targets: [
        .target(name: "AtlasCore"),
        .executableTarget(name: "ModelAtlas", dependencies: ["AtlasCore"]),
        .testTarget(name: "AtlasCoreTests", dependencies: ["AtlasCore"])
    ]
)
