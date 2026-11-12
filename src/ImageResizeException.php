<?php

namespace Pteal79\ImageResize;

use RuntimeException;

/**
 * The device could not resize an image. `errorCode` is the native side's code:
 * missing_path, not_found, unreadable, write_failed or resize_failed.
 */
class ImageResizeException extends RuntimeException
{
    public function __construct(string $message, public readonly string $errorCode = 'resize_failed')
    {
        parent::__construct($message);
    }
}
