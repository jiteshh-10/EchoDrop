package com.dev.echodrop.util;

import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.dev.echodrop.R;

/**
 * Applies and starts the animated EchoDrop logo on app bars.
 */
public final class ToolbarLogoAnimator {

    private ToolbarLogoAnimator() {
    }

    public static void apply(@NonNull Toolbar toolbar) {
        toolbar.setLogo(R.drawable.anim_toolbar_logo);
        toolbar.setLogoDescription(toolbar.getContext().getString(R.string.content_app_logo));

        final Drawable logo = toolbar.getLogo();
        if (logo instanceof AnimationDrawable) {
            final AnimationDrawable animation = (AnimationDrawable) logo;
            toolbar.post(animation::start);
        } else if (logo instanceof Animatable) {
            toolbar.post(() -> ((Animatable) logo).start());
        }
    }
}