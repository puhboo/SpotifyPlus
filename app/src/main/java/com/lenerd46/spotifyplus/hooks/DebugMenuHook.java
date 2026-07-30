package com.lenerd46.spotifyplus.hooks;

import android.app.Activity;
import android.content.Intent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class DebugMenuHook {

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.spotify.music".equals(lpparam.packageName)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.spotify.music.SpotifyMainActivity",
                lpparam.classLoader,
                "onResume",
                new XC_MethodHook() {
                    private boolean hasLaunched = false;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (hasLaunched) return;
                        hasLaunched = true;

                        Activity currentActivity = (Activity) param.thisObject;
                        XposedBridge.log("[SpotifyPlus] Launching RemoteConfigurationDebugActivity internally...");

                        try {
                            // Build an explicit intent using the exact class name found in the Manifest
                            Intent intent = new Intent();
                            intent.setClassName(
                                "com.spotify.music", 
                                "com.spotify.remoteconfig.debugfeature.RemoteConfigurationDebugActivity"
                            );
                            
                            currentActivity.startActivity(intent);
                            XposedBridge.log("[SpotifyPlus] Successfully launched debug activity!");
                        } catch (Throwable e) {
                            XposedBridge.log("[SpotifyPlus] Failed to start debug activity intent: " + e.getMessage());
                        }
                    }
                }
            );

        } catch (Throwable t) {
            XposedBridge.log("[SpotifyPlus] Error in DebugMenuHook: " + t.getMessage());
        }
    }
}
