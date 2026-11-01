package com.pawnbroking.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

/**
 * A rendered plate that behaves like a set, not a picture: it sits slightly
 * oversized and slides against the phone's tilt, and a line of light crosses
 * it now and then, as light crosses metal when you turn it in your hand.
 *
 * <p>The plate is drawn with its right edge held, because that is where each
 * one has its subject; the left stays dark for the screen's title.
 */
public class PlateView extends View {

    /** How much bigger than the view the plate is drawn, for the tilt to have room. */
    private static final float OVERSCAN = 1.07f;
    private static final float SWEEP_WIDTH = 0.34f;

    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint scrim = new Paint();
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sweepAt = new Matrix();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private Bitmap bitmap;
    private float tiltX, tiltY;
    private float sweepPhase = -1;
    private boolean scrimFromLeft = true;
    private boolean anchorTop;
    private float titleEnd;

    public PlateView(Context c) { this(c, null); }

    public PlateView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        sweep.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    public void setPlate(@DrawableRes int res) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        bitmap = BitmapFactory.decodeResource(getResources(), res, o);
        rebuildShaders();
        invalidate();
    }

    /** A portrait plate on a tall screen: held at its top, darkened downward rather than from the left. */
    public void setHero() {
        anchorTop = true;
        scrimFromLeft = false;
        rebuildShaders();
    }

    /** Where the screen's title ends, in this view's pixels. */
    public void setTitleEnd(float x) {
        if (x == titleEnd) return;
        titleEnd = x;
        rebuildShaders();
        invalidate();
    }

    /** -1 to 1 on each axis: how the phone is leaning. */
    public void setTilt(float x, float y) {
        tiltX = x;
        tiltY = y;
        invalidate();
    }

    /** 0 to 1 carries the line of light across; outside that it is off. */
    public void setSweep(float phase) {
        sweepPhase = phase;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { rebuildShaders(); }

    private void rebuildShaders() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (scrimFromLeft) {
            // Solid as far as the title reaches, so a long one never reads over the gold.
            float fade = 72 * getResources().getDisplayMetrics().density;
            float end = Math.max(w * 0.62f, titleEnd + fade);
            float held = Math.min(titleEnd, end - fade) / end;
            scrim.setShader(new LinearGradient(0, 0, end, 0,
                    new int[] {0xD80D0508, 0xD00D0508, 0x000D0508},
                    new float[] {0f, Math.max(held, 0.3f), 1f}, Shader.TileMode.CLAMP));
        } else {
            scrim.setShader(new LinearGradient(0, 0, 0, h,
                    // Dark by the height of the shop's name: the gold behind it must not take the words.
                    new int[] {0x700D0508, 0x400D0508, 0xC80D0508, 0xFF0D0508},
                    new float[] {0f, 0.10f, 0.26f, 0.46f}, Shader.TileMode.CLAMP));
        }
        sweep.setShader(new LinearGradient(0, 0, w * SWEEP_WIDTH, h * 0.5f,
                new int[] {Color.TRANSPARENT, 0x38FFE3A8, 0x60FFF3D2, 0x38FFE3A8, Color.TRANSPARENT},
                new float[] {0f, 0.4f, 0.5f, 0.6f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (bitmap == null || w == 0 || h == 0) return;

        // Cover the view, overscanned, keeping the plate's own proportions.
        float scale = Math.max((float) w / bitmap.getWidth(), (float) h / bitmap.getHeight()) * OVERSCAN;
        float dw = bitmap.getWidth() * scale, dh = bitmap.getHeight() * scale;
        float slackX = dw - w, slackY = dh - h;
        float x = anchorTop ? -slackX / 2f : -slackX;
        float y = anchorTop ? 0 : -slackY / 2f;

        float roomX = Math.min(slackX, w * (OVERSCAN - 1f)) / 2f;
        float roomY = Math.min(slackY, h * (OVERSCAN - 1f)) / 2f;
        x += anchorTop ? tiltX * roomX : roomX + tiltX * roomX;
        y += anchorTop ? -roomY + tiltY * roomY : tiltY * roomY;

        src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        dst.set(x, y, x + dw, y + dh);
        canvas.drawBitmap(bitmap, src, dst, image);
        canvas.drawRect(0, 0, w, h, scrim);

        if (sweepPhase >= 0 && sweepPhase <= 1) {
            float travel = w * (1f + SWEEP_WIDTH * 2f);
            sweepAt.setTranslate(-w * SWEEP_WIDTH + travel * sweepPhase - w * SWEEP_WIDTH, 0);
            sweep.getShader().setLocalMatrix(sweepAt);
            canvas.drawRect(0, 0, w, h, sweep);
        }
    }
}
