package com.srbport.roadblaster;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Floating touch buttons drawn over the game, very transparent so they hide little of the
 * picture. Same layout as the AYN Odin 2 Portal: steering ◀ ▶ on the left; on the right
 * "B" (right position) accelerates and "A" (bottom position) brakes; START at the bottom
 * centre and pause at the top right.
 *
 * Modes: "on" always visible, "off" never, "auto" appear when the screen is touched and
 * hide again as soon as a physical button is used.
 */
public final class TouchControls extends View {
    public interface Listener {
        void onTouchButtons(int mask);   // SNES button bits currently held on screen
        void onTouchStart();             // START tapped (the activity decides: pause or game START)
        void onTouchPause();             // pause button tapped
    }

    private static final int MAX_POINTERS = 10;
    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    private String mode = "auto";
    private float alpha = 0.2f;
    private boolean shown;
    private boolean movie;          // movie mode: only the pause button
    private int mask;
    private boolean startDown, pauseDown;

    // layout (computed in onSizeChanged)
    private float leftX, rightX, steerY, steerR;   // steering buttons (left / right only)
    private float accX, accY, brkX, brkY, btnR;     // B = accelerate (SNES A), A = brake (SNES B)
    private final RectF startRect = new RectF(), pauseRect = new RectF();

    public TouchControls(Context ctx, Listener l) {
        super(ctx);
        listener = l;
        dp = ctx.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * dp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setHapticFeedbackEnabled(true);
    }

    /** mode: auto | on | off; opacity 0..1 of the idle buttons. */
    public void configure(String mode, float opacity, boolean movieMode) {
        this.mode = mode == null ? "auto" : mode;
        this.alpha = Math.max(0.05f, Math.min(0.8f, opacity));
        this.movie = movieMode;
        if ("on".equals(this.mode)) shown = true;
        if ("off".equals(this.mode)) shown = false;
        setVisibility("off".equals(this.mode) ? GONE : VISIBLE);
        invalidate();
    }

    /** A physical button or stick was used: in auto mode the touch buttons fade away. */
    public void onPhysicalInput() {
        if ("auto".equals(mode) && shown) {
            shown = false;
            releaseAll();
            invalidate();
        }
    }

    public void releaseAll() {
        startDown = pauseDown = false;
        if (mask != 0) {
            mask = 0;
            listener.onTouchButtons(0);
        }
        invalidate();
    }

    public int mask() { return mask; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float u = Math.min(w, h);
        steerR = u * 0.095f;
        leftX = steerR + u * 0.06f;
        rightX = leftX + steerR * 2.5f;
        steerY = h - steerR - u * 0.10f;
        btnR = u * 0.085f;
        float cx = w - u * 0.25f, cy = h - u * 0.25f, d = btnR * 1.6f;   // diamond like the Odin, small gap between A and B
        accX = cx + d;                     // "B": right position
        accY = cy;
        brkX = cx;                         // "A": bottom position
        brkY = cy + d;
        float pw = u * 0.13f, ph = u * 0.065f;
        startRect.set(w / 2f - pw / 2f, h - ph - u * 0.03f, w / 2f + pw / 2f, h - u * 0.03f);
        float ps = u * 0.09f;
        pauseRect.set(w - ps - u * 0.03f, u * 0.03f, w - u * 0.03f, u * 0.03f + ps);
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if ("auto".equals(mode) && !shown) {
            shown = true;            // first touch only reveals the buttons
            invalidate();
            if (action == MotionEvent.ACTION_DOWN) return true;
        }
        int idx = e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (pauseRect.contains(x, y) || inflate(pauseRect).contains(x, y)) {
                pauseDown = true;
                haptic();
                listener.onTouchPause();
                invalidate();
                return true;
            }
            if (!movie && inflate(startRect).contains(x, y)) {
                startDown = true;
                haptic();
                listener.onTouchStart();
                invalidate();
                return true;
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            startDown = pauseDown = false;
        }
        int m = 0;
        if (!movie) {
            for (int i = 0; i < e.getPointerCount() && i < MAX_POINTERS; i++) {
                boolean lifted = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                        || (action == MotionEvent.ACTION_POINTER_UP && i == idx);
                if (lifted) continue;
                m |= buttonsAt(e.getX(i), e.getY(i));
            }
        }
        if (m != mask) {
            if ((m & ~mask) != 0) haptic();
            mask = m;
            listener.onTouchButtons(m);
            invalidate();
        }
        return true;
    }

