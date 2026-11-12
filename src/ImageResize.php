<?php

namespace Pteal79\ImageResize;

use InvalidArgumentException;

/**
 * Shrinks a photo on the device and saves it as a JPEG.
 *
 * Done natively because the PHP bundled with NativePHP has no GD or Imagick,
 * and because the platform decoders read HEIC, which PHP couldn't anyway.
 * The image is only ever made smaller: one already inside the limit keeps its
 * size and is just re-encoded, so a PNG or HEIC still comes back as a JPEG.
 */
class ImageResize
{
    /** The longest edge an image is given unless the caller says otherwise, in pixels. */
    public const DEFAULT_MAX_EDGE = 1980;

    /** JPEG quality, 1–100, unless the caller says otherwise. */
    public const DEFAULT_QUALITY = 80;

    /**
     * Scale the image at $path down so its longest edge is at most $maxEdge
     * pixels, and write it as a JPEG.
     *
     * Without a $destination the JPEG is written beside the source as
     * `{name}-resized.jpg`. Orientation is baked into the pixels and the
     * metadata (EXIF, GPS) is dropped; transparency is flattened onto white.
     *
     * Returns null off-device, where there is no native side to call.
     *
     * @throws InvalidArgumentException when $maxEdge or $quality is out of range
     * @throws ImageResizeException when the device could not read or write the image
     */
    public function resize(
        string $path,
        int $maxEdge = self::DEFAULT_MAX_EDGE,
        ?string $destination = null,
        int $quality = self::DEFAULT_QUALITY,
    ): ?ResizedImage {
        if ($maxEdge < 1) {
            throw new InvalidArgumentException('The max edge must be at least 1 pixel.');
        }

        if ($quality < 1 || $quality > 100) {
            throw new InvalidArgumentException('The quality must be between 1 and 100.');
        }

        if (! function_exists('nativephp_call')) {
            return null;
        }

        $result = nativephp_call('ImageResize.Resize', json_encode(array_filter([
            'path' => $path,
            'destination' => $destination,
            'maxEdge' => $maxEdge,
            'quality' => $quality,
        ], fn ($value) => $value !== null && $value !== '')));

        $decoded = $result ? json_decode($result, true) : null;

        if (! is_array($decoded)) {
            throw new ImageResizeException('The device returned no response.');
        }

        if (($decoded['status'] ?? null) === 'error') {
            throw new ImageResizeException(
                (string) ($decoded['message'] ?? 'The image could not be resized.'),
                (string) ($decoded['code'] ?? 'resize_failed'),
            );
        }

        $data = $decoded['data'] ?? $decoded;

        // A bridge with nothing behind it (a muted dev bridge, say) answers
        // with an empty success; that wrote nothing, so it isn't a result.
        if (! is_array($data) || empty($data['path'])) {
            throw new ImageResizeException('The device did not return a resized image.');
        }

        return ResizedImage::fromArray($data);
    }
}
