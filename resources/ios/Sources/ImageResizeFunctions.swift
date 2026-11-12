import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// Image resizing. Namespace: "ImageResize.*"
enum ImageResizeFunctions {

    static let defaultMaxEdge = 1980
    static let defaultQuality = 80

    /// Scales an image down to a maximum long edge and writes it as a JPEG.
    /// Parameters:
    ///   - path: string - absolute path (or file:// URL) of a JPEG, PNG or HEIC (required)
    ///   - maxEdge: int - the longest edge allowed, in pixels (optional, default 1980)
    ///   - destination: string - where to write the JPEG (optional, default `{name}-resized.jpg` beside the source)
    ///   - quality: int - JPEG quality 1–100 (optional, default 80)
    ///
    /// Never scales up. Orientation is baked into the pixels, metadata is
    /// dropped, and transparency is flattened onto white.
    class Resize: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let rawPath = parameters["path"] as? String, !rawPath.isEmpty else {
                return BridgeResponse.error(code: "missing_path", message: "The path of the image to resize is required.")
            }

            let sourcePath = ImageResizeFunctions.filePath(rawPath)

            guard FileManager.default.fileExists(atPath: sourcePath) else {
                return BridgeResponse.error(code: "not_found", message: "No image at \(sourcePath).")
            }

            let maxEdge = max(1, (parameters["maxEdge"] as? NSNumber)?.intValue ?? ImageResizeFunctions.defaultMaxEdge)
            let quality = min(100, max(1, (parameters["quality"] as? NSNumber)?.intValue ?? ImageResizeFunctions.defaultQuality))

            let destinationPath: String
            if let destination = parameters["destination"] as? String, !destination.isEmpty {
                destinationPath = ImageResizeFunctions.filePath(destination)
            } else {
                destinationPath = ImageResizeFunctions.defaultDestination(for: sourcePath)
            }

            let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary

            guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: sourcePath) as CFURL, sourceOptions),
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                  let pixelWidth = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
                  let pixelHeight = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
                  pixelWidth > 0, pixelHeight > 0 else {
                return BridgeResponse.error(code: "unreadable", message: "The file is not an image this device can read.")
            }

            // Orientations 5–8 store the pixels on their side, so the upright
            // image has its width and height the other way round.
            let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
            let onItsSide = (5...8).contains(orientation)
            let originalWidth = onItsSide ? pixelHeight : pixelWidth
            let originalHeight = onItsSide ? pixelWidth : pixelHeight
            let longEdge = max(originalWidth, originalHeight)

            // The thumbnail decoder reads straight to the target size, so a
            // 48MP photo never sits in memory at full resolution, and applies
            // the EXIF orientation as it goes.
            let thumbnailOptions: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: min(maxEdge, longEdge),
            ]

            guard let scaled = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
                return BridgeResponse.error(code: "unreadable", message: "The image could not be decoded.")
            }

            guard let output = ImageResizeFunctions.flattenedOntoWhite(scaled) else {
                return BridgeResponse.error(code: "resize_failed", message: "The image could not be redrawn.")
            }

            do {
                try ImageResizeFunctions.writeJpeg(output, to: destinationPath, quality: quality)
            } catch {
                return BridgeResponse.error(code: "write_failed", message: error.localizedDescription)
            }

            let bytes = (try? FileManager.default.attributesOfItem(atPath: destinationPath)[.size] as? NSNumber)?.intValue ?? 0

            return BridgeResponse.success(data: [
                "path": destinationPath,
                "width": output.width,
                "height": output.height,
                "originalWidth": originalWidth,
                "originalHeight": originalHeight,
                "bytes": bytes,
                "resized": longEdge > maxEdge,
            ])
        }
    }

    /// Accepts a plain path or a file:// URL.
    static func filePath(_ raw: String) -> String {
        if raw.hasPrefix("file://"), let url = URL(string: raw) {
            return url.path
        }

        return raw
    }

    /// `{name}-resized.jpg`, beside the source.
    static func defaultDestination(for sourcePath: String) -> String {
        let source = URL(fileURLWithPath: sourcePath)
        let name = source.deletingPathExtension().lastPathComponent

        return source.deletingLastPathComponent().appendingPathComponent("\(name)-resized.jpg").path
    }

    /// JPEG has no alpha channel, and an encoder left to drop it turns
    /// transparent areas black. Opaque images are passed through untouched.
    static func flattenedOntoWhite(_ image: CGImage) -> CGImage? {
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast:
            return image
        default:
            break
        }

        guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil,
                  width: image.width,
                  height: image.height,
                  bitsPerComponent: 8,
                  bytesPerRow: 0,
                  space: colorSpace,
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
              ) else {
            return nil
        }

        let bounds = CGRect(x: 0, y: 0, width: image.width, height: image.height)
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(bounds)
        context.draw(image, in: bounds)

        return context.makeImage()
    }

    /// Writes to a temporary file first and swaps it in, so a destination
    /// that is the source itself is never half-written.
    static func writeJpeg(_ image: CGImage, to path: String, quality: Int) throws {
        let fileManager = FileManager.default
        let destination = URL(fileURLWithPath: path)
        let directory = destination.deletingLastPathComponent()

        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)

        let temporary = directory.appendingPathComponent(".\(UUID().uuidString).jpg")

        guard let writer = CGImageDestinationCreateWithURL(temporary as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw ResizeError.cannotCreateFile(path)
        }

        let properties = [kCGImageDestinationLossyCompressionQuality: Double(quality) / 100.0] as CFDictionary
        CGImageDestinationAddImage(writer, image, properties)

        guard CGImageDestinationFinalize(writer) else {
            try? fileManager.removeItem(at: temporary)
            throw ResizeError.cannotCreateFile(path)
        }

        if fileManager.fileExists(atPath: path) {
            _ = try fileManager.replaceItemAt(destination, withItemAt: temporary)
        } else {
            try fileManager.moveItem(at: temporary, to: destination)
        }
    }

    enum ResizeError: LocalizedError {
        case cannotCreateFile(String)

        var errorDescription: String? {
            switch self {
            case .cannotCreateFile(let path):
                return "Could not write a JPEG to \(path)."
            }
        }
    }
}
