package com.lenerd46.spotifyplus.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XModuleResources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.lenerd46.spotifyplus.ModuleContextWrapper;
import com.lenerd46.spotifyplus.R;
import com.lenerd46.spotifyplus.References;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SleepTimerHook extends SpotifyHook {
    private Object customDuration;
    List<SleepTimerInfo> presets;
    private WeakReference<Activity> lastActivity = new WeakReference<>(null);
    private final Context context;

    public SleepTimerHook(Context context) {
        this.context = context;
    }

    @Override
    protected void hook() {
        Class<?> p3n = XposedHelpers.findClass("p.p3n", lpparm.classLoader);
        Object minutes = Enum.valueOf((Class<Enum>) p3n, "MINUTES");
        SharedPreferences prefs = context.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);

        customDuration = XposedHelpers.callStaticMethod(XposedHelpers.findClass("p.sav", lpparm.classLoader), "r", 0, minutes);

        XposedHelpers.findAndHookMethod("p.w7p0", lpparm.classLoader, "a", XposedHelpers.findClass("p.w7p0", lpparm.classLoader), XposedHelpers.findClass("p.u7p0", lpparm.classLoader), new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                Object w7p0 = methodHookParam.args[0];
                Object u7p0 = methodHookParam.args[1];

                Object d61 = XposedHelpers.getObjectField(w7p0, "a");
                lastActivity = new WeakReference<>((Activity) XposedHelpers.getObjectField(d61, "a"));

                String title = (String) XposedHelpers.getObjectField(u7p0, "a");
                Object selectedTimer = XposedHelpers.getObjectField(u7p0, "b");
                Object thirdArg = XposedHelpers.getObjectField(u7p0, "c");

                Gson gson = new Gson();
                Type type = new TypeToken<List<SleepTimerInfo>>() {
                }.getType();

                presets = gson.fromJson(prefs.getString("custom_sleep_timers", "[{\"value\":5,\"unit\":false},{\"value\":10,\"unit\":false},{\"value\":15,\"unit\":false},{\"value\":30,\"unit\":false},{\"value\":45,\"unit\":false},{\"value\":1,\"unit\":true}]"), type);

                Object list = XposedHelpers.callStaticMethod(XposedHelpers.findClass("p.e1c", lpparm.classLoader), "k");
                Object hours = Enum.valueOf((Class<Enum>) p3n, "HOURS");

                if(prefs.getBoolean("custom_sleep_timers_auto_reorder", true)) {
                    sortSleepTimerPresets(presets);
                }

                for (var preset : presets) {
                    addTimer(list, preset.value, preset.unit ? hours : minutes);
                }

//                addTimer(list, 5, minutes);
//                addTimer(list, 10, minutes);
//                addTimer(list, 15, minutes);
//                addTimer(list, 30, minutes);
//                addTimer(list, 45, minutes);
//                addTimer(list, 1, hours);

                // Add the custom button
                Object addCustomTime = XposedHelpers.newInstance(XposedHelpers.findClass("p.n8p0", lpparm.classLoader), customDuration);
                XposedHelpers.callMethod(list, "add", addCustomTime);

                // This is the end of track option
                Object o8p0 = XposedHelpers.newInstance(XposedHelpers.findClass("p.o8p0", lpparm.classLoader), selectedTimer);
                XposedHelpers.callMethod(list, "add", o8p0);

                // Check if there is a timer selected
                Object f7p0 = XposedHelpers.getObjectField(w7p0, "b");
                boolean shouldAddExtra = (boolean) XposedHelpers.callMethod(f7p0, "d");

                if (shouldAddExtra) {
                    // Add the option to turn it off
                    Object m8p0a = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("p.m8p0", lpparm.classLoader), "a");
                    XposedHelpers.callMethod(list, "add", m8p0a);
                }

                Object finalList = XposedHelpers.callStaticMethod(XposedHelpers.findClass("p.e1c", lpparm.classLoader), "h", list);
                return XposedHelpers.newInstance(XposedHelpers.findClass("p.v7p0", lpparm.classLoader), title, selectedTimer, thirdArg, finalList);
            }
        });

        XposedHelpers.findAndHookMethod("p.ndj0", lpparm.classLoader, "invoke", Object.class, Object.class, Object.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (XposedHelpers.getIntField(param.thisObject, "a") != 24) return;

                Object l7p0 = XposedHelpers.getObjectField(param.thisObject, "c");
                Object rowDuration = XposedHelpers.getObjectField(l7p0, "b");
                if (rowDuration != customDuration) return;

                Object qud = param.args[1];

                XposedHelpers.callStaticMethod(XposedHelpers.findClass("p.b001", lpparm.classLoader), "c", "Enter custom amount", null, null, 0L, null, null, 0, false, XposedHelpers.newInstance(XposedHelpers.findClass("p.an00", lpparm.classLoader), 1), 0, null, qud, 0, 0, 1790);
                param.setResult(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("p.fev0", lpparm.classLoader), "a"));
            }
        });

        XposedHelpers.findAndHookMethod("p.dea0", lpparm.classLoader, "invokeSuspend", Object.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (XposedHelpers.getIntField(param.thisObject, "a") != 13) return;

                Object k7p0 = XposedHelpers.getObjectField(param.thisObject, "b");
                Object n8p0 = XposedHelpers.getObjectField(k7p0, "a");
                Object rowDuration = XposedHelpers.getObjectField(n8p0, "a");
                if (rowDuration != customDuration) return;

                Object m7p0 = XposedHelpers.getObjectField(param.thisObject, "d");
                Object f7p0 = XposedHelpers.getObjectField(m7p0, "a");
                Object snackbarCallback = XposedHelpers.getObjectField(k7p0, "c");

                showCustomTimerSheet(lpparm.classLoader, f7p0, snackbarCallback);

                param.setResult(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("p.fev0", lpparm.classLoader), "a"));
            }
        });
    }

    private void addTimer(Object list, int amount, Object unit) {
        Object duration = XposedHelpers.callStaticMethod(XposedHelpers.findClass("p.sav", lpparm.classLoader), "r", amount, unit);
        Object item = XposedHelpers.newInstance(XposedHelpers.findClass("p.n8p0", lpparm.classLoader), duration);
        XposedHelpers.callMethod(list, "add", item);
    }

    private void showCustomTimerSheet(ClassLoader cl, Object f7p0, Object snackbarCallback) {
        Activity activity = lastActivity.get();
        if (activity == null) return;

        activity.runOnUiThread(() -> {
            int theme = getSpotifyStyle(cl, "ModalBottomSheetDialog", 0);

            Object sheet = XposedHelpers.newInstance(
                    XposedHelpers.findClass("p.p08", cl),
                    activity,
                    theme
            );

            XModuleResources modResources = References.modResources;
            int themeOverlayLast = R.style.Theme_SpotifyPlus;
            Context themedCtxLast = new ModuleContextWrapper(activity.getApplicationContext(), themeOverlayLast, modResources, ModuleContextWrapper.class.getClassLoader());
            LayoutInflater inflaterLast = LayoutInflater.from(activity.getApplicationContext()).cloneInContext(themedCtxLast);
            View root = inflaterLast.inflate(modResources.getIdentifier("sleep_timer_sheet", "layout", "com.lenerd46.spotifyplus"), null, false);

            TextInputEditText input = root.findViewById(modResources.getIdentifier("input_sleep_timer_amount", "id", "com.lenerd46.spotifyplus"));
            MaterialButtonToggleGroup unit = root.findViewById(modResources.getIdentifier("sleep_timer_unit_toggle", "id", "com.lenerd46.spotifyplus"));
            MaterialSwitch save = root.findViewById(modResources.getIdentifier("switch_sleep_timer_save", "id", "com.lenerd46.spotifyplus"));
            MaterialButton confirm = root.findViewById(modResources.getIdentifier("btn_confirm_sleep_timer_custom", "id", "com.lenerd46.spotifyplus"));
            MaterialButton cancel = root.findViewById(modResources.getIdentifier("btn_cancel_sleep_timer_custom", "id", "com.lenerd46.spotifyplus"));

            confirm.setOnClickListener(v -> {
                String raw = input.getText().toString().trim();
                if (raw.isEmpty()) return;

                long time = Long.parseLong(raw);
                if (time <= 0) return;

                if (!(boolean) XposedHelpers.callMethod(f7p0, "c")) return;

                boolean hours = unit.getCheckedButtonId() == modResources.getIdentifier("btn_sleep_timer_minutes", "id", "com.lenerd46.spotifyplus");
                long millis = hours ? TimeUnit.MINUTES.toMillis(time) : TimeUnit.HOURS.toMillis(time);
                Object rbt0 = XposedHelpers.newInstance(XposedHelpers.findClass("p.rbt0", lpparm.classLoader), millis);

                Object durationController = XposedHelpers.getObjectField(f7p0, "a");
                XposedHelpers.callMethod(durationController, "c", rbt0);

                if (save.isChecked()) {
                    presets.add(new SleepTimerInfo((int) time, !hours));

                    Gson gson = new Gson();
                    String json = gson.toJson(presets);

                    activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE).edit().putString("custom_sleep_timers", json).apply();
                }

                XposedHelpers.callMethod(sheet, "dismiss");
                showTimerSetMessage(lpparm.classLoader, snackbarCallback);
                activity.onBackPressed();
            });

            cancel.setOnClickListener(v -> {
                XposedHelpers.callMethod(sheet, "dismiss");
            });

            XposedHelpers.callMethod(sheet, "setContentView", root);
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

            input.requestFocus();
            ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    public static int getSpotifyStyle(ClassLoader cl, String name, int fallback) {
        try {
            return XposedHelpers.getStaticIntField(
                    XposedHelpers.findClass("com.spotify.music.R$style", cl),
                    name
            );
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void showTimerSetMessage(ClassLoader cl, Object callback) {
        try {
            int messageId = XposedHelpers.getStaticIntField(
                    XposedHelpers.findClass("com.spotify.music.R$string", cl),
                    "context_menu_sleep_timer_select_message"
            );

            Object emp0 = XposedHelpers.newInstance(
                    XposedHelpers.findClass("p.emp0", cl),
                    null,
                    "",
                    Integer.valueOf(messageId),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false
            );

            XposedHelpers.callMethod(callback, "invoke", emp0);
        } catch (Throwable ignored) {
            // Timer is already set; this is only the stock confirmation message.
        }
    }

    public static class SleepTimerInfo {
        public int value;
        public boolean unit;

        public SleepTimerInfo() {
        }

        public SleepTimerInfo(int value, boolean unit) {
            this.value = value;
            this.unit = unit;
        }

        String getTitle() {
            return value + " " + (value == 1 ? (unit ? "hour" : "minute") : unit ? "hours" : "minutes");
        }
    }

    private void sortSleepTimerPresets(List<SleepTimerInfo> presets) {
        presets.sort(Comparator.comparingLong(this::getSleepTimerDurationMillis));
    }

    private long getSleepTimerDurationMillis(SleepTimerHook.SleepTimerInfo preset) {
        return preset.unit ? java.util.concurrent.TimeUnit.HOURS.toMillis(preset.value) : java.util.concurrent.TimeUnit.MINUTES.toMillis(preset.value);
    }
}