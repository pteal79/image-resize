<?php

namespace Pteal79\ImageResize;

use Illuminate\Support\ServiceProvider;

class ImageResizeServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(ImageResize::class);
    }
}
