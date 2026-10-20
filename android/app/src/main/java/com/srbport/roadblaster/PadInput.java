package com.srbport.roadblaster;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Maps the AYN Odin 2 Portal controls (and keyboards) to SNES buttons and to
 * menu actions. "snes" layout follows button positions (bottom = B, right = A),
 * "xbox" layout follows the printed labels.
 */
public final class PadInput {
    public static final int ACT_NONE = 0, ACT_MENU = 1, ACT_SLOW = 2, ACT_FAST = 3, ACT_HUD = 4;

    private int keyMask;       // from key events
    private int axisMask;      // from sticks / hat
    private boolean slowHeld, fastHeld, slowAxis, fastAxis;
    private boolean xboxLayout;

    public void setLayout(String layout) { xboxLayout = "xbox".equals(layout); }

    public int mask() { return keyMask | axisMask; }
    public boolean slowHeld() { return slowHeld || slowAxis; }
    public boolean fastHeld() { return fastHeld || fastAxis; }
    public void clear() { keyMask = axisMask = 0; slowHeld = fastHeld = slowAxis = fastAxis = false; }

    /** SNES bit for a key code, 0 if none. */
    public int bitFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: return NativeBridge.BTN_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: return NativeBridge.BTN_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A: return NativeBridge.BTN_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D: return NativeBridge.BTN_RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A: return xboxLayout ? NativeBridge.BTN_A : NativeBridge.BTN_B;
            case KeyEvent.KEYCODE_BUTTON_B: return xboxLayout ? NativeBridge.BTN_B : NativeBridge.BTN_A;
            case KeyEvent.KEYCODE_BUTTON_X: return xboxLayout ? NativeBridge.BTN_X : NativeBridge.BTN_Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return xboxLayout ? NativeBridge.BTN_Y : NativeBridge.BTN_X;
            case KeyEvent.KEYCODE_K: return NativeBridge.BTN_B;      // keyboard: K = brake
            case KeyEvent.KEYCODE_L: return NativeBridge.BTN_A;      // keyboard: L = accelerate
            case KeyEvent.KEYCODE_BUTTON_L1: return NativeBridge.BTN_L;
            case KeyEvent.KEYCODE_BUTTON_R1: return NativeBridge.BTN_R;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER: return NativeBridge.BTN_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_SPACE: return NativeBridge.BTN_SELECT;
            default: return 0;
        }
    }

    public static int actionFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_MENU:
                return ACT_MENU;
            case KeyEvent.KEYCODE_BUTTON_L2: return ACT_SLOW;
            case KeyEvent.KEYCODE_BUTTON_R2: return ACT_FAST;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return ACT_HUD;
            default: return ACT_NONE;
        }
    }

    /** Returns true if the key was consumed for gameplay. */
    public boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return false;
        int act = actionFor(code);
        if (act == ACT_SLOW) { slowHeld = down; return true; }
        if (act == ACT_FAST) { fastHeld = down; return true; }
        int bit = bitFor(code);
        if (bit == 0) return false;
        if (down) keyMask |= bit; else keyMask &= ~bit;
        return true;
    }

    /** Analog stick, d-pad hat and analog triggers. */
    public boolean onMotion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int m = 0;
        if (x < -0.5f || hx < -0.5f) m |= NativeBridge.BTN_LEFT;
        if (x > 0.5f || hx > 0.5f) m |= NativeBridge.BTN_RIGHT;
        if (y < -0.5f || hy < -0.5f) m |= NativeBridge.BTN_UP;
        if (y > 0.5f || hy > 0.5f) m |= NativeBridge.BTN_DOWN;
        axisMask = m;
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        slowAxis = lt > 0.5f;
        fastAxis = rt > 0.5f;
        return true;
    }

    /** Menu navigation token for the web UI ("UP", "A", ...), or null. */
    public static String uiToken(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D: return "RIGHT";
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                return "A";
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            default: return null;
        }
    }
}
