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
 * picture: d-pad (left), A = accelerate and B = brake (right), START and pause (top).
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
    private float padX, padY, padR, aX, aY, bX, bY, btnR;
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
        padR = u * 0.17f;
        padX = padR + u * 0.07f;
        padY = h - padR - u * 0.08f;
        btnR = u * 0.085f;
        bX = w - u * 0.32f;               // B (brake): bottom
        bY = h - btnR - u * 0.06f;
        aX = w - btnR - u * 0.05f;        // A (accelerate): right, a bit higher
        aY = bY - btnR * 1.45f;
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
        float dx = x - padX, dy = y - padY;
        float d = (float) Math.hypot(dx, dy);
        if (d < padR * 1.35f && d > padR * 0.18f) {
            double ang = Math.toDegrees(Math.atan2(dy, dx)); // 0 = right, 90 = down
            if (ang > -67.5 && ang < 67.5) m |= NativeBridge.BTN_RIGHT;
            if (ang > 112.5 || ang < -112.5) m |= NativeBridge.BTN_LEFT;
            if (ang > 22.5 && ang < 157.5) m |= NativeBridge.BTN_DOWN;
            if (ang < -22.5 && ang > -157.5) m |= NativeBridge.BTN_UP;
        }
        if (Math.hypot(x - aX, y - aY) < btnR * 1.25f) m |= NativeBridge.BTN_A;
        if (Math.hypot(x - bX, y - bY) < btnR * 1.25f) m |= NativeBridge.BTN_B;
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
        // d-pad
        boolean any = (mask & (NativeBridge.BTN_UP | NativeBridge.BTN_DOWN | NativeBridge.BTN_LEFT | NativeBridge.BTN_RIGHT)) != 0;
        circle(c, padX, padY, padR, false, any ? 0.35f : 0f);
        float arm = padR * 0.62f;
        arrow(c, padX, padY - arm, 0, (mask & NativeBridge.BTN_UP) != 0);
        arrow(c, padX, padY + arm, 180, (mask & NativeBridge.BTN_DOWN) != 0);
        arrow(c, padX - arm, padY, 270, (mask & NativeBridge.BTN_LEFT) != 0);
        arrow(c, padX + arm, padY, 90, (mask & NativeBridge.BTN_RIGHT) != 0);
        // A / B
        button(c, aX, aY, "A", "ACELERAR", (mask & NativeBridge.BTN_A) != 0);
        button(c, bX, bY, "B", "FRENAR", (mask & NativeBridge.BTN_B) != 0);
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
        float s = padR * 0.22f;
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

    private void button(Canvas c, float x, float y, String label, String caption, boolean on) {
        circle(c, x, y, btnR, on, 0f);
        text.setColor(white(on ? 1f : Math.min(1f, alpha * 3.2f)));
        text.setTextSize(btnR * 0.9f);
        c.drawText(label, x, y + btnR * 0.32f, text);
        text.setTextSize(btnR * 0.24f);
        text.setColor(white(Math.min(1f, alpha * 2.4f)));
        c.drawText(caption, x, y + btnR + btnR * 0.38f, text);
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
