package com.lenerd46.spotifyplus.hooks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class PrivateSessionHook extends SpotifyHook {
    private static final long REFRESH_DELAY = 5L * 60L * 60L * 1000L + 45L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private Object privateSessionKey;
    private Object settingsRepository;
    private Runnable refreshPrivateSession;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    public PrivateSessionHook(Context context) {
        prefs = context.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
    }

    @Override
    protected void hook() {
        try {
            privateSessionKey = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("p.i4l0", lpparm.classLoader), "d");

            refreshPrivateSession = () -> {
                handler.removeCallbacks(refreshPrivateSession);
                if (!prefs.getBoolean("private_session", false)
                        || settingsRepository == null
                        || privateSessionKey == null) {
                    return;
                }

                try {
                    Object update = XposedHelpers.callMethod(
                            settingsRepository, "c", privateSessionKey, Boolean.TRUE);
                    XposedHelpers.callMethod(update, "subscribe");
                    handler.removeCallbacks(refreshPrivateSession);
                    handler.postDelayed(refreshPrivateSession, REFRESH_DELAY);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                    handler.removeCallbacks(refreshPrivateSession);
                    handler.postDelayed(refreshPrivateSession, 60_000L);
                }
            };

            preferenceListener = (sharedPreferences, key) -> {
                if (!"private_session".equals(key)) return;

                handler.removeCallbacks(refreshPrivateSession);
                if (sharedPreferences.getBoolean(key, false)) {
                    handler.post(refreshPrivateSession);
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener);

            XposedBridge.hookAllConstructors(
                    XposedHelpers.findClass("p.n4l0", lpparm.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            settingsRepository = param.thisObject;
                            handler.post(refreshPrivateSession);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass("p.n4l0", lpparm.classLoader),
                    "c",
                    XposedHelpers.findClass("p.j4l0", lpparm.classLoader),
                    Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!prefs.getBoolean("private_session", false)
                                    || param.args[0] != privateSessionKey
                                    || !Boolean.FALSE.equals(param.args[1])) {
                                return;
                            }

                            param.args[1] = Boolean.TRUE;
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!prefs.getBoolean("private_session", false)
                                    || param.args[0] != privateSessionKey
                                    || !Boolean.TRUE.equals(param.args[1])) {
                                return;
                            }

                            handler.removeCallbacks(refreshPrivateSession);
                            handler.postDelayed(refreshPrivateSession, REFRESH_DELAY);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    "com.spotify.settings.esperanto.proto.SettingsOuterClass$SettingsState",
                    lpparm.classLoader,
                    "K",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!prefs.getBoolean("private_session", false)) return;

                            if (Boolean.FALSE.equals(param.getResult())) {
                                handler.removeCallbacks(refreshPrivateSession);
                                handler.postDelayed(refreshPrivateSession, 750L);
                            }
                            param.setResult(Boolean.TRUE);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    "p.s7n0",
                    lpparm.classLoader,
                    "onIncognitoModeDisabledByTimer",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!prefs.getBoolean("private_session", false)) return;

                            handler.removeCallbacks(refreshPrivateSession);
                            handler.postDelayed(refreshPrivateSession, 250L);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
