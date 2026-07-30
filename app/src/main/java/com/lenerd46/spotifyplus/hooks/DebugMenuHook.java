package com.lenerd46.spotifyplus.hooks;

import android.app.Activity;
import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class DebugMenuHook extends SpotifyHook {

    private boolean launched = false;

    @Override
    protected void hook() {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.spotify.music.SpotifyMainActivity",
                    lpparm.classLoader,
                    "onResume",
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (launched)
                                return;

                            launched = true;

                            try {
                                Activity activity = (Activity) param.thisObject;

                                Intent intent = new Intent();
                                intent.setClassName(
                                        "com.spotify.music",
                                        "com.spotify.remoteconfig.debugfeature.RemoteConfigurationDebugActivity");

                                activity.startActivity(intent);

                                XposedBridge.log("[SpotifyPlus] RemoteConfigurationDebugActivity launched.");
                            } catch (Throwable t) {
                                XposedBridge.log("[SpotifyPlus] Failed to launch debug activity.");
                                XposedBridge.log(t);
                            }
                        }
                    });

        } catch (Throwable t) {
            XposedBridge.log("[SpotifyPlus] Failed to install DebugMenuHook.");
            XposedBridge.log(t);
        }
    }
}
