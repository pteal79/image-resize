## pteal79/image-resize

Shrinks a JPEG, PNG or HEIC (Android 9+ for HEIC) on the device and saves it as a JPEG. Use it instead of GD or Imagick, which the NativePHP PHP runtime doesn't have.

### PHP Usage

@verbatim
<code-snippet name="Resizing a photo before upload" lang="php">
use Pteal79\ImageResize\Facades\ImageResize;
use Pteal79\ImageResize\ImageResizeException;

try {
    $image = ImageResize::resize($path, maxEdge: 1980, destination: null, quality: 80);
} catch (ImageResizeException $e) {
    report($e); // fall back to the original file
}

$uploadPath = $image?->path ?? $path;
</code-snippet>
@endverbatim

- `ImageResize::resize(string $path, int $maxEdge = 1980, ?string $destination = null, int $quality = 80): ?ResizedImage`
- Returns null off-device. Throws `ImageResizeException` (with `errorCode`) on a native failure, and `InvalidArgumentException` for `maxEdge < 1` or `quality` outside 1–100.
- `ResizedImage` has `path`, `width`, `height`, `originalWidth`, `originalHeight`, `bytes` and `resized`.
- Never scales up. EXIF orientation is baked in, metadata is dropped, and transparency becomes white.
- The default destination is `{name}-resized.jpg` beside the source. The destination may be the source itself.