    private RectF inflate(RectF r) {
        RectF o = new RectF(r);
        o.inset(-12 * dp, -12 * dp);
        return o;
    }

    private int buttonsAt(float x, float y) {
        int m = 0;
        // steering: a generous zone around both buttons, split down the middle
        if (Math.abs(y - steerY) < steerR * 1.6f && x < rightX + steerR * 1.5f) {
            m |= x < (leftX + rightX) / 2f ? NativeBridge.BTN_LEFT : NativeBridge.BTN_RIGHT;
        }
        if (Math.hypot(x - accX, y - accY) < btnR * 1.12f) m |= NativeBridge.BTN_A;
        if (Math.hypot(x - brkX, y - brkY) < btnR * 1.12f) m |= NativeBridge.BTN_B;
        return m;
    }

    private void haptic() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        if (!shown) return;
        drawPause(c);
        if (movie) return;
        // steering: left / right only
        boolean l = (mask & NativeBridge.BTN_LEFT) != 0, r = (mask & NativeBridge.BTN_RIGHT) != 0;
        circle(c, leftX, steerY, steerR, l, 0f);
        arrow(c, leftX, steerY, 270, l);
        circle(c, rightX, steerY, steerR, r, 0f);
        arrow(c, rightX, steerY, 90, r);
        // Odin 2 Portal labels: B (right) accelerates, A (bottom) brakes
        button(c, accX, accY, "B", (mask & NativeBridge.BTN_A) != 0);
        button(c, brkX, brkY, "A", (mask & NativeBridge.BTN_B) != 0);
        // START
        pill(c, startRect, "START", startDown);
    }

    private int white(float a) { return ((int) (Math.min(1f, a) * 255) << 24) | 0xFFFFFF; }

    private void circle(Canvas c, float x, float y, float r, boolean pressed, float extra) {
        fill.setColor(pressed ? white(alpha * 1.6f + 0.15f) : white(alpha * 0.35f + extra * alpha));
        c.drawCircle(x, y, r, fill);
        stroke.setColor(white(alpha * 2.2f));
        c.drawCircle(x, y, r, stroke);
    }

    private void arrow(Canvas c, float x, float y, float rot, boolean on) {
        float s = steerR * 0.42f;
        c.save();
        c.rotate(rot, x, y);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, y - s);
        p.lineTo(x + s, y + s * 0.6f);
        p.lineTo(x - s, y + s * 0.6f);
        p.close();
        fill.setColor(on ? white(Math.min(1f, alpha * 3.5f + 0.2f)) : white(alpha * 2.2f));
        c.drawPath(p, fill);
        c.restore();
    }

    private void button(Canvas c, float x, float y, String label, boolean on) {
        circle(c, x, y, btnR, on, 0f);
        text.setColor(white(on ? 1f : Math.min(1f, alpha * 3.2f)));
        text.setTextSize(btnR * 0.9f);
        c.drawText(label, x, y + btnR * 0.32f, text);
    }

    private void pill(Canvas c, RectF r, String label, boolean on) {
        float rad = r.height() / 2f;
        fill.setColor(on ? white(alpha * 1.6f + 0.15f) : white(alpha * 0.35f));
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(white(alpha * 2.2f));
        c.drawRoundRect(r, rad, rad, stroke);
        text.setColor(white(Math.min(1f, alpha * 3.2f)));
        text.setTextSize(r.height() * 0.42f);
        c.drawText(label, r.centerX(), r.centerY() + r.height() * 0.15f, text);
    }

    private void drawPause(Canvas c) {
        RectF r = pauseRect;
        float rad = r.width() / 2f;
        fill.setColor(pauseDown ? white(alpha * 1.6f + 0.15f) : white(alpha * 0.35f));
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(white(alpha * 2.2f));
        c.drawRoundRect(r, rad, rad, stroke);
        fill.setColor(white(Math.min(1f, alpha * 3.2f)));
        float bw = r.width() * 0.11f, bh = r.height() * 0.38f, cx = r.centerX(), cy = r.centerY();
        c.drawRect(cx - bw * 1.8f, cy - bh / 2, cx - bw * 0.6f, cy + bh / 2, fill);
        c.drawRect(cx + bw * 0.6f, cy - bh / 2, cx + bw * 1.8f, cy + bh / 2, fill);
    }
}
