package com.lenerd46.spotifyplus.hooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.callbacks.XC_LayoutInflated;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ThemeHook extends SpotifyHook {

    private static final String TAG = "SpotifyPlus/ThemeHook: ";
    private final SharedPreferences prefs;
    private static volatile boolean themeEnabled = true;
    private static volatile Long originalHomeFilterChipColor;

    private volatile int lastArtworkColor;

    public static volatile int BACKGROUND = 0xFF121212;
    public static volatile int BACKGROUND_HIGHLIGHT = 0xFF1F1F1F;
    public static volatile int BACKGROUND_PRESS = 0xFF000000;

    public static volatile int SURFACE = 0xFF1F1F1F;
    public static volatile int SURFACE_HIGHLIGHT = 0xFF2A2A2A;
    public static volatile int SURFACE_PRESS = 0xFF191919;

    public static volatile int TINTED = 0x1AFFFFFF;
    public static volatile int TINTED_HIGHLIGHT = 0x24FFFFFF;
    public static volatile int TINTED_PRESS = 0x36FFFFFF;

    public static volatile int TEXT = 0xFFFFFFFF;
    public static volatile int TEXT_SUBDUED = 0xFFB3B3B3;

    public static volatile int ACCENT = 0xFF1ED760;
    public static volatile int ACCENT_HIGHLIGHT = 0xFF3BE477;
    public static volatile int ACCENT_PRESS = 0xFF1ABC54;

    public static volatile int ANNOUNCEMENT = 0xFF539DF5;
    public static volatile int DECORATIVE = 0xFFFFFFFF;
    public static volatile int DECORATIVE_SUBDUED = 0xFF292929;

    public static volatile int NEGATIVE = 0xFFED2C3F;
    public static volatile int WARNING = 0xFFFFA42B;
    public static volatile int POSITIVE = 0xFF1ED760;
    public static final int WHITE = 0xFFFFFFFF;
    public static volatile int SCRIM = 0xFF000000;
    public static volatile int ON_ACCENT = Color.BLACK;

    private static final Map<Object, Object> encoreCache = new IdentityHashMap<>();
    private static final Map<Object, Object> material3Cache = new IdentityHashMap<>();
    private static final Map<Object, Boolean> composeHosts = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Activity, Integer> activityPaletteGenerations = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile int paletteGeneration;

    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger artworkRequestGeneration = new AtomicInteger();
    private final LruCache<String, Integer> artworkColorCache = new LruCache<>(64);

    private volatile String lastArtworkUrl;
    private final Handler paletteHandler = new Handler(Looper.getMainLooper());

    private static WeakReference<Activity> visibleActivity = new WeakReference<>(null);

    private int testPaletteIndex = 0;

    public ThemeHook(Context context) {
        prefs = context.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        if (prefs.getBoolean("theme_palette_saved", false)) loadCustomTheme(prefs);
        themeEnabled = prefs.getBoolean("theme_enabled", false);
        ThemeResourcesHook.setEnabled(themeEnabled);
        ThemeResourcesHook.applyPalette(BACKGROUND, SURFACE, TINTED, TEXT, TEXT_SUBDUED, ACCENT, ON_ACCENT);
    }

    public static void setThemeEnabled(boolean enabled, ClassLoader classLoader) {
        themeEnabled = enabled;
        synchronized (encoreCache) {encoreCache.clear();}
        synchronized (material3Cache) {material3Cache.clear();}
        ThemeResourcesHook.setEnabled(enabled);
        updateHomeFilterChipColor(classLoader);
        paletteGeneration++;
        refreshComposeHosts();
        recreateVisibleActivity();
    }

    public static void applyCustomTheme(SharedPreferences prefs, ClassLoader classLoader) {
        prefs.edit().putBoolean("theme_palette_saved", true).apply();
        loadCustomTheme(prefs);
        onPaletteChanged(classLoader);
        recreateVisibleActivity();
    }

    private static void loadCustomTheme(SharedPreferences prefs) {
        BACKGROUND = prefs.getInt("theme_background", 0xFF121212);
        BACKGROUND_HIGHLIGHT = prefs.getInt("theme_background_highlight", 0xFF1F1F1F);
        BACKGROUND_PRESS = prefs.getInt("theme_background_press", 0xFF000000);
        SURFACE = prefs.getInt("theme_surface", 0xFF1F1F1F);
        SURFACE_HIGHLIGHT = prefs.getInt("theme_surface_highlight", 0xFF2A2A2A);
        SURFACE_PRESS = prefs.getInt("theme_surface_press", 0xFF191919);
        TINTED = prefs.getInt("theme_tinted", 0x1AFFFFFF);
        TINTED_HIGHLIGHT = prefs.getInt("theme_tinted_highlight", 0x24FFFFFF);
        TINTED_PRESS = prefs.getInt("theme_tinted_press", 0x36FFFFFF);
        TEXT = prefs.getInt("theme_text", 0xFFFFFFFF);
        TEXT_SUBDUED = prefs.getInt("theme_text_subdued", 0xFFB3B3B3);
        ACCENT = prefs.getInt("theme_accent", 0xFF1ED760);
        ACCENT_HIGHLIGHT = prefs.getInt("theme_accent_highlight", 0xFF3BE477);
        ACCENT_PRESS = prefs.getInt("theme_accent_press", 0xFF1ABC54);
        ANNOUNCEMENT = prefs.getInt("theme_announcement", 0xFF539DF5);
        DECORATIVE = prefs.getInt("theme_decorative", 0xFFFFFFFF);
        DECORATIVE_SUBDUED = prefs.getInt("theme_decorative_subdued", 0xFF292929);
        NEGATIVE = prefs.getInt("theme_negative", 0xFFED2C3F);
        WARNING = prefs.getInt("theme_warning", 0xFFFFA42B);
        POSITIVE = prefs.getInt("theme_positive", 0xFF1ED760);
        SCRIM = prefs.getInt("theme_scrim", 0xFF000000);
        ON_ACCENT = prefs.getInt("theme_on_accent", 0xFF000000);
    }

    @Override
    protected void hook() {
        hookComposeHosts();
        hookEncorePalette();
        hookMaterial3Palette();

        hookSideDrawerBackground();
        hookHomeShortcutCards();
        patchHomeFilterChipColor();
        hookArtworkGradients();

        hookSpotifyConnectColor();
        hookCreativeWorkAlbumHeader();
        hookAlbumTrackRows();
        hookBottomNavigationGradient();

        hookVisibleActivity();

        hookTrackArtworkColor();
    }

    private void hookEncorePalette() {
        try {
            ClassLoader classLoader = lpparm.classLoader;
            Class<?> composerClass = XposedHelpers.findClass("p.qud", classLoader);

            XposedHelpers.findAndHookMethod("p.rjo", classLoader, "a", composerClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object original = param.getResult();
                            if (original == null) {
                                return;
                            }

                            try {
                                param.setResult(recolorEncorePalette(original));
                            } catch (Throwable throwable) {
                                XposedBridge.log(TAG + "Failed to recolor Encore palette");
                                XposedBridge.log(throwable);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + "Encore palette hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not install Encore palette hook");
            XposedBridge.log(throwable);
        }
    }

    private Object recolorEncorePalette(Object original) {
        if (!themeEnabled) return original;
        synchronized (encoreCache) {
            Object cached = encoreCache.get(original);
            if (cached != null) {
                return cached;
            }

            Object oldBackgrounds = XposedHelpers.getObjectField(original, "a");
            int originalBase = unpackColor(XposedHelpers.getLongField(oldBackgrounds, "c"));

            final Object replacement;

            switch (originalBase) {
                /*
                 * Standard Encore Base palette.
                 */
                case 0xFF121212:
                    replacement = createLightEncorePalette(original, BACKGROUND, BACKGROUND_HIGHLIGHT, BACKGROUND_PRESS);
                    break;

                /*
                 * Encore Elevated palette. Spotify derives this from Base for
                 * cards, dialogs, sheets, and elevated containers.
                 */
                case 0xFF1F1F1F:
                    replacement = createLightEncorePalette(
                            original,
                            SURFACE,
                            SURFACE_HIGHLIGHT,
                            SURFACE_PRESS
                    );
                    break;

                /*
                 * Encore Tinted palette. The original value is 10% white.
                 */
                case 0x1AFFFFFF:
                    replacement = createLightEncorePalette(
                            original,
                            TINTED,
                            TINTED_HIGHLIGHT,
                            TINTED_PRESS
                    );
                    break;

                /*
                 * Spotify's green BrightAccent palette. This controls many
                 * primary buttons and highlighted controls.
                 */
                case 0xFF1ED760:
                    replacement = createBlueAccentPalette(original);
                    break;

                /*
                 * Preserve Negative, Warning, Positive, Announcement,
                 * OverMedia, and dynamic artwork palettes.
                 */
                default:
                    replacement = original;
                    break;
            }

            encoreCache.put(original, replacement);
            return replacement;
        }
    }

    private Object createLightEncorePalette(
            Object original,
            int mainBackground,
            int mainHighlight,
            int mainPress
    ) {
        Object oldBackgrounds =
                XposedHelpers.getObjectField(original, "a");
        Object oldElevated =
                XposedHelpers.getObjectField(oldBackgrounds, "a");
        Object oldTinted =
                XposedHelpers.getObjectField(oldBackgrounds, "b");

        Object oldText =
                XposedHelpers.getObjectField(original, "b");
        Object oldEssential =
                XposedHelpers.getObjectField(original, "c");
        Object oldDecorative =
                XposedHelpers.getObjectField(original, "d");

        Object elevated = XposedHelpers.newInstance(
                oldElevated.getClass(),
                packColor(SURFACE),
                packColor(SURFACE_HIGHLIGHT),
                packColor(SURFACE_PRESS)
        );

        Object tinted = XposedHelpers.newInstance(
                oldTinted.getClass(),
                packColor(TINTED),
                packColor(TINTED_HIGHLIGHT),
                packColor(TINTED_PRESS)
        );

        Object backgrounds = XposedHelpers.newInstance(
                oldBackgrounds.getClass(),
                elevated,
                tinted,
                packColor(mainBackground),
                packColor(mainHighlight),
                packColor(mainPress)
        );

        /*
         * e3p:
         * base, subdued, brightAccent, negative, warning, positive,
         * announcement
         */
        Object text = XposedHelpers.newInstance(
                oldText.getClass(),
                packColor(TEXT),
                packColor(TEXT_SUBDUED),
                packColor(ACCENT),
                packColor(NEGATIVE),
                packColor(WARNING),
                packColor(POSITIVE),
                packColor(ANNOUNCEMENT)
        );

        /*
         * epo: essential/icon colors in the same semantic order.
         */
        Object essential = XposedHelpers.newInstance(
                oldEssential.getClass(),
                packColor(TEXT),
                packColor(0xFF5E7A91),
                packColor(ACCENT),
                packColor(0xFFC0364B),
                packColor(0xFFB67800),
                packColor(0xFF0A8F69),
                packColor(ANNOUNCEMENT)
        );

        /*
         * qoo: decorative base and decorative subdued.
         */
        Object decorative = XposedHelpers.newInstance(
                oldDecorative.getClass(),
                packColor(DECORATIVE),
                packColor(DECORATIVE_SUBDUED)
        );

        return XposedHelpers.newInstance(
                original.getClass(),
                backgrounds,
                text,
                essential,
                decorative
        );
    }

    private Object createBlueAccentPalette(Object original) {
        Object oldBackgrounds =
                XposedHelpers.getObjectField(original, "a");
        Object oldElevated =
                XposedHelpers.getObjectField(oldBackgrounds, "a");
        Object oldTinted =
                XposedHelpers.getObjectField(oldBackgrounds, "b");

        Object oldText =
                XposedHelpers.getObjectField(original, "b");
        Object oldEssential =
                XposedHelpers.getObjectField(original, "c");
        Object oldDecorative =
                XposedHelpers.getObjectField(original, "d");

        Object elevated = XposedHelpers.newInstance(
                oldElevated.getClass(),
                packColor(ACCENT),
                packColor(ACCENT_HIGHLIGHT),
                packColor(ACCENT_PRESS)
        );

        Object tinted = XposedHelpers.newInstance(
                oldTinted.getClass(),
                packColor(ACCENT),
                packColor(ACCENT_HIGHLIGHT),
                packColor(ACCENT_PRESS)
        );

        Object backgrounds = XposedHelpers.newInstance(
                oldBackgrounds.getClass(),
                elevated,
                tinted,
                packColor(ACCENT),
                packColor(ACCENT_HIGHLIGHT),
                packColor(ACCENT_PRESS)
        );

        /*
         * Content displayed on the blue accent uses white or lightly tinted
         * white to maintain contrast.
         */
        Object text = XposedHelpers.newInstance(
                oldText.getClass(),
                packColor(ON_ACCENT),
                packColor(0xFFD8F1FF),
                packColor(ON_ACCENT),
                packColor(0xFFFFE1E6),
                packColor(0xFFFFF0C2),
                packColor(0xFFD5FFED),
                packColor(0xFFDCEEFF)
        );

        Object essential = XposedHelpers.newInstance(
                oldEssential.getClass(),
                packColor(ON_ACCENT),
                packColor(0xFFD8F1FF),
                packColor(ON_ACCENT),
                packColor(0xFFFFE1E6),
                packColor(0xFFFFF0C2),
                packColor(0xFFD5FFED),
                packColor(0xFFDCEEFF)
        );

        Object decorative = XposedHelpers.newInstance(
                oldDecorative.getClass(),
                packColor(ON_ACCENT),
                packColor(0xFFB9E5FA)
        );

        return XposedHelpers.newInstance(
                original.getClass(),
                backgrounds,
                text,
                essential,
                decorative
        );
    }

    /**
     * Material 3 does not read rjo.a(). Spotify converts Encore into an f3c
     * ColorScheme and installs it through s540.a(), so it needs a second hook.
     */
    private void hookMaterial3Palette() {
        try {
            ClassLoader classLoader = lpparm.classLoader;

            Class<?> colorSchemeClass =
                    XposedHelpers.findClass("p.f3c", classLoader);
            Class<?> shapesClass =
                    XposedHelpers.findClass("p.hbn0", classLoader);
            Class<?> typographyClass =
                    XposedHelpers.findClass("p.a3v0", classLoader);
            Class<?> contentClass =
                    XposedHelpers.findClass("p.jpc", classLoader);
            Class<?> composerClass =
                    XposedHelpers.findClass("p.qud", classLoader);

            XposedHelpers.findAndHookMethod(
                    "p.s540",
                    classLoader,
                    "a",
                    colorSchemeClass,
                    shapesClass,
                    typographyClass,
                    contentClass,
                    composerClass,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object original = param.args[0];
                            if (original == null) {
                                return;
                            }

                            try {
                                param.args[0] = recolorMaterial3Palette(original);
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + "Failed to recolor Material 3 palette"
                                );
                                XposedBridge.log(throwable);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + "Material 3 palette hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not install Material 3 palette hook");
            XposedBridge.log(throwable);
        }
    }

    private Object recolorMaterial3Palette(Object original) {
        if (!themeEnabled) return original;
        synchronized (material3Cache) {
            Object cached = material3Cache.get(original);
            if (cached != null) {
                return cached;
            }

            /*
             * f3c.n is the Material background role. Only replace Spotify's
             * normal dark scheme, preserving unrelated dynamic/light schemes.
             */
            int originalBackground = unpackColor(
                    XposedHelpers.getLongField(original, "n")
            );

            if (originalBackground != 0xFF121212) {
                material3Cache.put(original, original);
                return original;
            }

            /*
             * f3c constructor order:
             *
             * primary, onPrimary, primaryContainer, onPrimaryContainer,
             * inversePrimary, secondary, onSecondary, secondaryContainer,
             * onSecondaryContainer, tertiary, onTertiary, tertiaryContainer,
             * onTertiaryContainer, background, onBackground, surface,
             * onSurface, surfaceVariant, onSurfaceVariant, surfaceTint,
             * inverseSurface, inverseOnSurface, error, onError,
             * errorContainer, onErrorContainer, outline, outlineVariant,
             * scrim, surfaceBright, surfaceDim, surfaceContainer,
             * surfaceContainerHigh, surfaceContainerHighest,
             * surfaceContainerLow, surfaceContainerLowest
             */
            Object replacement = XposedHelpers.newInstance(
                    original.getClass(),
                    packColor(ACCENT),                 // primary
                    packColor(ON_ACCENT),                  // onPrimary
                    packColor(0xFFCBEAFF),             // primaryContainer
                    packColor(0xFF082F49),             // onPrimaryContainer
                    packColor(0xFF7DD3FC),             // inversePrimary

                    packColor(0xFF476F85),             // secondary
                    packColor(ON_ACCENT),                  // onSecondary
                    packColor(0xFFD6EFFC),             // secondaryContainer
                    packColor(0xFF163746),             // onSecondaryContainer

                    packColor(0xFF5E5A92),             // tertiary
                    packColor(ON_ACCENT),                  // onTertiary
                    packColor(0xFFE5DFFF),             // tertiaryContainer
                    packColor(0xFF2B2857),             // onTertiaryContainer

                    packColor(BACKGROUND),             // background
                    packColor(TEXT),                   // onBackground
                    packColor(SURFACE),                // surface
                    packColor(TEXT),                   // onSurface
                    packColor(0xFFDCECF5),             // surfaceVariant
                    packColor(0xFF3C5668),             // onSurfaceVariant
                    packColor(ACCENT),                 // surfaceTint

                    packColor(0xFF233A4A),             // inverseSurface
                    packColor(0xFFE9F5FB),             // inverseOnSurface

                    packColor(0xFFBA1A1A),             // error
                    packColor(ON_ACCENT),                  // onError
                    packColor(0xFFFFDAD6),             // errorContainer
                    packColor(0xFF410002),             // onErrorContainer

                    packColor(0xFF6F8795),             // outline
                    packColor(0xFFBFCCD4),             // outlineVariant
                    packColor(SCRIM),                  // scrim

                    packColor(ON_ACCENT),                  // surfaceBright
                    packColor(0xFFCFDDE5),             // surfaceDim
                    packColor(0xFFEDF7FC),             // surfaceContainer
                    packColor(0xFFE4F1F7),             // surfaceContainerHigh
                    packColor(0xFFD9EAF2),             // surfaceContainerHighest
                    packColor(0xFFF4FAFD),             // surfaceContainerLow
                    packColor(ON_ACCENT)                   // surfaceContainerLowest
            );

            material3Cache.put(original, replacement);
            return replacement;
        }
    }

    private void hookSideDrawerBackground() {
        try {
            ClassLoader classLoader = lpparm.classLoader;

            Class<?> contextClass =
                    XposedHelpers.findClass("p.cpt", classLoader);
            Class<?> dependency1 =
                    XposedHelpers.findClass("p.ljo0", classLoader);
            Class<?> dependency2 =
                    XposedHelpers.findClass("p.pob0", classLoader);
            Class<?> dependency3 =
                    XposedHelpers.findClass("p.k1j", classLoader);

            XposedHelpers.findAndHookConstructor(
                    "p.cjo0",
                    classLoader,
                    contextClass,
                    dependency1,
                    dependency2,
                    dependency3,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                /*
                                 * u1 is the actual visible drawer container.
                                 * t1 is the outer DrawerLayout/scrim container.
                                 */
                                FrameLayout drawer = (FrameLayout)
                                        XposedHelpers.getObjectField(
                                                param.thisObject,
                                                "u1"
                                        );

                                drawer.setBackgroundColor(SURFACE);
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + "Failed to recolor side drawer"
                                );
                                XposedBridge.log(throwable);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + "Side drawer hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not install side drawer hook");
            XposedBridge.log(throwable);
        }
    }

    private void hookHomeShortcutCards() {
        try {
            ClassLoader classLoader = lpparm.classLoader;

            Class<?> callbackClass =
                    XposedHelpers.findClass("p.wyt", classLoader);
            Class<?> modelClass =
                    XposedHelpers.findClass("p.u4o0", classLoader);
            Class<?> dependencyClass =
                    XposedHelpers.findClass("p.n1x", classLoader);

            /*
             * Current constructor:
             *
             * p5k0(wyt, u4o0, Context, n1x)
             *
             * It inflates shortcut_card.xml.
             */
            XposedHelpers.findAndHookConstructor(
                    "p.p5k0",
                    classLoader,
                    callbackClass,
                    modelClass,
                    Context.class,
                    dependencyClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                /*
                                 * p5k0.c is the generated shortcut-card holder.
                                 *
                                 * holder.i = root CardView
                                 * holder.c = title TextView
                                 */
                                Object holder =
                                        XposedHelpers.getObjectField(
                                                param.thisObject,
                                                "c"
                                        );

                                Object card =
                                        XposedHelpers.getObjectField(holder, "i");

                                TextView title = (TextView)
                                        XposedHelpers.getObjectField(holder, "c");

                                /*
                                 * Avoid a nearly invisible white-on-white card.
                                 * A pale blue card also separates each shortcut
                                 * from the page background.
                                 */
                                XposedHelpers.callMethod(
                                        card,
                                        "setCardBackgroundColor",
                                        TINTED
                                );

                                title.setTextColor(TEXT);
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + "Failed to recolor Home shortcut"
                                );
                                XposedBridge.log(throwable);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + "Home shortcut hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not install Home shortcut hook");
            XposedBridge.log(throwable);
        }
    }

    private void patchHomeFilterChipColor() {
        updateHomeFilterChipColor(lpparm.classLoader);
    }

    private static void updateHomeFilterChipColor(ClassLoader classLoader) {
        try {
            Class<?> homeFilterChipClass = XposedHelpers.findClass("p.b2w", classLoader);

            /*
             * b2w.b is the hard-coded #FF333333 unselected-chip color.
             * It is initialized through inh.f(), so it is not a compile-time
             * constant and can be changed after class loading.
             */
            if (originalHomeFilterChipColor == null) originalHomeFilterChipColor = XposedHelpers.getStaticLongField(homeFilterChipClass, "b");
            XposedHelpers.setStaticLongField(homeFilterChipClass, "b", themeEnabled ? packColor(TINTED) : originalHomeFilterChipColor);

            XposedBridge.log(TAG + "Home filter-chip color patched");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not patch Home filter-chip color");
            XposedBridge.log(throwable);
        }
    }

    private void hookArtworkGradients() {
        hookNowPlayingGradient();
        hookAlbumHeaderGradient();
    }

    private void hookNowPlayingGradient() {
        try {
            Class<?> gradientViewClass = XposedHelpers.findClass(
                    "com.spotify.nowplaying.uiusecases.overlay.OverlayHidingGradientBackgroundView",
                    lpparm.classLoader
            );

            XposedHelpers.findAndHookConstructor(
                    gradientViewClass,
                    Context.class,
                    AttributeSet.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                GradientDrawable gradient =
                                        (GradientDrawable) XposedHelpers.getObjectField(
                                                param.thisObject,
                                                "Q0"
                                        );

                                /*
                                 * Spotify later places the artwork-extracted color
                                 * behind this drawable using PorterDuff DST_OVER.
                                 *
                                 * Keep that color through most of the screen, then
                                 * gently fade into our light-blue page background.
                                 */
                                int[] colors = {
                                        Color.TRANSPARENT,
                                        Color.TRANSPARENT,
                                        BACKGROUND
                                };

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    gradient.setColors(
                                            colors,
                                            new float[]{
                                                    0.00f,
                                                    0.88f,
                                                    1.00f
                                            }
                                    );
                                } else {
                                    // Approximate the delayed transition on older Android.
                                    gradient.setColors(new int[]{
                                            Color.TRANSPARENT,
                                            Color.TRANSPARENT,
                                            Color.TRANSPARENT,
                                            Color.TRANSPARENT,
                                            BACKGROUND
                                    });
                                }
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not update Now Playing gradient: "
                                                + throwable
                                );
                            }
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log(
                    TAG + ": Could not hook Now Playing gradient: " + throwable
            );
        }
    }

    private void hookAlbumHeaderGradient() {
        try {
            Class<?> gradientClass =
                    XposedHelpers.findClass("p.ojv", lpparm.classLoader);

            Class<?> positionedGradientClass =
                    XposedHelpers.findClass("p.pjv", lpparm.classLoader);

            Class<?> gradientStopClass =
                    XposedHelpers.findClass("p.njv", lpparm.classLoader);

            Class<?> extractedColorClass =
                    XposedHelpers.findClass("p.kjv", lpparm.classLoader);

            Class<?> fixedColorClass =
                    XposedHelpers.findClass("p.ljv", lpparm.classLoader);

            XposedHelpers.findAndHookConstructor(
                    gradientClass,
                    List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                List<?> original = (List<?>) param.args[0];

                                /*
                                 * Album/entity headers normally arrive as:
                                 *
                                 *   [artwork-extracted color, fixed #121212]
                                 *
                                 * Avoid touching unrelated application gradients.
                                 */
                                if (original == null || original.size() != 2) {
                                    return;
                                }

                                Object start = original.get(0);
                                Object darkEnd = original.get(1);

                                if (!extractedColorClass.isInstance(start)
                                        || !fixedColorClass.isInstance(darkEnd)) {
                                    return;
                                }

                                Object themedEnd = XposedHelpers.newInstance(
                                        fixedColorClass,
                                        BACKGROUND
                                );

                                ArrayList<Object> flowingGradient =
                                        new ArrayList<>(2);

                                // End the entity header at the live page color.
                                flowingGradient.add(start);
                                flowingGradient.add(themedEnd);

                                param.args[0] = flowingGradient;

                                XposedBridge.log(
                                        TAG + "Recolored linear entity gradient to #"
                                                + Integer.toHexString(BACKGROUND)
                                );
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not modify album gradient: "
                                                + throwable
                                );
                            }
                        }
                    }
            );

            /*
             * Current album pages use pjv, not ojv. vnf case 5 constructs:
             *
             *   0.0  artwork/dynamic color
             *   0.1  artwork/dynamic color
             *   1.0  fixed Encore background
             *
             * The old hook only covered the two-color ojv variant, which is
             * why it installed successfully without ever changing this screen.
             */
            XposedHelpers.findAndHookConstructor(
                    positionedGradientClass,
                    float.class,
                    List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                List<?> original = (List<?>) param.args[1];

                                if (original == null || original.size() != 3) {
                                    return;
                                }

                                Object firstStop = original.get(0);
                                Object secondStop = original.get(1);
                                Object finalStop = original.get(2);

                                Object firstColor = XposedHelpers.getObjectField(
                                        firstStop,
                                        "b"
                                );
                                Object secondColor = XposedHelpers.getObjectField(
                                        secondStop,
                                        "b"
                                );
                                Object finalColor = XposedHelpers.getObjectField(
                                        finalStop,
                                        "b"
                                );

                                if (!extractedColorClass.isInstance(firstColor)
                                        || !extractedColorClass.isInstance(secondColor)
                                        || !fixedColorClass.isInstance(finalColor)) {
                                    return;
                                }

                                float firstPosition =
                                        XposedHelpers.getFloatField(firstStop, "a");
                                float secondPosition =
                                        XposedHelpers.getFloatField(secondStop, "a");
                                float finalPosition =
                                        XposedHelpers.getFloatField(finalStop, "a");

                                if (firstPosition != 0.0f
                                        || secondPosition != 0.1f
                                        || finalPosition != 1.0f) {
                                    return;
                                }

                                Object themedEnd = XposedHelpers.newInstance(
                                        fixedColorClass,
                                        BACKGROUND
                                );

                                ArrayList<Object> themedStops = new ArrayList<>(3);
                                themedStops.add(firstStop);
                                themedStops.add(secondStop);
                                themedStops.add(
                                        XposedHelpers.newInstance(
                                                gradientStopClass,
                                                1.0f,
                                                themedEnd
                                        )
                                );

                                param.args[1] = themedStops;

                                XposedBridge.log(
                                        TAG + "Recolored positioned album gradient to #"
                                                + Integer.toHexString(BACKGROUND)
                                );
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not modify positioned album gradient"
                                );
                                XposedBridge.log(throwable);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + "Entity-gradient hooks installed");
        } catch (Throwable throwable) {
            XposedBridge.log(
                    TAG + ": Could not hook album header gradient: " + throwable
            );
        }
    }

    private void hookSpotifyConnectColor() {
        try {
            Class<?> connectLabelClass = XposedHelpers.findClass(
                    "com.spotify.connect.destinationbutton.ConnectLabel",
                    lpparm.classLoader
            );

            Class<?> connectionInfoClass = XposedHelpers.findClass(
                    "p.jr20",
                    lpparm.classLoader
            );

            /*
             * x(...) is the connected-device rendering path.
             * It renders the icon, device name and Lossless suffix in green.
             */
            XposedHelpers.findAndHookMethod(
                    connectLabelClass,
                    "x",
                    String.class,
                    int.class,
                    boolean.class,
                    connectionInfoClass,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                TextView deviceName =
                                        (TextView) XposedHelpers.getObjectField(
                                                param.thisObject,
                                                "R0"
                                        );

                                ImageView deviceIcon =
                                        (ImageView) XposedHelpers.getObjectField(
                                                param.thisObject,
                                                "S0"
                                        );

                                deviceName.setTextColor(ACCENT);
                                deviceIcon.setColorFilter(
                                        ACCENT,
                                        PorterDuff.Mode.SRC_IN
                                );
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not recolor Spotify Connect: "
                                                + throwable
                                );
                            }
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log(
                    TAG + ": Could not hook Spotify Connect: " + throwable
            );
        }
    }

    private void hookCreativeWorkAlbumHeader() {
        try {
            Class<?> headerClass = XposedHelpers.findClass(
                    "p.hol",
                    lpparm.classLoader
            );

            /*
             * Prepare the album header's underlying surface. The artwork gradient
             * is a child of this surface, so it can now terminate at BACKGROUND
             * without revealing Spotify's original #121212 underneath.
             */
            Set<?> constructorHooks = XposedBridge.hookAllConstructors(
                    headerClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                Object binding = XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "d"
                                );

                                View headerRoot = (View) XposedHelpers.getObjectField(
                                        binding,
                                        "b"
                                );

                                View appBar = (View) XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "g"
                                );

                                headerRoot.setBackgroundColor(BACKGROUND);
                                appBar.setBackgroundColor(BACKGROUND);
                                themeAlbumHeaderText(headerRoot);
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not prepare album header: "
                                                + throwable
                                );
                            }
                        }
                    }
            );

            /*
             * hol.b(hol, int) receives the actual artwork-extracted color.
             * Spotify normally applies it behind transparent -> #121212.
             * Rebuild that drawable as transparent -> themed background.
             */
            XposedHelpers.findAndHookMethod(
                    headerClass,
                    "b",
                    headerClass,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                Object header = param.args[0];
                                int extractedColor = (Integer) param.args[1];
                                applyCreativeWorkAlbumHeader(
                                        header,
                                        extractedColor
                                );
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not apply album gradient: "
                                                + throwable
                                );
                            }
                        }
                    }
            );

            /*
             * The artwork loader invokes eol only after it has extracted the
             * album color and called hol.b(). Hooking this callback as well
             * ensures our drawable wins over every late header/app-bar write.
             */
            XposedHelpers.findAndHookMethod(
                    "p.eol",
                    lpparm.classLoader,
                    "invoke",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                Object header = XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "b"
                                );

                                if (!headerClass.isInstance(header)) {
                                    return;
                                }

                                int extractedColor = XposedHelpers.getIntField(
                                        param.thisObject,
                                        "c"
                                );

                                applyCreativeWorkAlbumHeader(
                                        header,
                                        extractedColor
                                );

                                Object binding = XposedHelpers.getObjectField(
                                        header,
                                        "d"
                                );
                                View headerRoot = (View)
                                        XposedHelpers.getObjectField(binding, "b");

                                headerRoot.post(() -> {
                                    try {
                                        applyCreativeWorkAlbumHeader(
                                                header,
                                                extractedColor
                                        );
                                    } catch (Throwable throwable) {
                                        XposedBridge.log(
                                                TAG + ": Delayed album gradient failed"
                                        );
                                        XposedBridge.log(throwable);
                                    }
                                });
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not apply post-image album gradient"
                                );
                                XposedBridge.log(throwable);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + "Creative-work album hooks installed");
        } catch (Throwable throwable) {
            XposedBridge.log(
                    TAG + ": Could not hook album header: " + throwable
            );
        }
    }

    private void themeAlbumHeaderText(View view) {
        if (!themeEnabled) return;
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(TEXT);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            for (int i = 0; i < group.getChildCount(); i++) {
                themeAlbumHeaderText(group.getChildAt(i));
            }
        }
    }

    private void hookAlbumTrackRows() {
        try {
            Class<?> rowClass = XposedHelpers.findClass(
                    "p.b7i",
                    lpparm.classLoader
            );

            Class<?> configurationClass = XposedHelpers.findClass(
                    "p.qno",
                    lpparm.classLoader
            );

            XposedHelpers.findAndHookConstructor(
                    rowClass,
                    Context.class,
                    configurationClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                /*
                                 * b7i has several unrelated constructors.
                                 * Variant 20 is specifically album_track_row_layout.
                                 */
                                if (XposedHelpers.getIntField(
                                        param.thisObject,
                                        "a"
                                ) != 20) {
                                    return;
                                }

                                Object binding = XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "b"
                                );

                                TextView title =
                                        (TextView) XposedHelpers.getObjectField(
                                                binding,
                                                "N0"
                                        );

                                TextView subtitle =
                                        (TextView) XposedHelpers.getObjectField(
                                                binding,
                                                "M0"
                                        );

                                ColorStateList titleColors = new ColorStateList(
                                        new int[][]{
                                                new int[]{android.R.attr.state_activated},
                                                new int[]{android.R.attr.state_selected},
                                                new int[]{}
                                        },
                                        new int[]{
                                                ACCENT,
                                                ACCENT,
                                                TEXT
                                        }
                                );

                                title.setTextColor(titleColors);
                                subtitle.setTextColor(TEXT_SUBDUED);
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not theme album row: "
                                                + throwable
                                );
                            }
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log(
                    TAG + ": Could not hook album rows: " + throwable
            );
        }
    }

    private void hookBottomNavigationGradient() {
        try {
            Class<?> gradientViewClass = XposedHelpers.findClass(
                    "com.spotify.musicappplatform.main.MainLayoutGradientView",
                    lpparm.classLoader
            );

            XposedHelpers.findAndHookConstructor(
                    gradientViewClass,
                    Context.class,
                    AttributeSet.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!themeEnabled) return;
                            try {
                                /*
                                 * Preserve the transparent beginning but fade into
                                 * the light theme instead of opaque black.
                                 */
                                XposedHelpers.callMethod(
                                        param.thisObject,
                                        "setTopColor",
                                        Integer.valueOf(Color.TRANSPARENT)
                                );

                                XposedHelpers.callMethod(
                                        param.thisObject,
                                        "setBottomColor",
                                        Integer.valueOf(BACKGROUND)
                                );
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + ": Could not update navigation gradient: "
                                                + throwable
                                );
                            }
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log(
                    TAG + ": Could not hook navigation gradient: " + throwable
            );
        }
    }

    private static void applyLightBluePalette() {
        BACKGROUND = 0xFFEAF6FF;
        BACKGROUND_HIGHLIGHT = 0xFFD8ECFA;
        BACKGROUND_PRESS = 0xFFC6E1F4;

        SURFACE = 0xFFF7FBFF;
        SURFACE_HIGHLIGHT = 0xFFE3F2FC;
        SURFACE_PRESS = 0xFFCFE7F7;

        TINTED = 0xFFD6EEFF;
        TINTED_HIGHLIGHT = 0xFFC2E4FF;
        TINTED_PRESS = 0xFFA9D8FA;

        TEXT = 0xFF102A43;
        TEXT_SUBDUED = 0xFF52718A;

        ACCENT = 0xFF0077B6;
        ACCENT_HIGHLIGHT = 0xFF159AD8;
        ACCENT_PRESS = 0xFF0369A1;

        ANNOUNCEMENT = 0xFF006FB0;
        DECORATIVE = 0xFF138CCB;
        DECORATIVE_SUBDUED = 0xFFA8D7F2;
    }

    private static void applyLavenderPalette() {
        BACKGROUND = 0xFFF6F0FF;
        BACKGROUND_HIGHLIGHT = 0xFFE9DEFA;
        BACKGROUND_PRESS = 0xFFDCCBF3;

        SURFACE = 0xFFFCFAFF;
        SURFACE_HIGHLIGHT = 0xFFF0E8FA;
        SURFACE_PRESS = 0xFFE3D7F2;

        TINTED = 0xFFEADFFF;
        TINTED_HIGHLIGHT = 0xFFDCCBFA;
        TINTED_PRESS = 0xFFCBB4F1;

        TEXT = 0xFF2E2140;
        TEXT_SUBDUED = 0xFF705C86;

        ACCENT = 0xFF7C3AED;
        ACCENT_HIGHLIGHT = 0xFF955BF2;
        ACCENT_PRESS = 0xFF6D28D9;

        ANNOUNCEMENT = 0xFF6D3BD1;
        DECORATIVE = 0xFF8651D6;
        DECORATIVE_SUBDUED = 0xFFD8C4F2;
    }

    public static void onPaletteChanged(ClassLoader classLoader) {
        synchronized (encoreCache) {
            encoreCache.clear();
        }

        synchronized (material3Cache) {
            material3Cache.clear();
        }

        ThemeResourcesHook.applyPalette(
                BACKGROUND,
                SURFACE,
                TINTED,
                TEXT,
                TEXT_SUBDUED,
                ACCENT,
                ON_ACCENT
        );

        updateHomeFilterChipColor(classLoader);

        paletteGeneration++;
        refreshComposeHosts();
    }

    /**
     * Tracks every Compose host, including hosts owned by cached and currently
     * detached bottom-navigation pages. p.o4 is this Spotify build's
     * obfuscated AbstractComposeView base class.
     */
    private void hookComposeHosts() {
        try {
            Class<?> composeHostClass = XposedHelpers.findClass(
                    "p.o4",
                    lpparm.classLoader
            );

            XposedBridge.hookAllConstructors(
                    composeHostClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            composeHosts.put(param.thisObject, Boolean.TRUE);
                        }
                    }
            );

            XposedBridge.log(TAG + "Compose-host tracking installed");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not track Compose hosts");
            XposedBridge.log(throwable);
        }
    }

    private void applyCreativeWorkAlbumHeader(
            Object header,
            int extractedColor
    ) {
        if (!themeEnabled) return;
        Object binding = XposedHelpers.getObjectField(header, "d");

        View artworkBackground = (View) XposedHelpers.getObjectField(
                binding,
                "d"
        );
        View headerRoot = (View) XposedHelpers.getObjectField(
                binding,
                "b"
        );
        View appBar = (View) XposedHelpers.getObjectField(header, "g");

        int[] colors = {
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                BACKGROUND
        };

        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                colors
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            gradient.setColors(
                    colors,
                    new float[]{
                            0.00f,
                            0.38f,
                            1.00f
                    }
            );
        }

        gradient.setColorFilter(
                new PorterDuffColorFilter(
                        extractedColor,
                        PorterDuff.Mode.DST_OVER
                )
        );

        artworkBackground.setBackground(gradient);
        headerRoot.setBackgroundColor(BACKGROUND);
        appBar.setBackgroundColor(BACKGROUND);
        themeAlbumHeaderText(headerRoot);

        XposedBridge.log(
                TAG + "Applied album gradient #"
                        + Integer.toHexString(extractedColor)
                        + " -> #" + Integer.toHexString(BACKGROUND)
        );
    }

    /**
     * o4.k() disposes the current composition but retains the host's content
     * function. Attached hosts are recreated immediately with o4.j(); detached
     * cached pages recreate with the new palette when they next attach.
     */
    private static void refreshComposeHosts() {
        List<Object> hosts;

        synchronized (composeHosts) {
            hosts = new ArrayList<>(composeHosts.keySet());
        }

        int refreshed = 0;

        for (Object host : hosts) {
            if (host == null) {
                continue;
            }

            try {
                boolean attached = (Boolean) XposedHelpers.callMethod(
                        host,
                        "isAttachedToWindow"
                );

                XposedHelpers.callMethod(host, "k");

                if (attached) {
                    XposedHelpers.callMethod(host, "j");
                }

                refreshed++;
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + "Could not refresh a Compose host");
                XposedBridge.log(throwable);
            }
        }

        XposedBridge.log(
                TAG + "Palette generation " + paletteGeneration
                        + " refreshed " + refreshed + " Compose hosts"
        );
    }

    private void hookTrackArtworkColor() {
        try {
            Class<?> playerConsumerClass = XposedHelpers.findClass(
                    "p.fa40",
                    lpparm.classLoader
            );

            XposedHelpers.findAndHookMethod(
                    playerConsumerClass,
                    "accept",
                    Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            /*
                             * fa40 variant 3 handles the global PlayerState.
                             */
                            if (XposedHelpers.getIntField(
                                    param.thisObject,
                                    "a"
                            ) != 3) {
                                return;
                            }

                            try {
                                Object stateWrapper = param.args[0];

                                Object playerState =
                                        XposedHelpers.getObjectField(
                                                stateWrapper,
                                                "a"
                                        );

                                if (playerState == null) {
                                    return;
                                }

                                Object trackOptional =
                                        XposedHelpers.callMethod(
                                                playerState,
                                                "track"
                                        );

                                Object contextTrack =
                                        XposedHelpers.callMethod(
                                                trackOptional,
                                                "h"
                                        );

                                if (contextTrack == null) {
                                    return;
                                }

                                Map<?, ?> metadata = (Map<?, ?>)
                                        XposedHelpers.callMethod(
                                                contextTrack,
                                                "metadata"
                                        );

                                String imageUrl =
                                        (String) metadata.get("image_url");

                                if (imageUrl == null || imageUrl.isEmpty()) {
                                    return;
                                }

                                /*
                                 * Keep Spotify's color only as a fallback if the
                                 * artwork cannot be loaded.
                                 */
                                Integer fallbackColor = null;
                                String encodedColor =
                                        (String) metadata.get("extracted_color");

                                if (encodedColor != null
                                        && !encodedColor.isEmpty()) {
                                    try {
                                        fallbackColor = Color.parseColor(
                                                encodedColor.startsWith("#")
                                                        ? encodedColor
                                                        : "#" + encodedColor
                                        );
                                    } catch (IllegalArgumentException ignored) {
                                    }
                                }

                                requestArtworkTheme(
                                        imageUrl,
                                        fallbackColor
                                );
                            } catch (Throwable throwable) {
                                XposedBridge.log(
                                        TAG + "Failed reading artwork metadata"
                                );
                                XposedBridge.log(throwable);
                            }
                        }
                    }
            );

            XposedBridge.log(TAG + "Artwork image hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log(
                    TAG + "Could not hook artwork metadata"
            );
            XposedBridge.log(throwable);
        }
    }

    private void requestArtworkTheme(
            String spotifyImageUrl,
            Integer fallbackColor
    ) {
        if (!themeEnabled || !prefs.getBoolean("auto_theme", false)) {
            lastArtworkUrl = null;
            artworkRequestGeneration.incrementAndGet();
            return;
        }
        String downloadUrl = normalizeArtworkUrl(spotifyImageUrl);

        if (downloadUrl == null) {
            if (fallbackColor != null) {
                paletteHandler.post(() ->
                        applyArtworkTheme(fallbackColor)
                );
            }

            return;
        }

        if (downloadUrl.equals(lastArtworkUrl)) {
            return;
        }

        lastArtworkUrl = downloadUrl;

        int generation =
                artworkRequestGeneration.incrementAndGet();

        Integer cachedColor =
                artworkColorCache.get(downloadUrl);

        if (cachedColor != null) {
            paletteHandler.post(() -> {
                if (generation
                        == artworkRequestGeneration.get()) {
                    applyArtworkTheme(cachedColor);
                }
            });

            return;
        }

        artworkExecutor.execute(() -> {
            Integer selectedColor = null;

            try {
                Bitmap artwork = downloadArtwork(downloadUrl);

                if (artwork != null) {
                    selectedColor =
                            selectArtworkColor(artwork);

                    artwork.recycle();
                }
            } catch (Throwable throwable) {
                XposedBridge.log(
                        TAG + "Artwork download/extraction failed: "
                                + downloadUrl
                );
                XposedBridge.log(throwable);
            }

            if (selectedColor == null) {
                selectedColor = fallbackColor;
            }

            if (selectedColor == null) {
                return;
            }

            artworkColorCache.put(
                    downloadUrl,
                    selectedColor
            );

            final int result = selectedColor;

            paletteHandler.post(() -> {
                /*
                 * Ignore a slow response if another track started while this
                 * artwork was downloading.
                 */
                if (generation
                        != artworkRequestGeneration.get()) {
                    return;
                }

                XposedBridge.log(
                        TAG + "Selected artwork color #"
                                + Integer.toHexString(result)
                );

                applyArtworkTheme(result);
            });
        });
    }

    private static String normalizeArtworkUrl(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }

        String trimmed = imageUrl.trim();

        String spotifyPrefix = "spotify:image:";

        if (trimmed.startsWith(spotifyPrefix)) {
            String imageId =
                    trimmed.substring(spotifyPrefix.length());

            if (imageId.isEmpty()) {
                return null;
            }

            return "https://i.scdn.co/image/" + imageId;
        }

        if (trimmed.startsWith("https://")
                || trimmed.startsWith("http://")) {
            return trimmed;
        }

        XposedBridge.log(
                TAG + "Unsupported artwork URI: " + trimmed
        );

        return null;
    }

    private static Bitmap downloadArtwork(String imageUrl)
            throws Exception {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(imageUrl).openConnection();

            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty(
                    "User-Agent",
                    "SpotifyPlus/ArtworkTheme"
            );

            int status = connection.getResponseCode();

            if (status < 200 || status >= 300) {
                throw new IllegalStateException(
                        "Artwork request returned HTTP " + status
                );
            }

            try (InputStream stream =
                         connection.getInputStream()) {
                Bitmap decoded =
                        BitmapFactory.decodeStream(stream);

                if (decoded == null) {
                    return null;
                }

                int width = decoded.getWidth();
                int height = decoded.getHeight();
                int largest = Math.max(width, height);

                if (largest <= 64) {
                    return decoded;
                }

                float scale = 64.0f / largest;

                int scaledWidth = Math.max(
                        1,
                        Math.round(width * scale)
                );

                int scaledHeight = Math.max(
                        1,
                        Math.round(height * scale)
                );

                Bitmap scaled = Bitmap.createScaledBitmap(
                        decoded,
                        scaledWidth,
                        scaledHeight,
                        true
                );

                if (scaled != decoded) {
                    decoded.recycle();
                }

                return scaled;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static int selectArtworkColor(Bitmap bitmap) {
        /*
         * 24 hue ranges × 4 saturation ranges × 4 brightness ranges.
         */
        final int hueBuckets = 24;
        final int saturationBuckets = 4;
        final int valueBuckets = 4;
        final int bucketCount =
                hueBuckets * saturationBuckets * valueBuckets;

        int[] populations = new int[bucketCount];
        long[] redTotals = new long[bucketCount];
        long[] greenTotals = new long[bucketCount];
        long[] blueTotals = new long[bucketCount];

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];

        bitmap.getPixels(
                pixels,
                0,
                width,
                0,
                0,
                width,
                height
        );

        float[] hsv = new float[3];

        for (int pixel : pixels) {
            if (Color.alpha(pixel) < 200) {
                continue;
            }

            Color.colorToHSV(pixel, hsv);

            float hue = hsv[0];
            float saturation = hsv[1];
            float value = hsv[2];

            /*
             * Ignore almost-black and almost-white neutral pixels. They commonly
             * come from borders, text and logos rather than the cover's identity.
             */
            if (value < 0.07f) {
                continue;
            }

            if (value > 0.94f && saturation < 0.14f) {
                continue;
            }

            int hueBucket = Math.min(
                    hueBuckets - 1,
                    (int) (hue / 360.0f * hueBuckets)
            );

            int saturationBucket = Math.min(
                    saturationBuckets - 1,
                    (int) (saturation * saturationBuckets)
            );

            int valueBucket = Math.min(
                    valueBuckets - 1,
                    (int) (value * valueBuckets)
            );

            int bucket =
                    hueBucket * saturationBuckets * valueBuckets
                            + saturationBucket * valueBuckets
                            + valueBucket;

            populations[bucket]++;
            redTotals[bucket] += Color.red(pixel);
            greenTotals[bucket] += Color.green(pixel);
            blueTotals[bucket] += Color.blue(pixel);
        }

        int bestBucket = -1;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int bucket = 0;
             bucket < bucketCount;
             bucket++) {
            int population = populations[bucket];

            if (population == 0) {
                continue;
            }

            int red = (int) (
                    redTotals[bucket] / population
            );

            int green = (int) (
                    greenTotals[bucket] / population
            );

            int blue = (int) (
                    blueTotals[bucket] / population
            );

            int averageColor =
                    Color.rgb(red, green, blue);

            Color.colorToHSV(averageColor, hsv);

            double saturationWeight =
                    0.70 + hsv[1] * 0.85;

            /*
             * Avoid selecting tiny bright logos over a much larger cover color.
             * Population remains the strongest part of the score.
             */
            double brightnessWeight =
                    1.15 - Math.abs(hsv[2] - 0.55) * 0.45;

            double score =
                    population
                            * saturationWeight
                            * brightnessWeight;

            if (score > bestScore) {
                bestScore = score;
                bestBucket = bucket;
            }
        }

        if (bestBucket < 0) {
            /*
             * Unusual fully transparent or monochrome image.
             */
            return averageBitmapColor(pixels);
        }

        int population = populations[bestBucket];

        return Color.rgb(
                (int) (redTotals[bestBucket] / population),
                (int) (greenTotals[bestBucket] / population),
                (int) (blueTotals[bestBucket] / population)
        );
    }

    private static int averageBitmapColor(int[] pixels) {
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;

        for (int pixel : pixels) {
            if (Color.alpha(pixel) < 200) {
                continue;
            }

            red += Color.red(pixel);
            green += Color.green(pixel);
            blue += Color.blue(pixel);
            count++;
        }

        if (count == 0) {
            return 0xFF303030;
        }

        return Color.rgb(
                (int) (red / count),
                (int) (green / count),
                (int) (blue / count)
        );
    }

    private void applyArtworkTheme(int artworkColor) {
        if (!themeEnabled || !prefs.getBoolean("auto_theme", false)) return;
        generateTheme(artworkColor, prefs.getString("auto_theme_mode", "neutral"), lpparm.classLoader);
    }

    private static int blendColors(
            int first,
            int second,
            float secondAmount
    ) {
        float firstAmount = 1.0f - secondAmount;

        return Color.rgb(
                Math.round(Color.red(first) * firstAmount
                        + Color.red(second) * secondAmount),
                Math.round(Color.green(first) * firstAmount
                        + Color.green(second) * secondAmount),
                Math.round(Color.blue(first) * firstAmount
                        + Color.blue(second) * secondAmount)
        );
    }

    private static int ensureDarkEnough(int color) {
        double luminance =
                0.2126 * (Color.red(color) / 255.0)
                        + 0.7152 * (Color.green(color) / 255.0)
                        + 0.0722 * (Color.blue(color) / 255.0);

        if (luminance <= 0.48) {
            return color;
        }

        return blendColors(color, Color.BLACK, 0.38f);
    }

    private void hookVisibleActivity() {
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;

                            if (!"com.spotify.music".equals(
                                    activity.getPackageName()
                            )) {
                                return;
                            }

                            visibleActivity = new WeakReference<>(activity);

                            boolean stale;

                            synchronized (activityPaletteGenerations) {
                                Integer appliedGeneration =
                                        activityPaletteGenerations.get(activity);

                                if (appliedGeneration == null) {
                                    activityPaletteGenerations.put(
                                            activity,
                                            paletteGeneration
                                    );
                                    stale = false;
                                } else {
                                    stale = appliedGeneration != paletteGeneration;

                                    if (stale) {
                                        activityPaletteGenerations.put(
                                                activity,
                                                paletteGeneration
                                        );
                                    }
                                }
                            }

                            XposedBridge.log(
                                    TAG + "Visible activity: "
                                            + activity.getClass().getName()
                            );

                            if (stale) {
                                /*
                                 * This activity was covered when the song
                                 * changed (usually SpotifyMainActivity beneath
                                 * Now Playing). Rebuild it as it returns.
                                 */
                                paletteHandler.post(() ->
                                        recreateActivity(activity)
                                );
                            }
                        }
                    }
            );
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not track visible activity");
            XposedBridge.log(throwable);
        }
    }

    private static void recreateVisibleActivity() {
        Activity activity = visibleActivity.get();

        if (activity == null) {
            XposedBridge.log(
                    TAG + "Palette changed, but no visible activity was captured"
            );
            return;
        }

        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        synchronized (activityPaletteGenerations) {
            activityPaletteGenerations.put(activity, paletteGeneration);
        }

        recreateActivity(activity);
    }

    private static void recreateActivity(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.getTheme().rebase();
            }

            if (BeautifulLyricsHook.isOverlayShowing(activity)) {
                View content = activity.findViewById(android.R.id.content);
                if (content != null) {
                    content.requestLayout();
                    content.invalidate();
                }
                activity.getWindow().getDecorView().invalidate();
                XposedBridge.log(TAG + "Rebased " + activity.getClass().getName() + " in place while lyrics are open for palette generation " + paletteGeneration);
                return;
            }

            XposedBridge.log(
                    TAG + "Recreating " + activity.getClass().getName()
                            + " for palette generation " + paletteGeneration
            );

            RemoveCreateButtonHook.resetSettingsOverlayState();
            activity.recreate();
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + "Could not recreate Spotify activity");
            XposedBridge.log(throwable);
        }
    }

    private static int createArtworkAccent(int background, int contrastTarget) {
        float[] hsv = new float[3];
        Color.colorToHSV(background, hsv);

        /*
         * Retain the artwork hue. Raise saturation slightly so buttons and active
         * controls do not become gray when the extracted color is muted.
         */
        hsv[1] = Math.max(hsv[1], 0.42f);

        boolean darkBackground = relativeLuminance(background) < 0.42;

        if (darkBackground) {
            hsv[2] = Math.max(hsv[2], 0.78f);
        } else {
            hsv[2] = Math.min(hsv[2], 0.46f);
        }

        int candidate = Color.HSVToColor(hsv);

        /*
         * Guarantee that the accent remains distinguishable from the page.
         */
        return ensureContrast(
                candidate,
                background,
                contrastTarget,
                3.0
        );
    }

    private static int bestTextColor(int background) {
        int lightText = 0xFFF8FAFC;
        int darkText = 0xFF101418;

        double lightContrast =
                contrastRatio(lightText, background);
        double darkContrast =
                contrastRatio(darkText, background);

        return lightContrast >= darkContrast
                ? lightText
                : darkText;
    }

    private static int ensureContrast(
            int foreground,
            int background,
            int contrastTarget,
            double minimumRatio
    ) {
        int result = foreground;

        /*
         * Gradually pull the color toward the selected high-contrast target.
         */
        for (int i = 0;
             i < 20
                     && contrastRatio(result, background) < minimumRatio;
             i++) {
            result = blendColors(result, contrastTarget, 0.10f);
        }

        return result;
    }

    private static double contrastRatio(int first, int second) {
        double firstLuminance = relativeLuminance(first);
        double secondLuminance = relativeLuminance(second);

        double lighter = Math.max(firstLuminance, secondLuminance);
        double darker = Math.min(firstLuminance, secondLuminance);

        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(int color) {
        double red = linearColorComponent(Color.red(color) / 255.0);
        double green = linearColorComponent(Color.green(color) / 255.0);
        double blue = linearColorComponent(Color.blue(color) / 255.0);

        return 0.2126 * red
                + 0.7152 * green
                + 0.0722 * blue;
    }

    private static double linearColorComponent(double component) {
        return component <= 0.04045
                ? component / 12.92
                : Math.pow(
                (component + 0.055) / 1.055,
                2.4
        );
    }

    /**
     * Compose's sRGB Color representation stores ARGB in the upper 32 bits.
     */
    private static long packColor(int argb) {
        return (argb & 0xFFFFFFFFL) << 32;
    }

    private static int unpackColor(long composeColor) {
        return (int) (composeColor >>> 32);
    }

    public static void generateTheme(int baseColor, String mode, ClassLoader classLoader) {
        int background = adjustBaseColor(baseColor | 0xFF000000, mode);
        int text = bestTextColor(background);
        int textSubdued = ensureContrast(blendColors(text, background, 0.32f), background, text, 4.5);

        float surfaceAmount = "light".equals(mode) ? 0.035f : "dark".equals(mode) ? 0.09f : 0.07f;
        int surface = blendColors(background, text, surfaceAmount);
        int surfaceHighlight = blendColors(background, text, surfaceAmount + 0.05f);
        int surfacePress = blendColors(background, text, surfaceAmount + 0.11f);

        int accent = createThemeAccent(background, text, mode);
        int onAccent = bestTextColor(accent);

        int tinted = blendColors(background, accent, "neutral".equals(mode) ? 0.24f : 0.20f);
        int tintedHighlight = blendColors(tinted, text, 0.08f);
        int tintedPress = blendColors(tinted, text, 0.15f);

        BACKGROUND = background;
        BACKGROUND_HIGHLIGHT = blendColors(background, text, 0.08f);
        BACKGROUND_PRESS = blendColors(background, text, 0.15f);

        SURFACE = surface;
        SURFACE_HIGHLIGHT = surfaceHighlight;
        SURFACE_PRESS = surfacePress;

        TINTED = tinted;
        TINTED_HIGHLIGHT = tintedHighlight;
        TINTED_PRESS = tintedPress;

        TEXT = text;
        TEXT_SUBDUED = textSubdued;

        ACCENT = accent;
        ACCENT_HIGHLIGHT = blendColors(accent, onAccent, 0.12f);
        ACCENT_PRESS = blendColors(accent, onAccent, 0.22f);

        ON_ACCENT = onAccent;
        ANNOUNCEMENT = accent;
        DECORATIVE = accent;
        DECORATIVE_SUBDUED = blendColors(background, accent, 0.42f);

        XposedBridge.log(TAG + "Generated " + mode + " theme from #" + String.format("%08X", baseColor));
        onPaletteChanged(classLoader);
        recreateVisibleActivity();
    }

    private static int adjustBaseColor(int color, String mode) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        switch (mode) {
            case "light": {
                hsv[1] = Math.min(hsv[1], 0.42f);
                hsv[2] = Math.max(hsv[2], 0.90f);
                break;
            }

            case "dark": {
                hsv[1] = Math.max(hsv[1], 0.30f);
                hsv[2] = Math.min(hsv[2], 0.24f);
                break;
            }

            default: {
                hsv[1] = Math.max(hsv[1], 0.18f);
                hsv[2] = Math.max(0.16f, Math.min(hsv[2], 0.86f));
                break;
            }
        }

        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static int createThemeAccent(int background, int contrastTarget, String mode) {
        float[] hsv = new float[3];
        Color.colorToHSV(background, hsv);

        hsv[1] = Math.max(hsv[1], 0.48f);

        switch (mode) {
            case "light":
                hsv[2] = Math.min(Math.max(hsv[2], 0.52f), 0.68f);
                break;

            case "dark":
                hsv[2] = Math.max(hsv[2], 0.78f);
                break;

            default:
                hsv[2] = relativeLuminance(background) < 0.42 ? Math.max(hsv[2], 0.76f) : Math.min(hsv[2], 0.48f);
                break;
        }

        return ensureContrast(Color.HSVToColor(hsv), background, contrastTarget, 3.0);
    }
}
