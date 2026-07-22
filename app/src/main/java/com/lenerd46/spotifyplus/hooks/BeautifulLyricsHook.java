package com.lenerd46.spotifyplus.hooks;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XModuleResources;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.*;
import androidx.core.content.res.ResourcesCompat;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.flexbox.JustifyContent;
import com.google.gson.*;
import com.lenerd46.spotifyplus.R;
import com.lenerd46.spotifyplus.References;
import com.lenerd46.spotifyplus.SpotifyTrack;
import com.lenerd46.spotifyplus.beautifullyrics.entities.*;
import com.lenerd46.spotifyplus.beautifullyrics.entities.lyrics.*;
import com.lenerd46.spotifyplus.beautifullyrics.entities.interludes.InterludeVisual;
import com.lenerd46.spotifyplus.netease.NeteaseApi;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class BeautifulLyricsHook extends SpotifyHook {

    private static Map<FlexboxLayout, List<SyncableVocals>> vocalGroups;
    private volatile boolean stop = false;
    private Thread mainLoop;
    private final Handler closeButtonHandler = new Handler(Looper.getMainLooper());
    private Runnable closeButtonRunnable;
    private ImageView closeButton;
    private LinearLayout rightContainer;
    private ImageView syncButton;
    private Constructor<?> ctor = null;
    private Object seekInstance = null;
    private LineSyncedLyrics lineLyrics = null;
    private boolean isPlaying = true;

    private static final float SCROLL_POSITION_RATIO = 0.18f;
    private static final double LINE_ANIMATION_DELAY = 25;
    private static final double SCROLL_SPRING_FREQUENCY = 2.5;
    private static final double SCROLL_SPRING_DAMPING = 0.85;

    private View experimentalTouchSurface;
    private View headerFadeAnchor;
    private static final float HEADER_FADE_DISTANCE_DP = 96f;
    private static final float HEADER_FADE_MIN_ALPHA = 0.12f;
    private static final float HEADER_PIXEL_FADE_DP = 72f;

    private GestureDetector gestureDetector;
    private double targetScrollOffset = 0;
    private double contentHeight = 0;
    private double viewportHeight = 0;
    private boolean isUserInteracting = false;
    private boolean isFollowingPlayback = true;
    private View currentActiveLineView = null;
    private String currentLineSpacingMode = "default";

    private final Map<View, Spring> lineSprings = new HashMap<>();
    private final Map<View, Long> lineAnimationStartTimes = new HashMap<>();

    private final Map<View, Integer> logicalLineTops = new HashMap<>();
    private final Map<View, Double> lineBaseOffsets = new HashMap<>();

    private final List<View> lineRoots = new ArrayList<>();
    private final Map<View, Integer> lineIndex = new WeakHashMap<>();
    private int activeLineIndex = -1;

    private static final int BLUR_MAX_DISTANCE = 3;

    private static final float[] BLUR_LEVELS_DP = new float[] {
            0f,
            0.6f,
            1.4f,
            2f
    };

    private int lastAppliedActiveIndex = Integer.MIN_VALUE;
    private boolean lastBlurAllowed = true;
    private ValueAnimator inertiaAnimator;

    @Override
    protected void hook() {
        XposedHelpers.findAndHookMethod("com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity",
                lpparm.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            XposedBridge.log("[SpotifyPlus] Loading Beautiful Lyrics ✨");

                            final Activity activity = (Activity) param.thisObject;

                            stop = false;
                            lastUpdatedAt = 0;
                            lastTimestamp = 0;
                            targetScrollOffset = 0;

                            // new Thread(() -> {
                            // try {
                            // String lyrics = NeteaseApi.fetchLyric(2133503225L);
                            // XposedBridge.log("[SpotifyPlus] " + lyrics);
                            // } catch (Exception e) {
                            // XposedBridge.log("[SpotifyPlus] " + e.getMessage());
                            // XposedBridge.log(e);
                            // }
                            // }).start();

                            activity.runOnUiThread(() -> {
                                try {
                                    activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
                                    ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();

                                    XModuleResources res = References.modResources;

                                    GridLayout grid = new GridLayout(activity);
                                    grid.setRowCount(2);
                                    grid.setColumnCount(1);
                                    grid.setElevation(10f);
                                    grid.setClickable(true);
                                    grid.setFocusable(true);
                                    grid.setClipChildren(true);
                                    grid.setClipToPadding(false);

                                    SpotifyTrack track = References.getTrackTitle(lpparm, bridge);
                                    if (track == null) {
                                        XposedBridge.log("[SpotifyPlus] Failed to get current track");
                                        return;
                                    }

                                    // Header
                                    FrameLayout headerContainer = new FrameLayout(activity);
                                    GridLayout.LayoutParams headerParams = new GridLayout.LayoutParams(
                                            GridLayout.spec(0), GridLayout.spec(0));
                                    headerParams.width = GridLayout.LayoutParams.MATCH_PARENT;
                                    headerParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
                                    headerContainer.setLayoutParams(headerParams);

                                    LinearLayout header = new LinearLayout(activity);
                                    header.setOrientation(LinearLayout.HORIZONTAL);
                                    header.setGravity(Gravity.CENTER_VERTICAL);
                                    header.setPadding(dpToPx(22, activity), dpToPx(32, activity), dpToPx(22, activity),
                                            dpToPx(18, activity));
                                    header.setLayoutParams(
                                            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                                                    FrameLayout.LayoutParams.WRAP_CONTENT));

                                    ImageView cover = new ImageView(activity);
                                    int coverSize = dpToPx(56, activity);
                                    cover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
                                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);

                                    LinearLayout titleAndArtist = new LinearLayout(activity);
                                    titleAndArtist.setOrientation(LinearLayout.VERTICAL);
                                    titleAndArtist.setPadding(dpToPx(12, activity), 0, 0, 0);

                                    TextView titleText = new TextView(activity);
                                    titleText.setText(track.title);
                                    titleText.setTextColor(Color.WHITE);
                                    titleText.setTextSize(20f);

                                    TextView artistText = new TextView(activity);
                                    artistText.setText(track.artist);
                                    artistText.setTextColor(Color.LTGRAY);
                                    artistText.setTextSize(16f);

                                    titleAndArtist.addView(titleText);
                                    titleAndArtist.addView(artistText);
                                    header.addView(cover);
                                    header.addView(titleAndArtist);

                                    rightContainer = new LinearLayout(activity);
                                    rightContainer.setOrientation(LinearLayout.HORIZONTAL);
                                    rightContainer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                                    FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            Gravity.END | Gravity.CENTER_VERTICAL);
                                    rightParams.setMargins(0, dpToPx(8, activity), dpToPx(22, activity), 0);
                                    rightContainer.setLayoutParams(rightParams);
                                    rightContainer.setAlpha(0f);

                                    closeButton = new ImageView(activity);
                                    closeButton.setImageDrawable(createChevronDownIcon(activity));
                                    closeButton.setOnClickListener(v -> activity.onBackPressed());

                                    syncButton = new ImageView(activity);
                                    syncButton.setImageDrawable(
                                            ResourcesCompat.getDrawable(res, R.drawable.add_circle, null));

                                    rightContainer.addView(syncButton);
                                    rightContainer.addView(closeButton);
                                    headerContainer.addView(header);
                                    headerContainer.addView(rightContainer);
                                    headerFadeAnchor = headerContainer;

                                    SharedPreferences prefs = activity.getSharedPreferences("SpotifyPlus",
                                            Context.MODE_PRIVATE);

                                    if (prefs.getBoolean("experiment_scroll", false)) {
                                        int fadePx = dpToPx((int) HEADER_PIXEL_FADE_DP, activity);

                                        TopFadeLayout lyricsRoot = new TopFadeLayout(activity, fadePx);
                                        GridLayout.LayoutParams scrollParams = new GridLayout.LayoutParams(
                                                GridLayout.spec(1), GridLayout.spec(0));
                                        scrollParams.width = GridLayout.LayoutParams.MATCH_PARENT;
                                        scrollParams.height = GridLayout.LayoutParams.MATCH_PARENT;
                                        lyricsRoot.setLayoutParams(scrollParams);
                                        lyricsRoot.setClipChildren(false);
                                        lyricsRoot.setClipToPadding(false);

                                        LinearLayout layout = new LinearLayout(activity);
                                        layout.setOrientation(LinearLayout.VERTICAL);

                                        FrameLayout.LayoutParams matchParams = new FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                dpToPx(40000, activity));
                                        layout.setLayoutParams(matchParams);
                                        layout.setClipChildren(true);
                                        layout.setClipToPadding(false);

                                        lyricsRoot.addView(layout);

                                        setupGestureDetector(activity, lyricsRoot, layout);
                                        experimentalTouchSurface = lyricsRoot;

                                        closeButtonRunnable = () -> rightContainer.animate().alpha(0f).setDuration(300)
                                                .start();

                                        FrameLayout blackBox = new FrameLayout(activity);
                                        GridLayout.LayoutParams blackParams = new GridLayout.LayoutParams(
                                                GridLayout.spec(0, 2), GridLayout.spec(0));
                                        blackParams.width = GridLayout.LayoutParams.MATCH_PARENT;
                                        blackParams.height = GridLayout.LayoutParams.MATCH_PARENT;
                                        blackBox.setLayoutParams(blackParams);
                                        blackBox.setBackgroundColor(Color.BLACK);
                                        blackBox.setAlpha(0.2f);

                                        grid.addView(blackBox);
                                        grid.addView(headerContainer);
                                        grid.addView(lyricsRoot);
                                        root.addView(grid, -2);

                                        syncButton.setOnClickListener(v -> {
                                            LayoutInflater inflater = LayoutInflater.from(activity);
                                            View newView = inflater.inflate(res.getLayout(R.layout.editor_layout),
                                                    (ViewGroup) activity.getWindow().getDecorView(), false);
                                            grid.removeView(lyricsRoot);
                                            grid.addView(newView);

                                            LinearLayout lyricsContainer = newView.findViewById(res.getIdentifier(
                                                    "lyrics_container", "id", "com.lenerd46.spotifyplus"));
                                            ScrollView scroller = newView.findViewById(
                                                    res.getIdentifier("scroller", "id", "com.lenerd46.spotifyplus"));

                                            renderSyncLyricsSplitting(activity, lyricsContainer, scroller,
                                                    track.uri.split(":")[2], newView);
                                        });

                                        renderLyrics(activity, track, layout, root, cover);
                                    } else {
                                        int fadePx = dpToPx((int) HEADER_PIXEL_FADE_DP, activity);

                                        TopFadeLayout fadeWrapper = new TopFadeLayout(activity, fadePx);
                                        GridLayout.LayoutParams scrollParams = new GridLayout.LayoutParams(
                                                GridLayout.spec(1), GridLayout.spec(0));
                                        scrollParams.width = GridLayout.LayoutParams.MATCH_PARENT;
                                        scrollParams.height = GridLayout.LayoutParams.MATCH_PARENT;
                                        fadeWrapper.setLayoutParams(scrollParams);
                                        fadeWrapper.setClipChildren(false);
                                        fadeWrapper.setClipToPadding(false);

                                        ScrollView scrollView = new ScrollView(activity);
                                        FrameLayout.LayoutParams svParams = new FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT);
                                        scrollView.setLayoutParams(svParams);
                                        scrollView.setClipToPadding(false);
                                        scrollView.setClipChildren(false);
                                        scrollView.setVerticalScrollBarEnabled(false);

                                        LinearLayout layout = new LinearLayout(activity);
                                        layout.setOrientation(LinearLayout.VERTICAL);
                                        ScrollView.LayoutParams matchParams = new ScrollView.LayoutParams(
                                                ScrollView.LayoutParams.MATCH_PARENT,
                                                ScrollView.LayoutParams.WRAP_CONTENT);
                                        layout.setLayoutParams(matchParams);
                                        layout.setClipToPadding(false);
                                        layout.setClipChildren(false);
                                        scrollView.addView(layout);

                                        fadeWrapper.addView(scrollView);
                                        experimentalTouchSurface = null;

                                        scrollView.setOnScrollChangeListener(
                                                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                                                    applyHeaderFadeToLines();
                                                });

                                        FrameLayout blackBox = new FrameLayout(activity);
                                        GridLayout.LayoutParams blackParams = new GridLayout.LayoutParams(
                                                GridLayout.spec(0, 2), GridLayout.spec(0));
                                        blackParams.width = GridLayout.LayoutParams.MATCH_PARENT;
                                        blackParams.height = GridLayout.LayoutParams.MATCH_PARENT;
                                        blackBox.setLayoutParams(blackParams);
                                        blackBox.setBackgroundColor(Color.BLACK);
                                        blackBox.setAlpha(0.1f);

                                        syncButton.setOnClickListener(v -> {
                                            LayoutInflater inflater = LayoutInflater.from(activity);
                                            View newView = inflater.inflate(res.getLayout(R.layout.editor_layout),
                                                    (ViewGroup) activity.getWindow().getDecorView(), false);
                                            // grid.removeView(scrollView);
                                            grid.removeView(fadeWrapper);
                                            grid.addView(newView);

                                            LinearLayout lyricsContainer = newView.findViewById(res.getIdentifier(
                                                    "lyrics_container", "id", "com.lenerd46.spotifyplus"));
                                            ScrollView scroller = newView.findViewById(
                                                    res.getIdentifier("scroller", "id", "com.lenerd46.spotifyplus"));

                                            renderSyncLyricsSplitting(activity, lyricsContainer, scroller,
                                                    track.uri.split(":")[2], newView);
                                        });

                                        if (prefs.getBoolean("lyric_enable_background", true)) {
                                            grid.addView(blackBox);
                                        }

                                        grid.addView(headerContainer);
                                        // grid.addView(scrollView);
                                        grid.addView(fadeWrapper);
                                        root.addView(grid, -2);
                                        XposedBridge.log("[SpotifyPlus] Loaded Beautiful Lyrics UI");

                                        renderLyrics(activity, track, layout, root, cover);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                            });
                        } catch (Exception e) {
                            XposedBridge.log(e);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity",
                lpparm.classLoader, "finish", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        stop = true;
                        lineSprings.clear();
                        lineAnimationStartTimes.clear();
                        if (mainLoop != null)
                            mainLoop.interrupt();
                        vocalGroups = null;
                        if (inertiaAnimator != null)
                            inertiaAnimator.cancel();
                    }
                });

        try {
            var whateverThisClassEvenDoes = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                    .modifiers(Modifier.PUBLIC | Modifier.FINAL).interfaceCount(1).fields(FieldsMatcher.create()
                            .add(FieldMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL))
                            .add(FieldMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).type(String.class))
                            .add(FieldMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL)
                                    .type(ArrayList.class))
                            .add(FieldMatcher.create().modifiers(Modifier.PUBLIC).type(Object.class))
                            .add(FieldMatcher.create().modifiers(Modifier.PUBLIC).type(Bundle.class)))));

            Method getStateMethod = bridge
                    .findMethod(FindMethod.create().searchInClass(whateverThisClassEvenDoes)
                            .matcher(MethodMatcher.create().name("getState")))
                    .get(0).getMethodInstance(lpparm.classLoader);
            XposedBridge.hookMethod(getStateMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    References.playerStateWrapper = new WeakReference<>(param.thisObject);
                }
            });
        } catch (Exception e) {
            XposedBridge.log(e);
        }

        try {

            // Class<?> playerStateChangedClass =
            // bridge.findClass(FindClass.create().matcher(ClassMatcher.create().usingStrings("PlayerStateChanged(trackUri="))).get(0).getInstance(lpparm.classLoader);
            // XposedHelpers.findAndHookConstructor("p.xb90", lpparm.classLoader,
            // String.class, boolean.class, boolean.class, boolean.class, boolean.class,
            // boolean.class, boolean.class, XposedHelpers.findClass("p.gqf",
            // lpparm.classLoader), new XC_MethodHook() {
            // protected void afterHookedMethod(MethodHookParam p) {
            //// XposedBridge.log("[SpotifyPlus] Object: " + p.thisObject.toString());
            // References.notifyTrackStateChanged(p.thisObject);
            // isPlaying = !XposedHelpers.getBooleanField(p.thisObject, "c");
            // }
            // });

            XposedHelpers.findAndHookMethod("android.media.session.MediaSession", lpparm.classLoader,
                    "setPlaybackState",
                    XposedHelpers.findClass("android.media.session.PlaybackState", lpparm.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            PlaybackState playbackState = (PlaybackState) param.args[0];
                            if (playbackState == null)
                                return;

                            isPlaying = playbackState.getState() == PlaybackState.STATE_PLAYING;
                        }
                    });
        } catch (Exception e) {
            XposedBridge.log(e);
        }

        XposedHelpers.findAndHookMethod("com.spotify.player.model.AutoValue_PlayerState$Builder", lpparm.classLoader,
                "build", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object state = param.getResult();
                        References.playerState = new WeakReference<>(state);
                        References.notifyPlayerStateChanged(state);
                    }
                });

        try {
            Class<?> hzc = bridge
                    .findClass(
                            FindClass.create()
                                    .matcher(ClassMatcher.create().usingStrings(
                                            "spotify.player.esperanto.proto.ContextPlayer", "SetOptions")))
                    .get(0).getInstance(lpparm.classLoader);
            Class<?> seek = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).interfaceCount(1)
                            .methodCount(3).fields(FieldsMatcher.create()
                                    .count(3)
                                    .add(FieldMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL).type(hzc))
                                    .add(FieldMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL)
                                            .type(boolean.class)))))
                    .get(0).getInstance(lpparm.classLoader);

            XposedBridge.hookAllConstructors(seek, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    seekInstance = param.thisObject;
                }
            });
        } catch (Exception e) {
            XposedBridge.log(e);
        }

        try {
            var okHttp = bridge.findClass(
                    FindClass.create().matcher(ClassMatcher.create().className(("okhttp3.Request$Builder"))));
            Method request = bridge
                    .findMethod(FindMethod.create().searchInClass((okHttp))
                            .matcher(MethodMatcher.create().returnType(void.class)
                                    .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                                    .paramTypes(String.class, String.class)))
                    .get(1).getMethodInstance((lpparm.classLoader));

            XposedBridge.hookMethod(request, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String headerName = (String) param.args[0];
                    String headerValue = (String) param.args[1];

                    if (headerName != null && headerName.equalsIgnoreCase("authorization") && headerValue != null
                            && !headerValue.isEmpty()) {
                        String token = headerValue.replace("Bearer", "").trim();
                        String accessToken = References.accessToken;

                        if (accessToken != null && !accessToken.isEmpty() && accessToken.equals(token))
                            return;
                        References.accessToken = token;
                    }
                }
            });
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestureDetector(Context context, View touchSurface, LinearLayout contentContainer) {
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                isUserInteracting = true;

                if (inertiaAnimator != null) {
                    inertiaAnimator.cancel();
                    inertiaAnimator = null;
                }

                lineAnimationStartTimes.clear();

                for (View line : lineRoots) {
                    Spring spring = lineSprings.get(line);
                    if (spring != null) {
                        spring.set(targetScrollOffset);
                    }
                }

                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                isFollowingPlayback = false;

                touchSurface.post(BeautifulLyricsHook.this::applyLineFocusEffects);

                contentHeight = contentContainer.getHeight();
                viewportHeight = touchSurface.getHeight();

                double maxScroll = 0;
                double minScroll = Math.min(0, -(contentHeight - viewportHeight));

                double effectiveDistanceY = distanceY;
                if (targetScrollOffset > maxScroll || targetScrollOffset < minScroll) {
                    effectiveDistanceY *= 0.3;
                }

                double newOffset = targetScrollOffset - effectiveDistanceY;
                applyImmediateScrollOffset(contentContainer, newOffset);

                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                for (View line : lineRoots) {
                    Spring spring = lineSprings.get(line);
                    if (spring != null) {
                        spring.set(targetScrollOffset);
                    }
                }

                double velocity = velocityY * 0.001;

                inertiaAnimator = ValueAnimator.ofFloat((float) velocity, 0f);
                inertiaAnimator.setDuration(800);
                inertiaAnimator.setInterpolator(new DecelerateInterpolator());
                inertiaAnimator.addUpdateListener(animation -> {
                    float v = (float) animation.getAnimatedValue();
                    if (!isUserInteracting) {
                        targetScrollOffset += v * 16;
                        limitScrollBounds(contentContainer);

                        for (Spring spring : lineSprings.values()) {
                            spring.finalPosition = targetScrollOffset;
                        }
                    }
                });
                inertiaAnimator.start();
                return true;
            }
        });

        touchSurface.setOnTouchListener((v, event) -> handleTouchSurfaceEvent(touchSurface, contentContainer, event));
    }

    private void limitScrollBounds(View contentContainer) {
        if (contentContainer == null)
            return;

        contentHeight = contentContainer.getHeight();

        View parent = (View) contentContainer.getParent();
        if (parent != null) {
            viewportHeight = parent.getHeight();
        }

        if (contentHeight == 0 || viewportHeight == 0)
            return;

        double maxScroll = 0;
        double minScroll = Math.min(0, -(contentHeight - viewportHeight));

        if (targetScrollOffset > maxScroll) {
            targetScrollOffset = maxScroll;
        } else if (targetScrollOffset < minScroll) {
            targetScrollOffset = minScroll;
        }
    }

    private void renderLyrics(Activity activity, SpotifyTrack track, LinearLayout lyricsContainer, ViewGroup root,
            ImageView albumView) {
        vocalGroups = new HashMap<>();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executorService.execute(() -> {
            String finalContent = "";

            try {
                SharedPreferences prefs = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);

                String id = track.uri.split(":")[2];
                Bitmap albumArt = getBitmap(track.imageId);
                albumView.post(() -> albumView.setImageBitmap(albumArt));

                if (prefs.getBoolean("lyric_enable_background", true)) {
                    if (albumArt != null) { // Clearly I don't seem to care if it's null or not (what used to be line
                                            // 290)
                        AnimatedBackgroundView background = new AnimatedBackgroundView(activity, albumArt, root);
                        background.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT));

                        activity.runOnUiThread(() -> root.addView(background));

                        // if (prefs.getBoolean("old_background", false)) {
                        // OldAnimatedBackgroundView background = new
                        // OldAnimatedBackgroundView(activity, albumArt, root);
                        // background.setLayoutParams(new
                        // FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        // FrameLayout.LayoutParams.MATCH_PARENT));
                        //
                        // activity.runOnUiThread(() -> root.addView(background));
                        // } else {
                        // }
                    }
                } else {
                    FrameLayout background = new FrameLayout(activity);
                    background.setBackgroundColor(Color.parseColor("#" + track.color));

                    activity.runOnUiThread(() -> root.addView(background));
                }

                // URL url = new URL("https://beautiful-lyrics.socalifornian.live/lyrics/" +
                // id);
                // HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                // connection.setRequestMethod("GET");
                //
                // String token = References.accessToken.get();
                // connection.setRequestProperty("Authorization", "Bearer " + (((token != null
                // && !token.isEmpty()) && sendAccessToken) ? token : "0"));

                // int responseCode = connection.getResponseCode();
                // if (responseCode == HttpURLConnection.HTTP_OK) {
                // BufferedReader in = new BufferedReader(new
                // InputStreamReader(connection.getInputStream()));
                //
                // String inputLine;
                // StringBuilder response = new StringBuilder();
                // while ((inputLine = in.readLine()) != null) {
                // response.append(inputLine);
                // }
                // in.close();
                // finalContent = response.toString();
                // }
                // } catch (Exception e) {
                // XposedBridge.log(e);
                // Handler mainHandler = new Handler(Looper.getMainLooper());
                // Runnable runnable = () -> {
                // Toast.makeText(activity, "Failed to get lyrics", Toast.LENGTH_LONG).show();
                // };
                //
                // mainHandler.post(runnable);
                // return;
                // }

                String token = References.accessToken;
                OkHttpClient lyricsClient = new OkHttpClient();

                RequestBody body = RequestBody.create(
                        "{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\"" + id
                                + "\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"5.22.3\"}}",
                        MediaType.parse("application/json; charset=utf-8"));
                Request lyricsRequest = new Request.Builder().url("https://api.spicylyrics.org/query").post(body)
                        .header("Spicylyrics-Webauth", "Bearer " + token)
                        .header("Spicylyrics-Version", "5.22.3")
                        .header("Origin", "https://xpui.app.spotify.com")
                        .header("Referer", "https://xpui.app.spotify.com/")
                        .header("Accept", "*/*")
                        .header("Content-Type", "application/json")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "cross-site")
                        .header("User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.7680.179 Spotify/1.2.88.483 Safari/537.36")
                        .header("Sec-Ch-Ua", "\"Not-A.Brand\";v=\"24\", \"Chromium\";v=\"146\"")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Priority", "u=1, i")
                        .header("Accept-Language", "en-Latn-US,en-US;q=0.9,en-Latn;q=0.8,en;q=0.7")
                        .header("Sec-Ch-Ua-Mobile", "?0")
                        .header("Sec-Ca-Ua-Platform", "\"Windows\"")
                        .build();

                lyricsClient.newCall(lyricsRequest).enqueue(new Callback() {
                    @Override
                    public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                        assert response.body() != null;
                        String contentFull = response.body().string();
                        XposedBridge.log("[SpotifyPlus] " + contentFull);

                        JsonArray array = new JsonParser().parseString(contentFull).getAsJsonObject().get("queries")
                                .getAsJsonArray();

                        if (array == null || array.isEmpty()) {
                            XposedBridge.log("Lyrics queries not found!");
                            return;
                        }

                        var thing = array.asList().stream().filter(x -> {
                            JsonObject obj = x.getAsJsonObject();
                            return obj.get("operation") != null || obj.get("operationId") != null
                                    || obj.get("result") != null;
                        }).collect(Collectors.toList());

                        if (thing.isEmpty()) {
                            XposedBridge.log("No lyrics result found!");
                            return;
                        }

                        JsonObject jsonObject = thing.get(0).getAsJsonObject().get("result").getAsJsonObject()
                                .get("data").getAsJsonObject();
                        String content = jsonObject.toString();
                        String type = jsonObject.get("Type").getAsString();
                        var writers = jsonObject.get("SongWriters");
                        String writtenBy;
                        if (writers != null) {
                            writtenBy = writers.getAsJsonArray().asList().stream().map(JsonElement::getAsString)
                                    .collect(Collectors.joining(", "));
                        } else {
                            writtenBy = "";
                        }

                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.post(() -> {
                            try {
                                Class<?> requestClass = bridge
                                        .findClass(FindClass.create()
                                                .matcher(ClassMatcher.create()
                                                        .modifiers(Modifier.PUBLIC | Modifier.FINAL).fieldCount(
                                                                1)
                                                        .addField(FieldMatcher
                                                                .create().modifiers(
                                                                        Modifier.PUBLIC | Modifier.FINAL)
                                                                .type(long.class))
                                                        .methods(MethodsMatcher.create()
                                                                .count(6)
                                                                .add(MethodMatcher.create()
                                                                        .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                                                                        .returnType(Object.class).paramCount(13))
                                                                .add(MethodMatcher.create()
                                                                        .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                                                                        .returnType(void.class).paramCount(12))
                                                                .add(MethodMatcher.create()
                                                                        .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                                                                        .returnType(boolean.class)
                                                                        .addParamType(Object.class)))))
                                        .get(0).getInstance(lpparm.classLoader);

                                ctor = requestClass.getConstructor(long.class);
                                ctor.setAccessible(true);
                            } catch (Exception e) {
                                XposedBridge.log(e);
                                Toast.makeText(activity, "Failed to load lyrics", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            if (type.equals("Syllable")) {
                                Gson gson = new Gson();
                                SyllableSyncedLyrics providerLyrics = gson.fromJson(content,
                                        SyllableSyncedLyrics.class);
                                ProviderLyrics lyrics = new ProviderLyrics();
                                lyrics.syllableLyrics = providerLyrics;
                                XposedBridge.log("[SpotifyPlus] Line Count: " + lyrics.syllableLyrics.content.size());

                                TransformedLyrics transformedLyrics = LyricUtilities.transformLyrics(lyrics, activity);

                                renderSyllableLyrics(activity, transformedLyrics.lyrics, lyricsContainer, track,
                                        writtenBy);
                            } else if (type.equals("Line")) {
                                Gson gson = new Gson();
                                LineSyncedLyrics providerLyrics = gson.fromJson(content, LineSyncedLyrics.class);
                                ProviderLyrics lyrics = new ProviderLyrics();
                                lyrics.lineLyrics = providerLyrics;

                                TransformedLyrics transformedLyrics = LyricUtilities.transformLyrics(lyrics, activity);

                                renderLineLyrics(activity, transformedLyrics.lyrics, lyricsContainer, track, writtenBy);
                            } else if (type.equals("Static")) {
                                Gson gson = new Gson();
                                // This is pretty pointless
                                // If Spotify doesn't have lyrics, you can't open this page
                                // And it's very likely that if a song has static lyrics, Spotify won't have the
                                // lryics
                                // I redact my statement, there have been a few times that I've seen static
                                // lyrics
                                // And hey, guess what? It actually works!
                                // I wrote this code and never cared enough to go find a song to test it on

                                StaticSyncedLyrics providerLyrics = gson.fromJson(content, StaticSyncedLyrics.class);

                                ProviderLyrics providerLyricsThing = new ProviderLyrics();
                                providerLyricsThing.staticLyrics = providerLyrics;

                                TransformedLyrics transformedLyrics = LyricUtilities
                                        .transformLyrics(providerLyricsThing, activity);
                                StaticSyncedLyrics lyrics = transformedLyrics.lyrics.staticLyrics;

                                for (var line : lyrics.lines) {
                                    FlexboxLayout layout = new FlexboxLayout(activity.getApplicationContext());
                                    layout.setFlexWrap(FlexWrap.WRAP);

                                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                                            RelativeLayout.LayoutParams.MATCH_PARENT,
                                            RelativeLayout.LayoutParams.WRAP_CONTENT);
                                    params.setMargins(dpToPx(15, activity), dpToPx(20, activity), dpToPx(15, activity),
                                            0);
                                    layout.setLayoutParams(params);

                                    TextView text = new TextView(activity);
                                    text.setText(line.text);

                                    text.setTextColor(Color.WHITE);
                                    text.setTextSize(26f);
                                    text.setTypeface(References.beautifulFont.get());

                                    layout.addView(text);
                                    lyricsContainer.addView(layout);
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailure(@NotNull Call call, @NotNull IOException e) {
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        Runnable runnable = () -> {
                            Toast.makeText(activity, "No lyrics found for this song", Toast.LENGTH_LONG).show();
                        };

                        mainHandler.post(runnable);
                    }
                });
            } catch (Exception ex) {

            }
            // String content = finalContent;
            // if (content.isBlank()) {
            // Handler mainHandler = new Handler(Looper.getMainLooper());
            // Runnable runnable = () -> {
            // Toast.makeText(activity, "No lyrics found for this song",
            // Toast.LENGTH_LONG).show();
            // };
            //
            // mainHandler.post(runnable);
            // return;
            // }
            //
            // handler.post(() -> {
            //
            // });
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void renderSyllableLyrics(Activity activity, ProviderLyrics providedLyrics, LinearLayout lyricsContainer,
            SpotifyTrack track, String writtenBy) {
        List<View> lines = new ArrayList<>();
        vocalGroups = new HashMap<>();
        rightContainer.removeView(syncButton);
        SharedPreferences prefs = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        boolean newScrollingSystem = prefs.getBoolean("experiment_scroll", false);
        currentLineSpacingMode = prefs.getString("line_spacing", "default");

        int lineSpacing;
        int fontSize;

        switch (currentLineSpacingMode) {
            case "compact":
                lineSpacing = 32;
                fontSize = 28;
                break;

            case "spacious":
                lineSpacing = 42;
                fontSize = 36;
                break;

            case "more":
                lineSpacing = 46;
                fontSize = 38;
                break;

            case "max":
                lineSpacing = 46;
                fontSize = 38;
                break;

            default:
                lineSpacing = 36;
                fontSize = 34;
                break;
        }

        Gson gson = new Gson();
        TransformedLyrics transformedLyrics = LyricUtilities.transformLyrics(providedLyrics, activity);
        SyllableSyncedLyrics lyrics = transformedLyrics.lyrics.syllableLyrics;

        int i = 0;
        for (var vocalGroup : lyrics.content) {
            if (vocalGroup instanceof Interlude) {
                Interlude interlude = (Interlude) vocalGroup;
                RelativeLayout topGroup = new RelativeLayout(activity);
                topGroup.setClipToPadding(false);
                topGroup.setClipChildren(false);

                FlexboxLayout vocalGroupContainer = new FlexboxLayout(activity.getApplicationContext());
                vocalGroupContainer.setClipToPadding(false);
                vocalGroupContainer.setClipChildren(false);

                if (interlude.time.startTime == 0) {
                    RelativeLayout.MarginLayoutParams params = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                    params.setMargins(dpToPx(30, activity), dpToPx(40, activity), 0, 0);
                    vocalGroupContainer.setLayoutParams(params);
                } else {
                    RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                    params.setMargins(dpToPx(30, activity), dpToPx(20, activity), 0, 0);
                    vocalGroupContainer.setLayoutParams(params);

                    if (i != lyrics.content.size() - 1
                            && ((SyllableVocalSet) lyrics.content.get(i + 1)).oppositeAligned) {
                        params.addRule(RelativeLayout.ALIGN_PARENT_END);
                        params.setMargins(0, dpToPx(20, activity), dpToPx(30, activity), 0);
                    }
                }

                List<SyncableVocals> visual = new ArrayList<>();
                visual.add(new InterludeVisual(vocalGroupContainer, interlude, activity));
                vocalGroups.put(vocalGroupContainer, visual);

                // Check opposite alignment

                topGroup.addView(vocalGroupContainer);
                lines.add(topGroup);
            } else if (vocalGroup instanceof SyllableVocalSet) {
                SyllableVocalSet set = (SyllableVocalSet) vocalGroup;

                RelativeLayout evenMoreTopGroup = new RelativeLayout(activity);
                evenMoreTopGroup.setClipToPadding(false);
                evenMoreTopGroup.setClipChildren(false);

                LinearLayout topGroup = new LinearLayout(activity);
                topGroup.setOrientation(LinearLayout.VERTICAL);
                topGroup.setClipToPadding(false);
                topGroup.setClipChildren(false);
                RelativeLayout.LayoutParams parms = new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                parms.setMargins(dpToPx(25, activity), dpToPx(lineSpacing, activity), dpToPx(35, activity), 0);

                topGroup.setLayoutParams(parms);

                FlexboxLayout vocalGroupContainer = new FlexboxLayout(activity.getApplicationContext());
                vocalGroupContainer.setFlexWrap(FlexWrap.WRAP);
                vocalGroupContainer.setClipToPadding(false);
                vocalGroupContainer.setClipChildren(false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                vocalGroupContainer.setLayoutParams(params);
                vocalGroupContainer.setPadding(dpToPx(6, activity), dpToPx(4, activity), dpToPx(6, activity),
                        dpToPx(4, activity));

                if (set.oppositeAligned) {
                    parms.addRule(RelativeLayout.ALIGN_PARENT_END);
                    parms.setMargins(dpToPx(35, activity), dpToPx(lineSpacing, activity), dpToPx(25, activity), 0);

                    vocalGroupContainer.setJustifyContent(JustifyContent.FLEX_END);
                }

                topGroup.addView(vocalGroupContainer);
                evenMoreTopGroup.addView(topGroup);
                lines.add(evenMoreTopGroup);

                List<SyllableVocals> vocals = new ArrayList<>();
                double startTime = set.lead.startTime;

                SyllableVocals sv = new SyllableVocals(vocalGroupContainer, set.lead.syllables, false, false,
                        set.oppositeAligned, activity, fontSize);
                sv.activityChanged.addListener(info -> {
                    View lineView = (View) info.view.getParent().getParent();
                    View scrollView = (View) lyricsContainer.getParent();

                    currentActiveLineView = lineView;
                    onActiveLineChanged(info.view, lyricsContainer);

                    if (newScrollingSystem) {
                        experimentalScrollToNewLine(lineView, lyricsContainer, info.immediate);
                    } else {
                        scrollToNewLine(lineView, (ScrollView) scrollView, info.immediate);
                    }
                });

                vocals.add(sv);

                if (set.background != null && !set.background.isEmpty()) {
                    FlexboxLayout backgroundVocalGroupContainer = new FlexboxLayout(activity.getApplicationContext());
                    backgroundVocalGroupContainer.setFlexWrap(FlexWrap.WRAP);
                    backgroundVocalGroupContainer.setClipToPadding(false);
                    backgroundVocalGroupContainer.setClipChildren(false);
                    backgroundVocalGroupContainer.setJustifyContent(
                            set.oppositeAligned ? JustifyContent.FLEX_END : JustifyContent.FLEX_START);
                    topGroup.addView(backgroundVocalGroupContainer);
                    backgroundVocalGroupContainer.setPadding(dpToPx(6, activity), 0, dpToPx(6, activity), 0);

                    for (var backgroundVocal : set.background) {
                        startTime = Math.min(startTime, backgroundVocal.startTime);
                        vocals.add(new SyllableVocals(backgroundVocalGroupContainer, backgroundVocal.syllables, true,
                                false, set.oppositeAligned, activity, fontSize));
                    }
                }

                final double finalStartTime = startTime;
                int radius = dpToPx(8, activity);
                final GradientDrawable highlightBackground = new GradientDrawable();
                highlightBackground.setColor(Color.WHITE);
                highlightBackground.setCornerRadius(radius);
                highlightBackground.setAlpha(0);
                highlightBackground.mutate();
                vocalGroupContainer.setBackground(highlightBackground);

                final float touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
                final float[] downX = new float[1];
                final float[] downY = new float[1];
                final boolean[] isDragging = new boolean[1];

                vocalGroupContainer.setOnTouchListener((v, event) -> {
                    if (experimentalTouchSurface != null) {
                        forwardTouchToSurface(v, experimentalTouchSurface, lyricsContainer, event);
                    }

                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX[0] = event.getX();
                            downY[0] = event.getY();
                            isDragging[0] = false;

                            ObjectAnimator startAnimation = ObjectAnimator.ofInt(highlightBackground, "alpha",
                                    highlightBackground.getAlpha(), 50)
                                    .setDuration(400);
                            startAnimation.setInterpolator(new DecelerateInterpolator(2.0f));
                            startAnimation.start();
                            break;

                        case MotionEvent.ACTION_MOVE: {
                            if (!isDragging[0]) {
                                float dx = event.getX() - downX[0];
                                float dy = event.getY() - downY[0];
                                if (Math.hypot(dx, dy) > touchSlop) {
                                    isDragging[0] = true;
                                }
                            }
                            break;
                        }

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            ObjectAnimator endAnimation = ObjectAnimator.ofInt(highlightBackground, "alpha",
                                    highlightBackground.getAlpha(), 0)
                                    .setDuration(400);
                            endAnimation.setInterpolator(new DecelerateInterpolator(2.0f));
                            endAnimation.start();

                            if (event.getActionMasked() == MotionEvent.ACTION_UP && !isDragging[0]) {
                                resumeAutoFollowAfterTap(lyricsContainer);
                                v.performClick();
                            } else {
                                isUserInteracting = false;
                            }
                            break;
                    }

                    return true;
                });

                vocalGroupContainer.setOnClickListener((v) -> {
                    resumeAutoFollowAfterTap(lyricsContainer);

                    try {
                        Object seekArg = ctor.newInstance((long) (finalStartTime * 1000));

                        if (seekInstance != null) {
                            Method method = bridge.findMethod(
                                    FindMethod.create()
                                            .searchInClass(Collections
                                                    .singletonList(bridge.getClassData(seekInstance.getClass())))
                                            .matcher(MethodMatcher.create()
                                                    .paramTypes(ctor.getDeclaringClass().getSuperclass())))
                                    .get(0).getMethodInstance(lpparm.classLoader);

                            Object block = method.invoke(seekInstance, seekArg);
                            XposedHelpers.callMethod(block, "blockingGet");
                        } else {
                            XposedBridge.log("[SpotifyPlus] p.mmm is null :(");
                        }
                    } catch (Exception e) {
                        XposedBridge.log(e);
                    }
                });

                List<SyncableVocals> syncedVocals = new ArrayList<>(vocals);
                vocalGroups.put(vocalGroupContainer, syncedVocals);
            }

            i++;
        }

        lines.forEach(lyricsContainer::addView);
        registerLineRoots(lines);

        if (!writtenBy.isBlank()) {
            TextView writtenByTextView = new TextView(activity.getApplicationContext());
            writtenByTextView.setText("Written by: " + writtenBy);
            writtenByTextView.setTextSize(16f);
            writtenByTextView.setTypeface(References.beautifulFont.get());
            writtenByTextView.setTextColor(Color.parseColor("#f5f5f5"));

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(dpToPx(30, activity), dpToPx(20, activity), dpToPx(30, activity), 0);
            writtenByTextView.setLayoutParams(p);

            lyricsContainer.addView(writtenByTextView);
        }

        View spacer = new View(activity);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                dpToPx(180, activity));
        spacer.setLayoutParams(spacerParams);
        lyricsContainer.addView(spacer);

        lyricsContainer.post(() -> {
            computeLogicalLayout(lyricsContainer);

            update(vocalGroups, track.position / 1000d, 1.0d / 60d, true);
            updateProgress(track.position, System.currentTimeMillis(), vocalGroups, (View) lyricsContainer.getParent());
            applyHeaderFadeToLines();
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void renderLineLyrics(Activity activity, ProviderLyrics providerLyricsThing, LinearLayout lyricsContainer,
            SpotifyTrack track, String writtenBy) {
        List<View> lines = new ArrayList<>();
        vocalGroups = new HashMap<>();
        Gson gson = new Gson();

        SharedPreferences prefs = activity.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        boolean newScrollingSystem = prefs.getBoolean("experiment_scroll", false);

        TransformedLyrics transformedLyrics = LyricUtilities.transformLyrics(providerLyricsThing, activity);

        LineSyncedLyrics lyrics = transformedLyrics.lyrics.lineLyrics;
        lineLyrics = lyrics;
        int lineSpacing = getConfiguredLineSpacingDp(currentLineSpacingMode);

        int i = 0;
        for (var vocalGroup : lyrics.content) {
            if (vocalGroup instanceof Interlude) {
                Interlude interlude = (Interlude) vocalGroup;

                RelativeLayout topGroup = new RelativeLayout(activity);
                topGroup.setClipToPadding(false);
                topGroup.setClipChildren(false);

                FlexboxLayout vocalGroupContainer = new FlexboxLayout(activity.getApplicationContext());
                vocalGroupContainer.setClipToPadding(false);
                vocalGroupContainer.setClipChildren(false);

                if (interlude.time.startTime == 0) {
                    RelativeLayout.MarginLayoutParams params = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                    params.setMargins(dpToPx(15, activity), dpToPx(40, activity), 0, 0);
                    vocalGroupContainer.setLayoutParams(params);
                } else {
                    RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                    params.setMargins(dpToPx(15, activity), dpToPx(20, activity), 0, 0);
                    vocalGroupContainer.setLayoutParams(params);

                    if (i != lyrics.content.size() - 1 && ((LineVocal) lyrics.content.get(i - 1)).oppositeAligned
                            && ((LineVocal) lyrics.content.get(i + 1)).oppositeAligned) {
                        params.addRule(RelativeLayout.ALIGN_PARENT_END);
                        params.setMargins(0, dpToPx(20, activity), dpToPx(15, activity), 0);
                    }
                }

                List<SyncableVocals> visual = new ArrayList<>();
                visual.add(new InterludeVisual(vocalGroupContainer, interlude, activity));
                vocalGroups.put(vocalGroupContainer, visual);

                topGroup.addView(vocalGroupContainer);
                lines.add(topGroup);
            } else if (vocalGroup instanceof LineVocal) {
                LineVocal vocal = (LineVocal) vocalGroup;

                RelativeLayout topGroup = new RelativeLayout(activity);
                topGroup.setClipToPadding(false);
                topGroup.setClipChildren(false);

                FlexboxLayout vocalGroupContainer = new FlexboxLayout(activity.getApplicationContext());
                vocalGroupContainer.setFlexWrap(FlexWrap.WRAP);
                vocalGroupContainer.setClipToPadding(false);
                vocalGroupContainer.setClipChildren(false);
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                params.setMargins(dpToPx(25, activity), dpToPx(lineSpacing, activity), dpToPx(30, activity), 0);

                if (vocal.oppositeAligned) {
                    params.addRule(RelativeLayout.ALIGN_PARENT_END);
                }

                vocalGroupContainer.setLayoutParams(params);
                topGroup.addView(vocalGroupContainer);

                LineVocals lv = new LineVocals(vocalGroupContainer, vocal, false, activity);
                lv.activityChanged.addListener(info -> {
                    View lineView = (View) info.view.getParent();
                    View scrollView = (View) lyricsContainer.getParent();

                    currentActiveLineView = lineView;
                    onActiveLineChanged(info.view, lyricsContainer);

                    if (newScrollingSystem) {
                        experimentalScrollToNewLine(lineView, lyricsContainer, info.immediate);
                    } else {
                        scrollToNewLine(lineView, (ScrollView) scrollView, info.immediate);
                    }
                });

                vocalGroups.put(vocalGroupContainer, List.of(lv));

                final double finalStartTime = lv.startTime;
                int radius = dpToPx(8, activity);
                final GradientDrawable highlightBackground = new GradientDrawable();
                highlightBackground.setColor(Color.WHITE);
                highlightBackground.setCornerRadius(radius);
                highlightBackground.setAlpha(0);
                highlightBackground.mutate();
                vocalGroupContainer.setBackground(highlightBackground);

                final float touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
                final float[] downX = new float[1];
                final float[] downY = new float[1];
                final boolean[] isDragging = new boolean[1];

                vocalGroupContainer.setOnTouchListener((v, event) -> {
                    if (experimentalTouchSurface != null) {
                        forwardTouchToSurface(v, experimentalTouchSurface, lyricsContainer, event);
                    }

                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX[0] = event.getX();
                            downY[0] = event.getY();
                            isDragging[0] = false;

                            ObjectAnimator startAnimation = ObjectAnimator.ofInt(highlightBackground, "alpha",
                                    highlightBackground.getAlpha(), 50)
                                    .setDuration(400);
                            startAnimation.setInterpolator(new DecelerateInterpolator(2.0f));
                            startAnimation.start();
                            break;

                        case MotionEvent.ACTION_MOVE: {
                            if (!isDragging[0]) {
                                float dx = event.getX() - downX[0];
                                float dy = event.getY() - downY[0];
                                if (Math.hypot(dx, dy) > touchSlop) {
                                    isDragging[0] = true;
                                }
                            }
                            break;
                        }

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            ObjectAnimator endAnimation = ObjectAnimator.ofInt(highlightBackground, "alpha",
                                    highlightBackground.getAlpha(), 0)
                                    .setDuration(400);
                            endAnimation.setInterpolator(new DecelerateInterpolator(2.0f));
                            endAnimation.start();

                            if (event.getActionMasked() == MotionEvent.ACTION_UP && !isDragging[0]) {
                                resumeAutoFollowAfterTap(lyricsContainer);
                                v.performClick();
                            } else {
                                isUserInteracting = false;
                            }
                            break;
                    }

                    return true;
                });

                vocalGroupContainer.setOnClickListener((v) -> {
                    resumeAutoFollowAfterTap(lyricsContainer);

                    try {
                        Object seekArg = ctor.newInstance((long) (finalStartTime * 1000));

                        if (seekInstance != null) {
                            Method method = bridge.findMethod(
                                    FindMethod.create()
                                            .searchInClass(Collections
                                                    .singletonList(bridge.getClassData(seekInstance.getClass())))
                                            .matcher(MethodMatcher.create()
                                                    .paramTypes(ctor.getDeclaringClass().getSuperclass())))
                                    .get(0).getMethodInstance(lpparm.classLoader);

                            Object block = method.invoke(seekInstance, seekArg);
                            XposedHelpers.callMethod(block, "blockingGet");
                        } else {
                            XposedBridge.log("[SpotifyPlus] p.mmm is null :(");
                        }
                    } catch (Exception e) {
                        XposedBridge.log(e);
                    }
                });

                lines.add(topGroup);
            }
            i++;
        }

        lines.forEach(lyricsContainer::addView);
        registerLineRoots(lines);

        if (!writtenBy.isBlank()) {
            TextView writtenByTextView = new TextView(activity.getApplicationContext());
            writtenByTextView.setText("Written by: " + writtenBy);
            writtenByTextView.setTextSize(16f);
            writtenByTextView.setTypeface(References.beautifulFont.get());
            writtenByTextView.setTextColor(Color.parseColor("#f5f5f5"));

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(dpToPx(30, activity), dpToPx(20, activity), dpToPx(30, activity), 0);
            writtenByTextView.setLayoutParams(p);

            lyricsContainer.addView(writtenByTextView);
        }

        View spacer = new View(activity);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                dpToPx(180, activity));
        spacer.setLayoutParams(spacerParams);
        lyricsContainer.addView(spacer);

        lyricsContainer.post(() -> {
            computeLogicalLayout(lyricsContainer);

            update(vocalGroups, track.position / 1000d, 1.0d / 60d, true);
            updateProgress(track.position, System.currentTimeMillis(), vocalGroups, (View) lyricsContainer.getParent());
            applyHeaderFadeToLines();
        });
    }

    private void update(Map<FlexboxLayout, List<SyncableVocals>> vocalGroups, double timestamp, double deltaTime,
            boolean skipped) {
        try {
            for (var vocalGroup : vocalGroups.values()) {
                for (var vocal : vocalGroup) {
                    vocal.animate(timestamp, deltaTime, skipped);
                }
            }
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    private long lastUpdatedAt = 0;
    private double lastTimestamp = 0;

    private void updateProgress(long initialPositionS, double startedSyncAtS,
            Map<FlexboxLayout, List<SyncableVocals>> vocalGroups, View scrollView) {
        mainLoop = new Thread(() -> {
            try {
                int[] syncTimings = { 50, 100, 150, 750 };
                int syncIndex = 0;
                long nextSyncAt = syncTimings[0];
                long initialPosition = initialPositionS;
                double startedSyncAt = startedSyncAtS;

                while (!stop) {
                    long updatedAt = System.currentTimeMillis();

                    if (isPlaying) {
                        if (updatedAt > startedSyncAt + nextSyncAt) {
                            // Get the current position from Spotify
                            long position = References.getCurrentPlaybackPosition(bridge, lpparm);
                            if (position != -1) {
                                initialPosition = position;
                                startedSyncAt = updatedAt;

                                syncIndex++;

                                if (syncIndex < syncTimings.length) {
                                    nextSyncAt = syncTimings[syncIndex];
                                } else {
                                    nextSyncAt = 33;
                                }
                            }
                        }

                        double syncedTimestamp = (initialPosition + (updatedAt - startedSyncAt)) / 1000d;
                        double deltaTime = (updatedAt - lastUpdatedAt) / 1000d;

                        update(vocalGroups, syncedTimestamp, deltaTime,
                                Math.abs(syncedTimestamp - lastTimestamp) > 0.075d);
                        updateLineAnimations(deltaTime, scrollView);
                        lastTimestamp = syncedTimestamp;
                    }

                    lastUpdatedAt = updatedAt;

                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                XposedBridge.log(e);
            }
        });

        mainLoop.start();
    }

    private void registerLineRoots(List<View> roots) {
        lineRoots.clear();
        lineIndex.clear();

        for (int i = 0; i < roots.size(); i++) {
            View v = roots.get(i);
            lineRoots.add(v);
            lineIndex.put(v, i);
        }

        activeLineIndex = -1;
        lastAppliedActiveIndex = Integer.MIN_VALUE;
        lastBlurAllowed = true;
    }

    private void onActiveLineChanged(View anyViewInsideLine, LinearLayout lyricsContainer) {
        View root = findLineRoot(anyViewInsideLine, lyricsContainer);
        if (root == null)
            return;

        Integer idx = lineIndex.get(root);
        if (idx == null)
            idx = lineRoots.indexOf(root);
        if (idx == null || idx < 0)
            return;

        activeLineIndex = idx;

        lyricsContainer.post(() -> {
            applyCurrentLineTranslations();
            applyLineFocusEffects();
        });
    }

    private View findLineRoot(View v, LinearLayout lyricsContainer) {
        View cur = v;
        while (cur != null) {
            ViewParent p = cur.getParent();
            if (p == lyricsContainer)
                return cur;
            if (!(p instanceof View))
                return null;
            cur = (View) p;
        }
        return null;
    }

    private void applyLineFocusEffects() {
        boolean blurAllowed = isFollowingPlayback && !isUserInteracting;

        // if user is scrolling or we aren't following playback, clear everything
        if (!blurAllowed) {
            if (lastBlurAllowed) {
                clearAllLineBlur();
                lastBlurAllowed = false;
            }
            return;
        }

        lastBlurAllowed = true;

        if (activeLineIndex < 0) {
            clearAllLineBlur();
            return;
        }

        // no need to redo if same active line and blur is allowed
        if (activeLineIndex == lastAppliedActiveIndex)
            return;
        lastAppliedActiveIndex = activeLineIndex;

        for (int i = 0; i < lineRoots.size(); i++) {
            View line = lineRoots.get(i);

            int dist = Math.abs(i - activeLineIndex);
            int bucket = Math.min(dist, BLUR_MAX_DISTANCE);

            float radiusDp = BLUR_LEVELS_DP[bucket];
            float radiusPx = dpToPxF(radiusDp, line);

            setLineBlur(line, radiusPx);
        }
    }

    private void clearAllLineBlur() {
        for (View line : lineRoots) {
            setLineBlur(line, 0f);
        }
    }

    private float dpToPxF(float dp, View v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                v.getResources().getDisplayMetrics());
    }

    private void setLineBlur(View line, float radiusPx) {
        // No blur requested → clear effect
        if (radiusPx <= 0.01f) {
            RenderEffectCompat.clear(line);
            // optional: drop hardware layer if you want
            // line.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }

        // optional: force hardware layer for performance stability
        // line.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        RenderEffectCompat.blur(line, radiusPx);
    }

    private static final class RenderEffectCompat {
        private static boolean checked;
        private static boolean available;

        private static Class<?> cRenderEffect;
        private static java.lang.reflect.Method mCreateBlurEffect;
        private static java.lang.reflect.Method mSetRenderEffect;

        // cache by integer px so we aren’t allocating a new effect constantly
        private static final Map<Integer, Object> blurCache = new HashMap<>();

        private static void ensure() {
            if (checked)
                return;
            checked = true;

            try {
                cRenderEffect = Class.forName("android.graphics.RenderEffect");
                mCreateBlurEffect = cRenderEffect.getMethod(
                        "createBlurEffect",
                        float.class, float.class, android.graphics.Shader.TileMode.class);
                mSetRenderEffect = View.class.getMethod("setRenderEffect", cRenderEffect);
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }

        static void blur(View v, float radiusPx) {
            ensure();
            if (!available)
                return;

            try {
                int key = Math.max(1, Math.round(radiusPx));
                Object effect = blurCache.get(key);
                if (effect == null) {
                    effect = mCreateBlurEffect.invoke(null, (float) key, (float) key,
                            android.graphics.Shader.TileMode.CLAMP);
                    blurCache.put(key, effect);
                }
                mSetRenderEffect.invoke(v, effect);
            } catch (Throwable ignored) {
            }
        }

        static void clear(View v) {
            ensure();
            if (!available)
                return;

            try {
                mSetRenderEffect.invoke(v, new Object[] { null });
            } catch (Throwable ignored) {
            }
        }
    }

    private void renderSyncLyricsSplitting(Activity activity, LinearLayout lyricsContainer, ScrollView scroller,
            String id, View root) {
        var font = References.beautifulFont.get();

        for (var item : lineLyrics.content) {
            if (item instanceof LineVocal) {
                LineVocal vocal = (LineVocal) item;

                FlexboxLayout lineGroup = new FlexboxLayout(activity.getApplicationContext());
                lineGroup.setFlexWrap(FlexWrap.WRAP);
                lineGroup.setClipToPadding(false);
                lineGroup.setClipChildren(false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lineGroup.setLayoutParams(params);
                lineGroup.setPadding(dpToPx(6, activity), dpToPx(8, activity), dpToPx(6, activity),
                        dpToPx(8, activity));

                String content = vocal.text;

                SyllableVocalSet set = new SyllableVocalSet();
                set.type = "Vocal";
                set.oppositeAligned = vocal.oppositeAligned;
                SyllableVocal vocalThing = new SyllableVocal();
                vocalThing.syllables = new ArrayList<>();
                set.lead = vocalThing;

                List<Integer> wordStartsForThisLine = new ArrayList<>();
                int syllableIndex = 0;

                if (vocal.text.contains("(")) {
                    // Filter out backing vocals
                    set.background = new ArrayList<>();

                    var split = content.split("\\(");
                    var splitAfter = content.split("\\)");

                    if (split.length == 2) {
                        String lineBefore = split[1];
                        // backgroundVocals = lineBefore.split("\\)")[0];

                        String outputBefore = split[0].trim();
                        String outputAfter = "";

                        if (splitAfter.length == 2) {
                            outputAfter = splitAfter[1].trim();
                        }

                        content = (outputBefore + " " + outputAfter).trim();
                    } else {
                        // (Hey!) I don't know about you (I don't know about you) but I'm feeling 22

                        String first = split[1];
                        String second = first.split("\\)")[0]; // Hey!
                        String third = split[2];
                        String fourth = third.split("\\)")[0]; // I don't know about you

                        // backgroundVocals = second + " " + fourth; // Hey! I don't know about you

                        String leadFirst = split[0].trim(); // ""
                        String leadSecond = splitAfter[1]; // I don't know about you (I don't know about you
                        String leadThird = splitAfter[2]; // but I'm feeling 22

                        content = (leadFirst + " " + second.split("\\(")[0] + " " + third).trim();
                    }
                }

                if (content.isEmpty())
                    continue;

                String leadVocals = content;
                XModuleResources res = References.modResources;
                FrameLayout wordPopup = root
                        .findViewById(res.getIdentifier("split_word_view", "id", "com.lenerd46.spotifyplus"));
                LinearLayout wordContainer = root
                        .findViewById(res.getIdentifier("split_word_text_container", "id", "com.lenerd46.spotifyplus"));
                LinearLayout bottomBar = root
                        .findViewById(res.getIdentifier("bottom_bar", "id", "com.lenerd46.spotifyplus"));
                // It's not really on the bottom, but idk

                View cursor = root
                        .findViewById(res.getIdentifier("split_word_caret", "id", "com.lenerd46.spotifyplus"));
                Button leftButton = root
                        .findViewById(res.getIdentifier("split_word_left", "id", "com.lenerd46.spotifyplus"));
                Button rightButton = root
                        .findViewById(res.getIdentifier("split_word_right", "id", "com.lenerd46.spotifyplus"));
                Button confirmButton = root
                        .findViewById(res.getIdentifier("split_word_confirm", "id", "com.lenerd46.spotifyplus"));
                Button splitButton = root
                        .findViewById(res.getIdentifier("split_word_split", "id", "com.lenerd46.spotifyplus"));

                Button startSyncConfirmButton = root
                        .findViewById(res.getIdentifier("sync_confirm_button", "id", "com.lenerd46.spotifyplus"));
                Button skipButton = root
                        .findViewById(res.getIdentifier("sync_skip_button", "id", "com.lenerd46.spotifyplus"));

                leftButton.setOnClickListener((btn) -> {
                    cursorPosition -= cursorPosition <= 1 ? 0 : 1;

                    TextView text = (TextView) wordContainer.getChildAt(cursorPosition);
                    var cursorX = text.getX() + text.getWidth();

                    cursor.setX(cursorX);
                });

                rightButton.setOnClickListener((btn) -> {
                    cursorPosition += cursorPosition == wordContainer.getChildCount() - 2 ? 0 : 1;

                    TextView text = (TextView) wordContainer.getChildAt(cursorPosition);
                    var cursorX = text.getX() + text.getWidth();

                    cursor.setX(cursorX);
                });

                splitButton.setOnClickListener((btn) -> {
                    splits.add(cursorPosition);
                    TextView text = (TextView) wordContainer.getChildAt(cursorPosition);

                    LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    layoutParams.setMargins(0, 0, dpToPx(2, activity), 0);

                    text.setLayoutParams(layoutParams);
                });

                confirmButton.setOnClickListener((btn) -> {
                    SyllableVocalSet vocalSet = (SyllableVocalSet) syllables.get(selectedLineIndex);
                    List<Integer> wordStarts = wordStartIndices.get(selectedLineIndex);
                    int syllableStartIndex = wordStarts.get(selectedWordIndex);
                    String word = currentWordText;

                    List<SyllableMetadata> parts = new ArrayList<>();
                    int start = 0;

                    for (var index : splits) {
                        SyllableMetadata newMetadata = new SyllableMetadata();
                        newMetadata.text = word.substring(start, index);
                        newMetadata.isPartOfWord = true;

                        parts.add(newMetadata);
                        start = index;
                    }

                    SyllableMetadata newMetadata = new SyllableMetadata();
                    newMetadata.text = word.substring(start);
                    newMetadata.isPartOfWord = false;

                    parts.add(newMetadata);

                    vocalSet.lead.syllables.remove(syllableStartIndex);
                    vocalSet.lead.syllables.addAll(syllableStartIndex, parts);

                    int delta = parts.size() - 1;
                    for (int i = selectedWordIndex + 1; i < wordStarts.size(); i++) {
                        wordStarts.set(i, wordStarts.get(i) + delta);
                    }

                    wordPopup.setVisibility(View.GONE);

                    selectedLineIndex = 0;
                    selectedWordIndex = 0;
                });

                startSyncConfirmButton.setOnClickListener((btn) -> {
                    lyricsContainer.removeAllViews();
                    bottomBar.setVisibility(LinearLayout.GONE);
                    renderSyncLyrics(activity, lyricsContainer, scroller, id);
                });

                skipButton.setOnClickListener((btn) -> {
                    lyricsContainer.removeAllViews();
                    bottomBar.setVisibility(LinearLayout.GONE);
                    renderSyncLyrics(activity, lyricsContainer, scroller, id);
                });

                for (var word : leadVocals.split(" ")) {
                    var button = new TextView(activity);
                    button.setText(word);
                    button.setTextColor(0xFFE0E0E0);
                    button.setTextSize(32f);
                    button.setPadding(0, 0, dpToPx(1, activity), 0);
                    button.setTypeface(font);

                    FlexboxLayout.LayoutParams textParams = new FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT, FlexboxLayout.LayoutParams.WRAP_CONTENT);
                    textParams.setMargins(0, 0, dpToPx(5, activity), 0);
                    button.setLayoutParams(textParams);

                    button.setOnClickListener((btn) -> {
                        TextView self = (TextView) btn;

                        splits.clear();
                        cursorPosition = 1;
                        cursor.setX(wordContainer.getChildAt(0).getX());

                        for (int i = wordContainer.getChildCount() - 1; i >= 0; i--) {
                            View child = wordContainer.getChildAt(i);
                            if (child instanceof TextView) {
                                wordContainer.removeViewAt(i);
                            }
                        }

                        ViewGroup lineLayout = (ViewGroup) self.getParent();

                        selectedLineIndex = lyricsContainer.indexOfChild(lineLayout);
                        selectedWordIndex = lineLayout.indexOfChild(self);
                        currentWordText = self.getText().toString();

                        for (var letter : ((String) (self.getText())).toCharArray()) {
                            TextView text = new TextView(activity);
                            text.setText(String.valueOf(letter));
                            text.setTextColor(0x3CFFFFFF);
                            text.setTextSize(26f);
                            text.setTypeface(font);

                            wordContainer.addView(text);
                        }

                        wordPopup.setVisibility(FrameLayout.VISIBLE);
                    });

                    lineGroup.addView(button);
                    wordStartsForThisLine.add(syllableIndex);

                    SyllableMetadata metadata = new SyllableMetadata();
                    metadata.text = word;
                    metadata.isPartOfWord = false;

                    set.lead.syllables.add(metadata);
                    syllableIndex++;
                }

                syllables.add(set);
                wordStartIndices.add(wordStartsForThisLine);
                lyricsContainer.addView(lineGroup);
            }
        }
    }

    private int selectedLineIndex = 0;
    private int selectedWordIndex = 0;
    private int cursorPosition = 0;

    private final List<Object> syllables = new ArrayList<>();
    private final List<List<Integer>> wordStartIndices = new ArrayList<>();
    private final List<Integer> splits = new ArrayList<>();
    private String currentWordText = "";

    @SuppressLint("ClickableViewAccessibility")
    private void renderSyncLyrics(Activity activity, LinearLayout lyricsContainer, ScrollView scroller, String id) {
        AtomicInteger index = new AtomicInteger();
        AtomicInteger wordIndex = new AtomicInteger();
        AtomicInteger lineIndex = new AtomicInteger();
        AtomicLong startedAt = new AtomicLong();
        AtomicInteger lineCount = new AtomicInteger();
        final SyllableVocal[] currentLine = { null };
        var font = References.beautifulFont.get();

        final boolean[] started = { false };
        List<LineVocal> lines = lineLyrics.content.stream().filter(x -> x instanceof LineVocal).map(x -> (LineVocal) x)
                .collect(Collectors.toList());
        lineCount.set(lines.size());

        lyricsContainer.post(() -> {
            for (var lineThing : syllables) {
                SyllableVocalSet line = (SyllableVocalSet) lineThing;
                FlexboxLayout layout = new FlexboxLayout(activity.getApplicationContext());
                layout.setFlexWrap(FlexWrap.WRAP);
                layout.setClipToPadding(false);
                layout.setClipChildren(false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                layout.setLayoutParams(params);
                layout.setPadding(dpToPx(6, activity), dpToPx(8, activity), dpToPx(6, activity), dpToPx(8, activity));

                for (int j = 0; j < line.lead.syllables.size(); j++) {
                    TextView text = new TextView(activity);
                    text.setText(line.lead.syllables.get(j).text);
                    text.setTextColor(0x3CFFFFFF);
                    text.setTextSize(32f);
                    text.setPadding(0, 0, dpToPx(1, activity), 0);
                    text.setTypeface(font);

                    FlexboxLayout.LayoutParams textParams = new FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT, FlexboxLayout.LayoutParams.WRAP_CONTENT);
                    textParams.setMargins(0, 0, dpToPx(5, activity), 0);
                    text.setLayoutParams(textParams);

                    layout.addView(text);
                }

                lyricsContainer.addView(layout);
            }

            View spacer = new View(activity);
            LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT, dpToPx(180, activity));
            spacer.setLayoutParams(spacerParams);
            lyricsContainer.addView(spacer);

            Toast.makeText(activity, "Tap when you're ready", Toast.LENGTH_SHORT).show();
        });

        lyricsContainer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (!started[0])
                    return true;

                View view = lyricsContainer.getChildAt(lineIndex.get());
                if (!(view instanceof FlexboxLayout))
                    return true;

                FlexboxLayout container = (FlexboxLayout) view;
                TextView textView = (TextView) container.getChildAt(wordIndex.get());

                double seconds = (System.currentTimeMillis() - startedAt.get()) / 1000.0;

                if (currentLine[0] == null) {
                    SyllableVocal temp = new SyllableVocal();
                    temp.startTime = seconds;
                    temp.syllables = new ArrayList<>();

                    currentLine[0] = temp;
                }

                SyllableVocal current = ((SyllableVocalSet) syllables.get(lineIndex.get())).lead;

                if (wordIndex.get() == 0) {
                    current.startTime = seconds;
                }

                current.syllables.get(wordIndex.get()).startTime = seconds;

                textView.setShadowLayer(4f, 2f, 2f, Color.WHITE);
                textView.animate().scaleX(1.05f).scaleY(1.05f).translationY(-dpToPx(2, activity))
                        .setInterpolator(new OvershootInterpolator()).setDuration(250).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (!started[0]) {
                    started[0] = true;

                    try {
                        Object seekArg = ctor.newInstance(0L);

                        if (seekInstance != null) {
                            Method method = bridge
                                    .findMethod(FindMethod.create()
                                            .searchInClass(Collections
                                                    .singletonList(bridge.getClassData(seekInstance.getClass())))
                                            .matcher(MethodMatcher.create()
                                                    .paramTypes(ctor.getDeclaringClass().getSuperclass())))
                                    .get(0).getMethodInstance(lpparm.classLoader);

                            Object block = method.invoke(seekInstance, seekArg);
                            XposedHelpers.callMethod(block, "blockingGet");
                        } else {
                            XposedBridge.log("[SpotifyPlus] p.mmm is null :(");
                        }
                    } catch (Exception e) {
                        XposedBridge.log(e);
                    }

                    startedAt.set(System.currentTimeMillis());
                    return true;
                }

                View view = lyricsContainer.getChildAt(lineIndex.get());
                if (!(view instanceof FlexboxLayout))
                    return true;

                FlexboxLayout container = (FlexboxLayout) view;

                TextView textView = (TextView) container.getChildAt(wordIndex.get());
                LineVocal line = lines.get(lineIndex.get());

                double seconds = (System.currentTimeMillis() - startedAt.get()) / 1000.0;

                ((SyllableVocalSet) syllables.get(lineIndex.get())).lead.syllables
                        .get(wordIndex.get()).endTime = seconds;

                textView.animate().scaleX(1f).scaleY(1f).translationY(0f).setInterpolator(new OvershootInterpolator())
                        .setDuration(250).start();
                textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);

                wordIndex.getAndIncrement();
                index.getAndIncrement();

                // We've reached the last word in the line
                if (wordIndex.get() == container.getChildCount()) {
                    ((SyllableVocalSet) syllables.get(lineIndex.get())).lead.endTime = seconds;

                    wordIndex.set(0);
                    lineIndex.getAndIncrement();

                    // SyllableVocalSet newSet = new SyllableVocalSet();
                    // newSet.type = "Vocal";
                    // newSet.oppositeAligned = line.oppositeAligned;
                    // newSet.lead = currentLine[0];
                    //
                    // vocals.add(newSet);

                    // currentLine[0] = null;
                    scrollToNewLine(lyricsContainer.getChildAt(lineIndex.get()), scroller, false);
                }

                // We've reached the end of the song
                if (lineIndex.get() == lineCount.get()) {
                    SyllableSyncedLyrics syllableLyrics = new SyllableSyncedLyrics();
                    syllableLyrics.content = syllables;
                    syllableLyrics.startTime = ((SyllableVocalSet) (syllables.get(0))).lead.startTime;
                    syllableLyrics.endTime = ((SyllableVocalSet) (syllables.get(syllables.size() - 1))).lead.endTime;

                    Gson gson = new Gson();
                    String json = gson.toJson(syllableLyrics);

                    OkHttpClient client = new OkHttpClient();
                    MediaType jsonType = MediaType.get("application/json; charset=utf-8");
                    RequestBody bodyRequest = RequestBody.create(json, jsonType);

                    MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("file", id + ".json", bodyRequest).build();
                    Request request = new Request.Builder().url("https://spotifyplus.lenerd.tech/api/lyrics/" + id)
                            .post(body).build();
                    client.newCall(request).enqueue(new Callback() {
                        @Override
                        public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                            if (response.isSuccessful()) {
                                Handler mainHandler = new Handler(Looper.getMainLooper());
                                mainHandler.post(() -> {
                                    Toast.makeText(activity, "Lyrics Uploaded!", Toast.LENGTH_LONG).show();
                                });

                                XposedBridge.log("[SpotifyPlus] Success!");

                                activity.onBackPressed();
                            } else {
                                Handler mainHandler = new Handler(Looper.getMainLooper());
                                mainHandler.post(() -> {
                                    Toast.makeText(activity, "Failed to upload lyrics", Toast.LENGTH_LONG).show();
                                });

                                XposedBridge.log("[SpotifyPlus] " + response.code() + " " + response.message());
                            }
                        }

                        @Override
                        public void onFailure(@NotNull Call call, @NotNull IOException e) {
                            XposedBridge.log("[SpotifyPlus] Failed to upload file!");
                            XposedBridge.log(e);
                        }
                    });
                }
            }

            return true;
        });
    }

    private void updateLineAnimations(double deltaTime, View containerView) {
        long currentTime = System.currentTimeMillis();
        List<View> views = new ArrayList<>(lineSprings.keySet());

        containerView.post(() -> {
            if (isUserInteracting) {
                applyHeaderFadeToLines();
                return;
            }

            for (View line : views) {
                Spring spring = lineSprings.get(line);
                if (spring == null)
                    continue;

                Long startTime = lineAnimationStartTimes.get(line);
                if (startTime != null) {
                    if (currentTime < startTime) {
                        continue;
                    } else {
                        lineAnimationStartTimes.remove(line);
                    }
                }

                double newOffset = spring.update(deltaTime);
                line.setTranslationY(getFinalLineTranslationY(line, newOffset));
            }

            applyHeaderFadeToLines();
        });
    }

    private void experimentalScrollToNewLine(View activeLine, LinearLayout lyricsContainer, boolean immediate) {
        if (isUserInteracting || !isFollowingPlayback)
            return;

        List<View> allLines = new ArrayList<>();
        for (int i = 0; i < lyricsContainer.getChildCount(); i++) {
            allLines.add(lyricsContainer.getChildAt(i));
        }

        View finalLine = activeLine;
        while (finalLine.getParent() != lyricsContainer && finalLine.getParent() != null) {
            finalLine = (View) finalLine.getParent();
        }

        int activeIndex = allLines.indexOf(finalLine);
        if (activeIndex == -1)
            return;

        final int finalActiveIndex = activeIndex;
        final View finalLineForCalc = finalLine;

        lyricsContainer.post(() -> {
            try {
                if (lyricsContainer.getParent() instanceof View) {
                    viewportHeight = ((View) lyricsContainer.getParent()).getHeight();
                }
                if (viewportHeight <= 0)
                    return;

                int activeLineTop = finalLineForCalc.getTop();
                int activeLineHeight = finalLineForCalc.getHeight();

                int screenTargetY = (int) (viewportHeight * SCROLL_POSITION_RATIO) - (activeLineHeight / 2);

                double newGlobalOffset = screenTargetY - activeLineTop;
                targetScrollOffset = newGlobalOffset;

                limitScrollBounds(lyricsContainer);

                long currentTime = System.currentTimeMillis();

                for (int i = 0; i < allLines.size(); i++) {
                    View line = allLines.get(i);
                    Spring spring = lineSprings.get(line);

                    if (spring == null) {
                        spring = new Spring(targetScrollOffset,
                                SCROLL_SPRING_DAMPING,
                                SCROLL_SPRING_FREQUENCY);
                        lineSprings.put(line, spring);
                    }

                    if (immediate) {
                        spring.set(targetScrollOffset);
                        line.setTranslationY(getFinalLineTranslationY(line, targetScrollOffset));
                        lineAnimationStartTimes.remove(line);
                    } else {
                        long delay;
                        int distance = Math.abs(i - finalActiveIndex);

                        if (i == finalActiveIndex) {
                            delay = 0;
                        } else if (i < finalActiveIndex) {
                            delay = (long) (distance * LINE_ANIMATION_DELAY * 0.3);
                        } else {
                            delay = (long) (distance * LINE_ANIMATION_DELAY);
                        }

                        spring.finalPosition = targetScrollOffset;

                        if (delay > 0) {
                            lineAnimationStartTimes.put(line, currentTime + delay);
                        } else {
                            lineAnimationStartTimes.remove(line);
                        }
                    }
                }
            } catch (Exception e) {
                XposedBridge.log(e);
            }
        });
    }

    private void resumeAutoFollowAfterTap(LinearLayout lyricsContainer) {
        isUserInteracting = false;
        isFollowingPlayback = true;

        if (inertiaAnimator != null) {
            inertiaAnimator.cancel();
            inertiaAnimator = null;
        }

        for (View line : lineRoots) {
            Spring spring = lineSprings.get(line);
            if (spring != null) {
                spring.set(targetScrollOffset);
            }
        }

        lyricsContainer.post(() -> {
            applyCurrentLineTranslations();
            applyLineFocusEffects();
            applyHeaderFadeToLines();
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private boolean handleTouchSurfaceEvent(View touchSurface, LinearLayout contentContainer, MotionEvent event) {
        closeButtonHandler.removeCallbacksAndMessages(closeButtonRunnable);
        rightContainer.animate().alpha(0.8f).setDuration(200).withEndAction(() -> {
            closeButtonHandler.postDelayed(closeButtonRunnable, 3000);
        }).start();

        boolean result = gestureDetector.onTouchEvent(event);

        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            isUserInteracting = false;
            limitScrollBounds(contentContainer);
            touchSurface.post(this::applyHeaderFadeToLines);

            if (!isFollowingPlayback && currentActiveLineView != null) {
                View parent = (View) contentContainer.getParent();
                if (parent != null) {
                    float viewportH = parent.getHeight();

                    float activeTop = currentActiveLineView.getTop() + currentActiveLineView.getTranslationY();
                    float activeBottom = activeTop + currentActiveLineView.getHeight();

                    boolean visible = activeBottom > 0 && activeTop < viewportH;

                    if (visible) {
                        isFollowingPlayback = true;
                        contentContainer.post(this::applyLineFocusEffects);
                    }
                }
            }
        }

        return result;
    }

    private boolean forwardTouchToSurface(View child, View touchSurface, LinearLayout contentContainer,
            MotionEvent event) {
        MotionEvent forwarded = MotionEvent.obtain(event);

        int[] childLoc = new int[2];
        int[] surfaceLoc = new int[2];
        child.getLocationOnScreen(childLoc);
        touchSurface.getLocationOnScreen(surfaceLoc);

        float newX = event.getRawX() - surfaceLoc[0];
        float newY = event.getRawY() - surfaceLoc[1];
        forwarded.setLocation(newX, newY);

        boolean handled = handleTouchSurfaceEvent(touchSurface, contentContainer, forwarded);
        forwarded.recycle();
        return handled;
    }

    private void computeLogicalLayout(LinearLayout container) {
        logicalLineTops.clear();
        lineBaseOffsets.clear();

        int width = container.getWidth();
        if (width == 0) {
            width = container.getResources().getDisplayMetrics().widthPixels;
        }

        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        container.measure(widthSpec, heightSpec);

        int y = container.getPaddingTop();

        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();

            y += lp.topMargin;
            logicalLineTops.put(child, y);
            y += child.getMeasuredHeight() + lp.bottomMargin;
        }

        contentHeight = y + container.getPaddingBottom();

        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            Integer logicalTop = logicalLineTops.get(child);
            if (logicalTop == null)
                continue;

            int actualTop = child.getTop();
            lineBaseOffsets.put(child, (double) (logicalTop - actualTop));
        }

        View parent = (View) container.getParent();
        if (parent != null) {
            viewportHeight = parent.getHeight();
        }
    }

    private int getConfiguredLineSpacingDp(String spacingMode) {
        switch (spacingMode) {
            case "compact":
                return 32;
            case "spacious":
                return 42;
            case "more":
                return 46;
            case "max":
                return 46;
            default:
                return 36;
        }
    }

    private boolean isMaxPairSpacingEnabled() {
        return currentLineSpacingMode.equals("max");
    }

    private float getFocusedPairIsolationAmountPx(View anyLine) {
        float vh = (float) viewportHeight;

        if (vh <= 0f) {
            ViewParent parent = anyLine.getParent();
            if (parent instanceof View) {
                vh = ((View) parent).getHeight();
            }
        }

        float min = dpToPxF(300f, anyLine);
        float max = dpToPxF(320f, anyLine);

        if (vh <= 0f)
            return min;

        float preferred = vh * 0.35f;
        return Math.max(min, Math.min(max, preferred));
    }

    private float getFocusedPairOffsetPx(View line) {
        if (!isMaxPairSpacingEnabled())
            return 0f;
        if (activeLineIndex < 0)
            return 0f;

        Integer idxObj = lineIndex.get(line);
        if (idxObj == null) {
            int idx = lineRoots.indexOf(line);
            if (idx < 0)
                return 0f;
            idxObj = idx;
        }

        int idx = idxObj;
        int nextIndex = Math.min(activeLineIndex + 1, lineRoots.size() - 1);

        float isolation = getFocusedPairIsolationAmountPx(line);

        if (idx < activeLineIndex) {
            return -isolation;
        }

        if (idx > nextIndex) {
            return isolation;
        }

        return 0f;
    }

    private float getFinalLineTranslationY(View line, double baseScrollOffset) {
        return (float) baseScrollOffset + getFocusedPairOffsetPx(line);
    }

    private void applyCurrentLineTranslations() {
        for (View line : lineRoots) {
            Spring spring = lineSprings.get(line);
            double base = spring != null ? spring.position : targetScrollOffset;
            line.setTranslationY(getFinalLineTranslationY(line, base));
        }

        applyHeaderFadeToLines();
    }

    private void applyImmediateScrollOffset(LinearLayout contentContainer, double offset) {
        targetScrollOffset = offset;
        limitScrollBounds(contentContainer);

        for (View line : lineRoots) {
            line.setTranslationY((float) targetScrollOffset);

            Spring spring = lineSprings.get(line);
            if (spring != null) {
                spring.set(targetScrollOffset);
            }
        }

        applyHeaderFadeToLines();
    }

    private void applyHeaderFadeToLines() {
        if (headerFadeAnchor == null || lineRoots.isEmpty())
            return;
        if (!headerFadeAnchor.isAttachedToWindow())
            return;

        float fadeDistancePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                HEADER_FADE_DISTANCE_DP,
                headerFadeAnchor.getResources().getDisplayMetrics());

        int[] headerLoc = new int[2];
        headerFadeAnchor.getLocationInWindow(headerLoc);
        float headerBottomInWindow = headerLoc[1] + headerFadeAnchor.getHeight();

        for (View line : lineRoots) {
            if (line == null || !line.isAttachedToWindow())
                continue;

            int[] lineLoc = new int[2];
            line.getLocationInWindow(lineLoc);

            float lineTopInWindow = lineLoc[1];
            float lineBottomInWindow = lineTopInWindow + line.getHeight();
            float lineCenterInWindow = (lineTopInWindow + lineBottomInWindow) * 0.5f;

            float distanceBelowHeader = lineCenterInWindow - headerBottomInWindow;

            float alpha;
            if (distanceBelowHeader <= 0f) {
                alpha = HEADER_FADE_MIN_ALPHA;
            } else if (distanceBelowHeader >= fadeDistancePx) {
                alpha = 1f;
            } else {
                float t = distanceBelowHeader / fadeDistancePx;
                t = t * t * (3f - 2f * t);
                alpha = HEADER_FADE_MIN_ALPHA + ((1f - HEADER_FADE_MIN_ALPHA) * t);
            }

            line.setAlpha(alpha);
        }
    }

    private void clearHeaderFade() {
        for (View line : lineRoots) {
            if (line != null) {
                line.setAlpha(1f);
            }
        }
    }

    private ValueAnimator lyricsScrollAnimator = new ValueAnimator();

    private void scrollToNewLine(View activeLine, ScrollView scrollView, boolean immediate) {
        scrollView.post(() -> {
            final int scrollViewHeight = scrollView.getHeight();
            final int lineHeight = activeLine.getHeight();
            final int lineTopInSv = activeLine.getTop();
            final int targetScrollY = lineTopInSv - (scrollViewHeight / 3) + (lineHeight / 2);
            final int scrollY = scrollView.getScrollY();
            final int lineBottom = lineTopInSv + activeLine.getHeight();

            final View content = scrollView.getChildAt(0);
            final int maxScrollY = content.getHeight() - scrollViewHeight;
            final int targetScroll = Math.max(0, Math.min(targetScrollY, maxScrollY));

            if (lyricsScrollAnimator != null && lyricsScrollAnimator.isRunning()) {
                lyricsScrollAnimator.cancel();
            }

            if (immediate || (!scrollView.isPressed() && lineTopInSv >= scrollY
                    && lineBottom <= scrollY + scrollViewHeight)) {
                lyricsScrollAnimator = ValueAnimator.ofFloat(scrollView.getScrollY(), targetScroll);
                lyricsScrollAnimator.setDuration(400);
                lyricsScrollAnimator.setInterpolator(new DecelerateInterpolator());

                lyricsScrollAnimator.addUpdateListener(animation -> {
                    float value = (float) animation.getAnimatedValue();
                    scrollView.scrollTo(0, (int) value);
                });

                lyricsScrollAnimator.start();
            }

            activeLine.setPivotY(activeLine.getHeight() / 2.0f);
            activeLine.animate().scaleX(1.008f).scaleY(1.008f).setDuration(400)
                    .setInterpolator(new OvershootInterpolator());
        });
    }

    private Bitmap getBitmap(String id) {
        HttpURLConnection connection = null;
        InputStream input = null;

        try {
            URL url = new URL("https://i.scdn.co/image/" + id);
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();

            input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (IOException e) {
            XposedBridge.log(e);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private TrackAnalysis getTrackAnalysis(String id) {
        OkHttpClient client = new OkHttpClient();
        Gson gson = new Gson();

        Request request = new Request.Builder().url("https://api.reccobeats.com/v1/audio-features?ids=" + id).get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    Toast.makeText(References.currentActivity, "Failed to get track analysis", Toast.LENGTH_LONG)
                            .show();
                });

                return TrackAnalysis.defaultTrack;
            }

            String body = response.body().string();
            return gson.fromJson(body, TrackAnalysis.class);
        } catch (IOException e) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                Toast.makeText(References.currentActivity, "Failed to get track analysis", Toast.LENGTH_LONG).show();
            });

            return TrackAnalysis.defaultTrack;
        }
    }

    private Drawable createChevronDownIcon(Activity context) {
        int size = dpToPx(24, context);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setColor(Color.parseColor("#B3B3B3"));
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpToPx(2, context));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        float scale = size / 24f;

        Path path = new Path();
        path.moveTo(6f * scale, 9f * scale);
        path.lineTo(12f * scale, 15f * scale);
        path.lineTo(18f * scale, 9f * scale);

        canvas.drawPath(path, paint);

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    int dpToPx(int dp, Activity activity) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                activity.getResources().getDisplayMetrics());
    }

    private static class TopFadeLayout extends FrameLayout {
        private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private LinearGradient fadeShader;
        private int fadeHeightPx;

        public TopFadeLayout(Context context, int fadeHeightPx) {
            super(context);
            this.fadeHeightPx = fadeHeightPx;
            setWillNotDraw(false);

            fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

            solidPaint.setColor(0xFFFFFFFF);
            solidPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        public void setFadeHeightPx(int fadeHeightPx) {
            this.fadeHeightPx = fadeHeightPx;
            rebuildShader();
            invalidate();
        }

        private void rebuildShader() {
            if (getWidth() <= 0 || getHeight() <= 0 || fadeHeightPx <= 0) {
                fadeShader = null;
                fadePaint.setShader(null);
                return;
            }

            fadeShader = new LinearGradient(
                    0f, 0f,
                    0f, fadeHeightPx,
                    0x00FFFFFF,
                    0xFFFFFFFF,
                    Shader.TileMode.CLAMP);
            fadePaint.setShader(fadeShader);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rebuildShader();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);

            super.dispatchDraw(canvas);

            if (fadeHeightPx > 0) {
                if (fadeShader != null) {
                    canvas.drawRect(0, 0, getWidth(), fadeHeightPx, fadePaint);
                }

                canvas.drawRect(0, fadeHeightPx, getWidth(), getHeight(), solidPaint);
            }

            canvas.restoreToCount(save);
        }
    }
}