# pteal79/image-resize

A NativePHP Mobile v4 plugin that shrinks a JPEG, PNG or HEIC photo to a maximum long edge and saves it as a JPEG, on the device.

The PHP bundled with NativePHP has no GD or Imagick, so PHP can't resize images itself, and it couldn't read HEIC anyway. This uses the platform decoders instead: ImageIO on iOS, `BitmapFactory` on Android.

```php
use Pteal79\ImageResize\Facades\ImageResize;

$image = ImageResize::resize($path); // longest edge ≤ 1980px, JPEG at quality 80

$image->path; // {name}-resized.jpg, beside the source
```

## Installation

```bash
composer require pteal79/image-resize
php artisan native:plugin:register pteal79/image-resize
php artisan native:plugin:list
```

Rebuild the app afterwards (`php artisan native:run`); native code compiles in at build time.

No permissions are needed: the plugin only reads and writes the paths it is given.

## Usage

```php
use Pteal79\ImageResize\Facades\ImageResize;
use Pteal79\ImageResize\ImageResizeException;

try {
    $image = ImageResize::resize(
        path: $photoPath,               // absolute path or file:// URL
        maxEdge: 1600,                  // default 1980
        destination: $uploadPath,       // default {name}-resized.jpg beside the source
        quality: 75,                    // 1–100, default 80
    );
} catch (ImageResizeException $e) {
    // $e->errorCode: missing_path, not_found, unreadable, write_failed or resize_failed
}

if ($image === null) {
    // Off-device (tests, the dev server): nothing was written.
}

$image->path;            // where the JPEG was written
$image->width;           // its size, upright
$image->height;
$image->originalWidth;   // the source's size, upright
$image->originalHeight;
$image->bytes;           // the JPEG's file size
$image->resized;         // false when the source was already within maxEdge
```

### Behaviour

- **Never scales up.** An image already within `maxEdge` keeps its size and is only re-encoded, so a PNG or HEIC still comes back as a JPEG.
- **Orientation is baked in.** EXIF rotation is applied to the pixels, so the JPEG is upright everywhere, including viewers that ignore EXIF.
- **Metadata is dropped.** No EXIF or GPS is copied to the output.
- **Transparency becomes white.** JPEG has no alpha, so transparent PNG areas are flattened onto white rather than going black.
- **Overwriting the source is safe.** The JPEG is written to a temporary file and swapped in, so `destination` may be the source path.
- **Missing folders are created** for `destination`.
- **Memory-friendly.** iOS decodes straight to the target size; Android decodes at a power-of-two reduction first. A 48MP photo is never held in memory at full resolution.

### Formats

| | iOS | Android |
|---|---|---|
| JPEG | ✓ | ✓ |
| PNG | ✓ | ✓ |
| HEIC / HEIF | ✓ | Android 9+, where the device has a HEIF decoder |

### Exceptions

`resize()` throws `InvalidArgumentException` when `maxEdge` is below 1 or `quality` is outside 1–100, and `ImageResizeException` when the device couldn't read or write the image.

## Bridge function

| Name | Parameters | Returns |
|---|---|---|
| `ImageResize.Resize` | `path` (required), `maxEdge`, `destination`, `quality` | `{ path, width, height, originalWidth, originalHeight, bytes, resized }` |

Error codes: `missing_path`, `not_found`, `unreadable`, `write_failed`, `resize_failed`.

## Events

None. The call is synchronous and returns the result.

## License

MIT
