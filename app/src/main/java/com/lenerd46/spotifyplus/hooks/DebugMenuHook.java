package com.lenerd46.spotifyplus.hooks;

import android.app.Activity;
import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class DebugMenuHook extends SpotifyHook {

    private boolean hasLaunched = false;

    @Override
    protected void hook() {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.spotify.music.SpotifyMainActivity",
                    lpparm.classLoader,
                    "onResume",
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (hasLaunched)
                                return;

                            hasLaunched = true;

                            Activity activity = (Activity) param.thisObject;

                            XposedBridge.log("[SpotifyPlus] Launching RemoteConfigurationDebugActivity...");

                            try {
                                Intent intent = new Intent();
                                intent.setClassName(
                                        "com.spotify.music",
                                        "com.spotify.remoteconfig.debugfeature.RemoteConfigurationDebugActivity");

                                activity.startActivity(intent);

                                XposedBridge.log("[SpotifyPlus] Debug activity launched.");
                            } catch (Throwable e) {
                                XposedBridge.log("[SpotifyPlus] Failed to launch debug activity:");
                                XposedBridge.log(e);
                            }
                        }
                    });

        } catch (Throwable t) {
            XposedBridge.log("[SpotifyPlus] DebugMenuHook error:");
            XposedBridge.log(t);
        }
    }
}
