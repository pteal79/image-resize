<?php

namespace Pteal79\ImageResize;

/**
 * The JPEG the device wrote, and the size it came from.
 */
final readonly class ResizedImage
{
    public function __construct(
        public string $path,
        public int $width,
        public int $height,
        public int $originalWidth,
        public int $originalHeight,
        public int $bytes,
        public bool $resized,
    ) {}

    /**
     * @param  array{path?: string, width?: int, height?: int, originalWidth?: int, originalHeight?: int, bytes?: int, resized?: bool}  $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            path: (string) ($data['path'] ?? ''),
            width: (int) ($data['width'] ?? 0),
            height: (int) ($data['height'] ?? 0),
            originalWidth: (int) ($data['originalWidth'] ?? 0),
            originalHeight: (int) ($data['originalHeight'] ?? 0),
            bytes: (int) ($data['bytes'] ?? 0),
            resized: (bool) ($data['resized'] ?? false),
        );
    }
}
