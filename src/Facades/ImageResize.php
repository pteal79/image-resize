<?php

namespace Pteal79\ImageResize\Facades;

use Illuminate\Support\Facades\Facade;
use Pteal79\ImageResize\ResizedImage;

/**
 * @method static ResizedImage|null resize(string $path, int $maxEdge = 1980, ?string $destination = null, int $quality = 80)
 *
 * @see \Pteal79\ImageResize\ImageResize
 */
class ImageResize extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Pteal79\ImageResize\ImageResize::class;
    }
}
