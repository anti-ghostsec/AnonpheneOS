/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.privacy;

import android.app.Activity;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/**
 * Android 15 draws apps edge to edge, under the status bar, the action bar and the navigation
 * bar. This pads a screen's content so nothing sits under them, or under the keyboard.
 */
final class Bars {
    private Bars() {}

    static View fit(Activity a, View content) {
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            // The top inset already includes the action bar.
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        return content;
    }
}
