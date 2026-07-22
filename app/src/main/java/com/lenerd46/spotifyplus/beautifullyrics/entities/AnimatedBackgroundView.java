package com.lenerd46.spotifyplus.beautifullyrics.entities;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.*;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class AnimatedBackgroundView extends View {
    private static final int BLUR_RADIUS = 20;
    private static final long TRANSITION_DURATION_MS = 1000L;
    private static final int BUFFER_COUNT = 3;

    private static final int PALETTE_COLORFUL = 0;
    private static final int PALETTE_DARK_MUTED = 1;
    private static final int PALETTE_BRIGHT_NEUTRAL = 2;

    private final float downsampleFactor;
    private final int blobCount;
    private int paletteMode = PALETTE_COLORFUL;

    private final HandlerThread renderThread;
    private final Handler renderHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean renderScheduled = new AtomicBoolean(false);
    private final Object lock = new Object();

    private final Random random = new Random();

    private final Bitmap[] buffers = new Bitmap[BUFFER_COUNT];
    private final Canvas[] canvases = new Canvas[BUFFER_COUNT];
    private int renderHeadIndex = 0;
    private Bitmap renderedBitmap;

    private int offW = 1, offH = 1;

    private Bitmap sourceImage;
    private TrackAnalysis currentAnalysis = TrackAnalysis.defaultTrack;
    private volatile int baseColor = 0xFF101010;

    private List<Blob> blobs = new ArrayList<>();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final Rect drawBounds = new Rect();

    private long startTimeMs;
    private boolean isTransitioning = false;
    private Bitmap previousBitmap;
    private long transitionStartMs;
    private long lastRenderTimeNanos = 0;

    private float animationSpeedMultiplier = 1.0f;
    private float breathingFrequency = 1.0f;

    private int[] blurBufA, blurBufB;
    private int[] blurOutgoingX, blurIncomingX, blurOutgoingY, blurIncomingY;
    private final Choreographer.FrameCallback frameCallback;

    public AnimatedBackgroundView(Context ctx, Bitmap bitmap, ViewGroup root) {
        super(ctx);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        sourceImage = createThumbnail(bitmap);

        SharedPreferences prefs = ctx.getSharedPreferences("SpotifyPlus", Context.MODE_PRIVATE);
        String quality = prefs.getString("lyric_background_quality", "high");
        float selectedDownsampleFactor = 0.12f;
        int selectedBlobCount = 16;
        switch (quality) {
            case "mid":
                selectedDownsampleFactor = 0.06f;
                selectedBlobCount = 10;
                break;
            case "low":
                selectedDownsampleFactor = 0.04f;
                selectedBlobCount = 6;
                break;
            case "superLow":
            case "superlow":
                selectedDownsampleFactor = 0.02f;
                selectedBlobCount = 4;
                break;
        }
        downsampleFactor = selectedDownsampleFactor;
        blobCount = selectedBlobCount;

        renderThread = new HandlerThread("FluidBG");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        startTimeMs = SystemClock.elapsedRealtime();
        blobPaint.setXfermode(null);
        blobPaint.setStyle(Paint.Style.FILL);
        blobPaint.setAlpha(190);

        frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (getWindowToken() == null) return;
                if (renderScheduled.compareAndSet(false, true)) {
                    renderHandler.post(AnimatedBackgroundView.this::renderFrame);
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };

        renderHandler.post(this::internalRebuildResources);
    }

    public void updateImage(Bitmap newImage) {
        if (newImage == null || newImage.isRecycled()) return;
        final Bitmap smallCopy = createThumbnail(newImage);
        if (!renderHandler.post(() -> {
            synchronized (lock) {
                if (previousBitmap != null) previousBitmap.recycle();
                if (renderedBitmap != null && !renderedBitmap.isRecycled()) {
                    previousBitmap = renderedBitmap.copy(Bitmap.Config.ARGB_8888, false);
                    transitionStartMs = SystemClock.elapsedRealtime();
                    isTransitioning = true;
                }
            }
            if (sourceImage != null) sourceImage.recycle();
            sourceImage = smallCopy;
            internalRebuildResources();
        })) {
            smallCopy.recycle();
        }
    }

    public void updateTrackAnalysis(TrackAnalysis analysis) {
        TrackAnalysis nextAnalysis = (analysis != null) ? analysis : TrackAnalysis.defaultTrack;
        renderHandler.post(() -> {
            currentAnalysis = nextAnalysis;
            internalRebuildResources();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        int width = getWidth();
        int height = getHeight();
        renderHandler.post(() -> {
            allocateBuffersIfNeeded(width, height);
            internalRebuildResources();
        });
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        renderHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        renderHandler.post(() -> {
            synchronized (lock) {
                for (int i = 0; i < BUFFER_COUNT; i++) {
                    if (buffers[i] != null) buffers[i].recycle();
                    buffers[i] = null;
                    canvases[i] = null;
                }
                if (previousBitmap != null) previousBitmap.recycle();
                previousBitmap = null;
                renderedBitmap = null;
            }
            if (sourceImage != null) sourceImage.recycle();
            sourceImage = null;
            renderThread.quitSafely();
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        renderHandler.post(() -> allocateBuffersIfNeeded(w, h));
    }

    private void allocateBuffersIfNeeded(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        int targetW = Math.max(1, Math.round(vw * downsampleFactor));
        int targetH = Math.max(1, Math.round(vh * downsampleFactor));

        if (buffers[0] != null
                && buffers[0].getWidth() == targetW
                && buffers[0].getHeight() == targetH) {
            return;
        }

        synchronized (lock) {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                if (buffers[i] != null) buffers[i].recycle();
                buffers[i] = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                canvases[i] = new Canvas(buffers[i]);
            }
            offW = targetW;
            offH = targetH;
            blurBufA = new int[offW * offH];
            blurBufB = new int[offW * offH];
            blurOutgoingX = new int[offW];
            blurIncomingX = new int[offW];
            blurOutgoingY = new int[offH];
            blurIncomingY = new int[offH];
            for (int x = 0; x < offW; x++) {
                blurOutgoingX[x] = clamp(x - BLUR_RADIUS, 0, offW - 1);
                blurIncomingX[x] = clamp(x + BLUR_RADIUS + 1, 0, offW - 1);
            }
            for (int y = 0; y < offH; y++) {
                blurOutgoingY[y] = clamp(y - BLUR_RADIUS, 0, offH - 1) * offW;
                blurIncomingY[y] = clamp(y + BLUR_RADIUS + 1, 0, offH - 1) * offW;
            }
            renderedBitmap = null;
            renderHeadIndex = 0;
        }
    }

    private void internalRebuildResources() {
        if (sourceImage == null) return;

        float[] hsv = new float[3];
        double sumS = 0.0;
        double sumV = 0.0;
        int sampleCount = 0;
        for (int x = 0; x < sourceImage.getWidth(); x += 4) {
            for (int y = 0; y < sourceImage.getHeight(); y += 4) {
                Color.colorToHSV(sourceImage.getPixel(x, y), hsv);
                sumS += hsv[1];
                sumV += hsv[2];
                sampleCount++;
            }
        }
        float averageSaturation = sampleCount == 0 ? 0f : (float) (sumS / sampleCount);
        float averageValue = sampleCount == 0 ? 0f : (float) (sumV / sampleCount);
        if (averageValue >= 0.75f && averageSaturation <= 0.25f) {
            paletteMode = PALETTE_BRIGHT_NEUTRAL;
        } else if (averageValue < 0.30f || averageSaturation < 0.18f) {
            paletteMode = PALETTE_DARK_MUTED;
        } else {
            paletteMode = PALETTE_COLORFUL;
        }

        boolean usesDefaultAnalysis = currentAnalysis == TrackAnalysis.defaultTrack
                || (currentAnalysis.acousticness == 1
                && currentAnalysis.danceability == 1
                && currentAnalysis.tempo == 1);
        if (usesDefaultAnalysis) {
            animationSpeedMultiplier = 1.0f;
            breathingFrequency = 1.0f;
        } else {
            animationSpeedMultiplier = 0.4f + (currentAnalysis.energy * 1.4f);
            float bpm = currentAnalysis.tempo;
            if (bpm < 40) bpm = 40;
            if (bpm > 200) bpm = 200;
            breathingFrequency = bpm / 110.0f;
        }

        HueBucket[] hueBuckets = new HueBucket[36];
        for (int x = 0; x < sourceImage.getWidth(); x++) {
            for (int y = 0; y < sourceImage.getHeight(); y++) {
                int color = sourceImage.getPixel(x, y);
                if (Color.alpha(color) < 64) continue;

                Color.colorToHSV(color, hsv);
                float hue = hsv[0];
                float saturation = hsv[1];
                float value = hsv[2];
                if (value < 0.03f) continue;
                if (paletteMode == PALETTE_COLORFUL) {
                    if (saturation < 0.18f || value < 0.18f) continue;
                } else if (paletteMode == PALETTE_DARK_MUTED) {
                    if (value < 0.05f && saturation < 0.05f) continue;
                } else if (value < 0.50f && saturation < 0.08f) {
                    continue;
                }

                int bin = clamp((int) (hue / 10f), 0, 35);
                HueBucket bucket = hueBuckets[bin];
                if (bucket == null) {
                    bucket = new HueBucket();
                    bucket.hueCenter = bin * 10f + 5f;
                    hueBuckets[bin] = bucket;
                }
                bucket.sumR += Color.red(color);
                bucket.sumG += Color.green(color);
                bucket.sumB += Color.blue(color);
                bucket.sumS += saturation;
                bucket.sumV += value;
                bucket.count++;
            }
        }

        List<HueBucket> rankedBuckets = new ArrayList<>();
        for (HueBucket bucket : hueBuckets) {
            if (bucket == null || bucket.count == 0) continue;
            float vividness = (bucket.sumS / bucket.count) * 0.7f
                    + (bucket.sumV / bucket.count) * 0.3f;
            if (paletteMode == PALETTE_COLORFUL) {
                bucket.score = bucket.count * vividness;
            } else if (paletteMode == PALETTE_DARK_MUTED) {
                bucket.score = bucket.count * (0.7f + vividness * 0.3f);
            } else {
                bucket.score = bucket.count * (1.0f + vividness * 0.2f);
            }
            rankedBuckets.add(bucket);
        }

        List<Integer> blobColors = new ArrayList<>(blobCount);
        if (rankedBuckets.isEmpty()) {
            int color = sourceImage.getPixel(sourceImage.getWidth() / 2, sourceImage.getHeight() / 2);
            for (int i = 0; i < blobCount; i++) blobColors.add(color);
        } else {
            Collections.sort(rankedBuckets, (left, right) -> Float.compare(right.score, left.score));
            List<HueBucket> mainBuckets = new ArrayList<>(3);
            mainBuckets.add(rankedBuckets.get(0));
            for (int i = 1; i < rankedBuckets.size() && mainBuckets.size() < 3; i++) {
                HueBucket candidate = rankedBuckets.get(i);
                boolean farEnough = true;
                for (HueBucket main : mainBuckets) {
                    float distance = Math.abs(candidate.hueCenter - main.hueCenter);
                    if (distance > 180f) distance = 360f - distance;
                    if (distance < 22f) {
                        farEnough = false;
                        break;
                    }
                }
                if (farEnough) mainBuckets.add(candidate);
            }

            int mainCount = mainBuckets.size();
            if (mainCount == 1) {
                int color = bucketToColor(mainBuckets.get(0));
                for (int i = 0; i < blobCount; i++) blobColors.add(color);
            } else {
                float[] weights;
                if (mainCount == 2) {
                    if (paletteMode == PALETTE_DARK_MUTED) {
                        weights = new float[]{0.70f, 0.30f};
                    } else if (paletteMode == PALETTE_BRIGHT_NEUTRAL) {
                        weights = new float[]{0.75f, 0.25f};
                    } else {
                        weights = new float[]{0.65f, 0.35f};
                    }
                } else if (paletteMode == PALETTE_DARK_MUTED) {
                    weights = new float[]{0.60f, 0.25f, 0.15f};
                } else if (paletteMode == PALETTE_BRIGHT_NEUTRAL) {
                    weights = new float[]{0.70f, 0.18f, 0.12f};
                } else {
                    weights = new float[]{0.55f, 0.25f, 0.20f};
                }

                int[] counts = new int[mainCount];
                int primaryMinimum = Math.max(blobCount / 3, 4);
                int assigned = 0;
                for (int i = 0; i < mainCount; i++) {
                    int minimum = i == 0 ? primaryMinimum : 2;
                    counts[i] = clamp(Math.round(weights[i] * blobCount), minimum, blobCount);
                    assigned += counts[i];
                }
                if (assigned > blobCount) {
                    int excess = assigned - blobCount;
                    for (int i = mainCount - 1; i >= 0 && excess > 0; i--) {
                        int minimum = i == 0 ? primaryMinimum : 2;
                        int delta = Math.min(counts[i] - minimum, excess);
                        counts[i] -= delta;
                        excess -= delta;
                    }
                    assigned = blobCount;
                }
                if (assigned < blobCount) counts[0] += blobCount - assigned;

                for (int i = 0; i < mainCount; i++) {
                    int color = bucketToColor(mainBuckets.get(i));
                    for (int j = 0; j < counts[i]; j++) blobColors.add(color);
                }
                Collections.shuffle(blobColors, random);
            }
        }

        int seedColor = blobColors.get(0);
        Color.colorToHSV(seedColor, hsv);
        if (paletteMode == PALETTE_COLORFUL) {
            hsv[1] *= 0.35f;
            hsv[2] = clampFloat(0.13f + hsv[2] * 0.19f, 0.13f, 0.32f);
        } else if (paletteMode == PALETTE_DARK_MUTED) {
            hsv[1] *= 0.30f;
            hsv[2] = clampFloat(0.03f + hsv[2] * 0.18f, 0.03f, 0.24f);
        } else {
            hsv[1] = Math.min(hsv[1] * 0.3f, 0.10f);
            hsv[2] = clampFloat(0.85f + hsv[2] * 0.10f, 0.85f, 0.99f);
        }
        if (!usesDefaultAnalysis) {
            if (currentAnalysis.valence < 0.3f) {
                hsv[2] *= 0.8f;
            } else if (currentAnalysis.valence > 0.7f) {
                hsv[2] = clampFloat(hsv[2] * 1.1f, 0f, 0.9f);
            }
        }
        baseColor = Color.HSVToColor(hsv);

        List<Blob> newBlobs = new ArrayList<>(blobCount);
        for (int i = 0; i < blobCount; i++) {
            int rawColor = blobColors.get(i);
            Color.colorToHSV(rawColor, hsv);
            if (paletteMode == PALETTE_COLORFUL) {
                hsv[1] = clampFloat(hsv[1] * 1.10f, 0.45f, 0.90f);
                hsv[2] = clampFloat(0.50f + hsv[2] * 0.30f, 0.55f, 0.80f);
            } else if (paletteMode == PALETTE_DARK_MUTED) {
                hsv[1] = clampFloat(hsv[1] * 0.9f, 0.08f, 0.45f);
                hsv[2] = clampFloat(0.18f + hsv[2] * 0.24f, 0.12f, 0.46f);
            } else {
                hsv[1] = clampFloat(hsv[1] * 1.1f, 0.05f, 0.35f);
                hsv[2] = clampFloat(0.78f + hsv[2] * 0.17f, 0.78f, 0.97f);
            }
            if (!usesDefaultAnalysis) {
                float valence = currentAnalysis.valence;
                float energy = currentAnalysis.energy;
                if (valence > 0.6f) {
                    hsv[1] = clampFloat(hsv[1] * (1.0f + (valence - 0.5f) * 0.5f), 0f, 1f);
                    hsv[2] = clampFloat(hsv[2] * 1.1f, 0f, 1f);
                } else if (valence < 0.4f) {
                    hsv[1] *= 0.7f + valence * 0.3f;
                    hsv[2] *= 0.8f + valence * 0.2f;
                }
                if (energy > 0.7f) hsv[1] = clampFloat(hsv[1] * 1.15f, 0f, 1f);
            }
            int processedColor = Color.HSVToColor(hsv);

            float originX = 0.5f + (random.nextFloat() - 0.5f) * 0.8f;
            float originY = 0.5f + (random.nextFloat() - 0.5f) * 0.8f;
            float radius = 0.35f + random.nextFloat() * 0.4f;
            float vx = (random.nextFloat() - 0.5f) * 0.003f;
            float vy = (random.nextFloat() - 0.5f) * 0.003f;

            newBlobs.add(new Blob(originX, originY, radius, processedColor, vx, vy));
        }
        blobs = newBlobs;
    }

    private static class HueBucket {
        long sumR, sumG, sumB;
        float sumS, sumV;
        int count;
        float hueCenter;
        float score;
    }

    private static int bucketToColor(HueBucket bkt) {
        int avgR = (int) (bkt.sumR / bkt.count);
        int avgG = (int) (bkt.sumG / bkt.count);
        int avgB = (int) (bkt.sumB / bkt.count);
        return Color.rgb(avgR, avgG, avgB);
    }

    private void renderFrame() {
        try {
            if (offW <= 0 || offH <= 0) return;

            int index = (renderHeadIndex + 1) % BUFFER_COUNT;
            Bitmap buffer = buffers[index];
            Canvas c = canvases[index];
            if (buffer == null) return;

            c.drawColor(baseColor);

            long nowNanos = System.nanoTime();
            long dtNanos = lastRenderTimeNanos == 0 ? 16_666_667L : nowNanos - lastRenderTimeNanos;
            lastRenderTimeNanos = nowNanos;
            float dt = Math.min(dtNanos, 50_000_000L) / 1_000_000_000f;
            float time = (SystemClock.elapsedRealtime() - startTimeMs) / 1000f;
            float frameScale = dt * 60f;

            float safeMin = -0.3f;
            float safeMax = 1.3f;

            float targetSpeed = 0.0025f * animationSpeedMultiplier;

            for (int i = 0; i < blobs.size(); i++) {
                Blob b = blobs.get(i);

                b.vx += (random.nextFloat() - 0.5f) * 0.0005f * frameScale;
                b.vy += (random.nextFloat() - 0.5f) * 0.0005f * frameScale;

                if (b.x < safeMin) b.vx += 0.0002f * frameScale;
                else if (b.x > safeMax) b.vx -= 0.0002f * frameScale;

                if (b.y < safeMin) b.vy += 0.0002f * frameScale;
                else if (b.y > safeMax) b.vy -= 0.0002f * frameScale;

                float currentSpeed = (float) Math.sqrt(b.vx * b.vx + b.vy * b.vy);
                if (currentSpeed > 0.00001f) {
                    float newSpeed = currentSpeed * 0.95f + targetSpeed * 0.05f;
                    float scale = newSpeed / currentSpeed;
                    b.vx *= scale;
                    b.vy *= scale;
                }

                b.x += b.vx * frameScale;
                b.y += b.vy * frameScale;

                float drawX = b.x * offW;
                float drawY = b.y * offH;

                float breathe = (float) Math.sin(time * (0.5f * breathingFrequency + (i % 4) * 0.1f) + i) * 0.08f;
                float radiusPx = (b.radius + breathe) * Math.max(offW, offH);

                shaderMatrix.setScale(radiusPx, radiusPx);
                shaderMatrix.postTranslate(drawX, drawY);
                b.shader.setLocalMatrix(shaderMatrix);
                blobPaint.setShader(b.shader);
                c.drawCircle(drawX, drawY, radiusPx, blobPaint);
            }

            if (blurBufA != null && blurBufA.length >= offW * offH) {
                buffer.getPixels(blurBufA, 0, offW, 0, 0, offW, offH);
                int diameter = BLUR_RADIUS * 2 + 1;
                for (int y = 0; y < offH; y++) {
                    int red = 0;
                    int green = 0;
                    int blue = 0;
                    int rowStart = y * offW;
                    for (int x = -BLUR_RADIUS; x <= BLUR_RADIUS; x++) {
                        int color = blurBufA[rowStart + clamp(x, 0, offW - 1)];
                        red += (color >> 16) & 0xFF;
                        green += (color >> 8) & 0xFF;
                        blue += color & 0xFF;
                    }
                    for (int x = 0; x < offW; x++) {
                        blurBufB[rowStart + x] = 0xFF000000
                                | ((red / diameter) << 16)
                                | ((green / diameter) << 8)
                                | (blue / diameter);
                        int outgoing = blurBufA[rowStart + blurOutgoingX[x]];
                        int incoming = blurBufA[rowStart + blurIncomingX[x]];
                        red += ((incoming >> 16) & 0xFF) - ((outgoing >> 16) & 0xFF);
                        green += ((incoming >> 8) & 0xFF) - ((outgoing >> 8) & 0xFF);
                        blue += (incoming & 0xFF) - (outgoing & 0xFF);
                    }
                }
                for (int x = 0; x < offW; x++) {
                    int red = 0;
                    int green = 0;
                    int blue = 0;
                    for (int y = -BLUR_RADIUS; y <= BLUR_RADIUS; y++) {
                        int color = blurBufB[clamp(y, 0, offH - 1) * offW + x];
                        red += (color >> 16) & 0xFF;
                        green += (color >> 8) & 0xFF;
                        blue += color & 0xFF;
                    }
                    for (int y = 0; y < offH; y++) {
                        blurBufA[y * offW + x] = 0xFF000000
                                | ((red / diameter) << 16)
                                | ((green / diameter) << 8)
                                | (blue / diameter);
                        int outgoing = blurBufB[blurOutgoingY[y] + x];
                        int incoming = blurBufB[blurIncomingY[y] + x];
                        red += ((incoming >> 16) & 0xFF) - ((outgoing >> 16) & 0xFF);
                        green += ((incoming >> 8) & 0xFF) - ((outgoing >> 8) & 0xFF);
                        blue += (incoming & 0xFF) - (outgoing & 0xFF);
                    }
                }
                buffer.setPixels(blurBufA, 0, offW, 0, 0, offW, offH);
            }

            synchronized (lock) {
                renderedBitmap = buffer;
                renderHeadIndex = index;
            }

            mainHandler.post(this::postInvalidateOnAnimation);
        } finally {
            renderScheduled.set(false);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBounds.set(0, 0, getWidth(), getHeight());
        synchronized (lock) {
            if (renderedBitmap == null || renderedBitmap.isRecycled()) {
                canvas.drawColor(baseColor);
                return;
            }
            if (isTransitioning && previousBitmap != null && !previousBitmap.isRecycled()) {
                float progress = Math.min(
                        (SystemClock.elapsedRealtime() - transitionStartMs) / (float) TRANSITION_DURATION_MS,
                        1f
                );
                drawPaint.setAlpha((int) ((1f - progress) * 255));
                canvas.drawBitmap(previousBitmap, null, drawBounds, drawPaint);
                drawPaint.setAlpha((int) (progress * 255));
                canvas.drawBitmap(renderedBitmap, null, drawBounds, drawPaint);
                if (progress >= 1f) {
                    previousBitmap.recycle();
                    previousBitmap = null;
                    isTransitioning = false;
                }
            } else {
                drawPaint.setAlpha(255);
                canvas.drawBitmap(renderedBitmap, null, drawBounds, drawPaint);
            }
        }
    }

    private static Bitmap createThumbnail(Bitmap bitmap) {
        if (bitmap == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 100, 100, true);
        return scaled == bitmap ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : scaled;
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }

    private static float clampFloat(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static class Blob {
        float x, y, vx, vy, radius;
        final RadialGradient shader;

        Blob(float x, float y, float r, int c, float vx, float vy) {
            this.x = x;
            this.y = y;
            radius = r;
            this.vx = vx;
            this.vy = vy;
            shader = new RadialGradient(0f, 0f, 1f, new int[]{c, c & 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        }
    }
}
