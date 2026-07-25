package com.lenerd46.spotifyplus;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import com.lenerd46.spotifyplus.hooks.SleepTimerHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;

import java.lang.reflect.Method;
import java.util.Collections;

public class SpotifyBottomSheet {
    private final ClassLoader classLoader;
    private final Context context;

    private Object sheet;

    private static Class<?> sheetClass;
    private static Method draggableMethod;

    public SpotifyBottomSheet(ClassLoader classLoader, Context context) {
        this.classLoader = classLoader;
        this.context = context;
    }

    public static void initialize(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            sheetClass = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().methods(MethodsMatcher.create()
                    .add(MethodMatcher.create().name("cancel"))
                    .add(MethodMatcher.create().name("onAttachedToWindow"))
                    .add(MethodMatcher.create().name("onCreate"))
                    .add(MethodMatcher.create().name("onStart"))
                    .add(MethodMatcher.create().name("setCancelable"))
                    .add(MethodMatcher.create().name("setCanceledOnTouchOutside"))
                    .add(MethodMatcher.create().name("setContentView"))
                    .add(MethodMatcher.create().name("setContentView"))
            ))).single().getInstance(classLoader);

            draggableMethod = bridge.findMethod(FindMethod.create().searchInClass(Collections.singletonList(bridge.getClassData(sheetClass))).matcher(MethodMatcher.create().returnType("com.google.android.material.bottomsheet.BottomSheetBehavior"))).single().getMethodInstance(classLoader);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    public void create(View view) {
        int theme = SleepTimerHook.getSpotifyStyle(classLoader, "ModalBottomSheetDialog", 0);
        sheet = XposedHelpers.newInstance(sheetClass, context, theme);

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
        sheet = XposedHelpers.newInstance(sheetClass, context, theme);

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
        try {
            if (sheet == null) return;
            Object behavior = draggableMethod.invoke(sheet);

            try {XposedHelpers.callMethod(behavior, "setDraggable", draggable);} catch (Throwable ignored) {
                try {XposedHelpers.setBooleanField(behavior, "F", draggable);} catch (Throwable throwable) {
                    XposedBridge.log("[SpotifyPlus] Could not change bottom sheet draggable state");
                    XposedBridge.log(throwable);
                }
            }
        } catch (Throwable ignored) {}
    }
}
