package com.lenerd46.spotifyplus;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import com.lenerd46.spotifyplus.hooks.SleepTimerHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SpotifyBottomSheet {
    private final ClassLoader classLoader;
    private final Context context;

    private Object sheet;

    public SpotifyBottomSheet(ClassLoader classLoader, Context context) {
        this.classLoader = classLoader;
        this.context = context;
    }

    public void create(View view) {
        int theme = SleepTimerHook.getSpotifyStyle(classLoader, "ModalBottomSheetDialog", 0);
        sheet = XposedHelpers.newInstance(XposedHelpers.findClass("p.p08", classLoader), context, theme);

        XposedHelpers.callMethod(sheet, "setContentView", view);
        XposedHelpers.callMethod(sheet, "show");

        Window window = (Window) XposedHelpers.callMethod(sheet, "getWindow");
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        try {
            View bottomSheet = (View) XposedHelpers.getObjectField(sheet, "i"); // p08.design_bottom_sheet
            bottomSheet.setBackgroundColor(Color.TRANSPARENT);
            bottomSheet.setBackground(null);
        } catch (Throwable ignored) {
        }

        try {
            View outer = (View) XposedHelpers.getObjectField(sheet, "g"); // p08 root container
            outer.setBackgroundColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }
    }

    public void create(View view, boolean show) {
        int theme = SleepTimerHook.getSpotifyStyle(classLoader, "ModalBottomSheetDialog", 0);
        sheet = XposedHelpers.newInstance(XposedHelpers.findClass("p.p08", classLoader), context, theme);

        XposedHelpers.callMethod(sheet, "setContentView", view);
        if (show) {
            XposedHelpers.callMethod(sheet, "show");
        }

        Window window = (Window) XposedHelpers.callMethod(sheet, "getWindow");
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        try {
            View bottomSheet = (View) XposedHelpers.getObjectField(sheet, "i"); // p08.design_bottom_sheet
            bottomSheet.setBackgroundColor(Color.TRANSPARENT);
            bottomSheet.setBackground(null);
        } catch (Throwable ignored) {
        }

        try {
            View outer = (View) XposedHelpers.getObjectField(sheet, "g"); // p08 root container
            outer.setBackgroundColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }
    }

    public void dismiss() {
        if (sheet == null) return;
        XposedHelpers.callMethod(sheet, "dismiss");
    }

    public void show() {
        if (sheet == null) return;
        XposedHelpers.callMethod(sheet, "show");
    }

    public void setDraggable(boolean draggable) {
        if (sheet == null) return;
        Object behavior = XposedHelpers.callMethod(sheet, "g");
        try { XposedHelpers.callMethod(behavior, "setDraggable", draggable); } catch (Throwable ignored) { try { XposedHelpers.setBooleanField(behavior, "F", draggable); } catch (Throwable throwable) { XposedBridge.log("[SpotifyPlus] Could not change bottom sheet draggable state"); XposedBridge.log(throwable); } }
    }
}
