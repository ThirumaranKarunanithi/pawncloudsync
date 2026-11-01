package com.pawnbroking.app.util;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.AccountDetailActivity;
import com.pawnbroking.app.BackupFilesActivity;
import com.pawnbroking.app.BillingActivity;
import com.pawnbroking.app.BillsActivity;
import com.pawnbroking.app.CustomersActivity;
import com.pawnbroking.app.EmployeeActivityActivity;
import com.pawnbroking.app.HomeActivity;
import com.pawnbroking.app.LoginActivity;
import com.pawnbroking.app.MisListActivity;
import com.pawnbroking.app.MonthlyReportActivity;
import com.pawnbroking.app.NotificationsActivity;
import com.pawnbroking.app.PawnApp;
import com.pawnbroking.app.R;
import com.pawnbroking.app.SettingsActivity;
import com.pawnbroking.app.ShopPickerActivity;
import com.pawnbroking.app.StockDetailsActivity;
import com.pawnbroking.app.TodaysAccountActivity;
import com.pawnbroking.app.TrialBalanceActivity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * The film around every screen, installed once from {@link PawnApp} so no
 * screen has to remember it, and no new screen can forget it:
 *
 * <ul>
 *   <li>each screen gets its own rendered, separately graded plate in its band;
 *   <li>the plate slides against the phone's tilt and a line of light crosses it;
 *   <li>content rises into place in perspective;
 *   <li>anything tappable leans back under the finger.
 * </ul>
 *
 * <p>Motion stops with the screen: the sensor and the sweep run only while it
 * is resumed. And it honours the phone's "remove animations" setting, because
 * the figures have to be readable for the person who switched those off.
 */
public final class Cinema implements Application.ActivityLifecycleCallbacks {

    private static final float TILT_DEGREES = 5.5f;
    private static final long SWEEP_EVERY_MS = 7200;

    private static final Map<Class<?>, Integer> PLATES = new HashMap<>();
    static {
        PLATES.put(HomeActivity.class,             R.drawable.plate_home);
        PLATES.put(BillsActivity.class,            R.drawable.plate_bills);
        PLATES.put(BillingActivity.class,          R.drawable.plate_search);
        PLATES.put(TodaysAccountActivity.class,    R.drawable.plate_todays);
        PLATES.put(AccountDetailActivity.class,    R.drawable.plate_account);
        PLATES.put(StockDetailsActivity.class,     R.drawable.plate_stock);
        PLATES.put(MonthlyReportActivity.class,    R.drawable.plate_mis);
        PLATES.put(MisListActivity.class,          R.drawable.plate_mis);
        PLATES.put(TrialBalanceActivity.class,     R.drawable.plate_trial);
        PLATES.put(CustomersActivity.class,        R.drawable.plate_customers);
        PLATES.put(EmployeeActivityActivity.class, R.drawable.plate_activity);
        PLATES.put(NotificationsActivity.class,    R.drawable.plate_notifications);
        PLATES.put(BackupFilesActivity.class,      R.drawable.plate_backups);
        PLATES.put(SettingsActivity.class,         R.drawable.plate_settings);
        PLATES.put(ShopPickerActivity.class,       R.drawable.plate_shops);
    }

    private final Map<Activity, Stage> stages = new HashMap<>();

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Cinema());
    }

    private static boolean motionAllowed(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    // ── The lifecycle ────────────────────────────────────────────────

    @Override public void onActivityCreated(@NonNull Activity a, Bundle b) { }

    @Override public void onActivityStarted(@NonNull Activity a) {
        if (stages.containsKey(a)) return;
        // Started, not created: a screen's own onCreate has only just set its content.
        Stage s = new Stage(a);
        stages.put(a, s);
        s.dress();
    }

    @Override public void onActivityResumed(@NonNull Activity a) {
        Stage s = stages.get(a);
        if (s != null) s.roll();
    }

    @Override public void onActivityPaused(@NonNull Activity a) {
        Stage s = stages.get(a);
        if (s != null) s.cut();
    }

    @Override public void onActivityStopped(@NonNull Activity a) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { }
    @Override public void onActivityDestroyed(@NonNull Activity a) { stages.remove(a); }

    // ── One screen ───────────────────────────────────────────────────

    private static final class Stage implements SensorEventListener {
        final Activity activity;
        final boolean motion;
        PlateView plate;
        View emblem;
        ValueAnimator sweeper;
        SensorManager sensors;
        float smoothX, smoothY;

        Stage(Activity a) {
            activity = a;
            motion = motionAllowed(a);
        }

        void dress() {
            View root = activity.findViewById(android.R.id.content);
            if (!(root instanceof ViewGroup)) return;
            ViewGroup content = (ViewGroup) root;

            plate = activity.findViewById(R.id.bandPlate);
            if (plate != null) {
                Integer res = PLATES.get(activity.getClass());
                plate.setPlate(res != null ? res : R.drawable.plate_home);
                keepTitleClear(plate);
            }
            if (activity instanceof LoginActivity) {
                PlateView hero = activity.findViewById(R.id.heroPlate);
                if (hero != null) {
                    hero.setPlate(R.drawable.hero_login);
                    hero.setHero();
                    plate = hero;
                }
                emblem = activity.findViewById(R.id.ivEmblem);
            }
            if (content.getChildCount() > 0) ground(content.getChildAt(0));
            foot();

            pressTilt();
            if (motion) {
                riseIn(content);
                liftListsAsTheyFill(content);
            }
        }

        /** A screen sets its title when its data arrives, so the plate learns the title's width from each layout. */
        private void keepTitleClear(PlateView to) {
            View bar = activity.findViewById(R.id.toolbar);
            if (!(bar instanceof ViewGroup)) return;
            ViewGroup toolbar = (ViewGroup) bar;
            toolbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                float end = 0;
                for (int i = 0; i < toolbar.getChildCount(); i++) {
                    View c = toolbar.getChildAt(i);
                    if (c instanceof TextView && c.getVisibility() == View.VISIBLE) end = Math.max(end, c.getRight());
                }
                to.setTitleEnd(end);
            });
        }

        /** The page is a material under one pool of light, not a flat colour: velvet after dark, laid paper by day. */
        private void ground(View page) {
            Drawable bg = page.getBackground();
            if (!(bg instanceof ColorDrawable)) return;
            if (((ColorDrawable) bg).getColor() != ContextCompat.getColor(activity, R.color.parchment)) return;
            @DrawableRes int res = PawnApp.isDark(activity) ? R.drawable.page_velvet : R.drawable.page_parchment;
            Drawable cloth = ContextCompat.getDrawable(activity, res);
            if (cloth instanceof BitmapDrawable) ((BitmapDrawable) cloth).setDither(true);
            page.setBackground(cloth);
        }

        /**
         * The phone's own bar runs on from whatever is above it: the black foot of the menu where
         * a screen has one, and the page where it has none. Before Android 8.1 that bar cannot
         * have dark handles, so on a pale page it stays black there or the handles would vanish.
         */
        private void foot() {
            if (activity.findViewById(R.id.bottomNav) != null) return;
            boolean dark = PawnApp.isDark(activity);
            if (!dark && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return;
            Window w = activity.getWindow();
            // Sign-in ends on its flat sheet, every other screen on the rendered page.
            w.setNavigationBarColor(ContextCompat.getColor(activity,
                    activity instanceof LoginActivity ? R.color.parchment : R.color.page_foot));
            if (!dark) {
                View decor = w.getDecorView();
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }

        /** The top-level blocks of a screen settle into place, one after another. */
        private void riseIn(ViewGroup content) {
            ViewGroup body = bodyOf(content);
            if (body == null) return;
            int delay = 40;
            for (int i = 0; i < body.getChildCount(); i++) {
                View v = body.getChildAt(i);
                if (v.getVisibility() != View.VISIBLE || v.getId() == R.id.band || v.getId() == R.id.bottomNav) continue;
                settle(v, delay, 9f);
                delay = Math.min(delay + 38, 300);
            }
        }

        /** Where the blocks are: inside the screen's scroller when it has one directly under the band. */
        private ViewGroup bodyOf(ViewGroup content) {
            View first = content.getChildCount() > 0 ? content.getChildAt(0) : null;
            if (!(first instanceof ViewGroup)) return null;
            ViewGroup page = (ViewGroup) first;
            for (int i = 0; i < page.getChildCount(); i++) {
                View v = page.getChildAt(i);
                if (v instanceof ScrollView && ((ScrollView) v).getChildCount() == 1
                        && ((ScrollView) v).getChildAt(0) instanceof ViewGroup
                        && v.getVisibility() == View.VISIBLE) {
                    return (ViewGroup) ((ScrollView) v).getChildAt(0);
                }
            }
            return page;
        }

        /** A list fills after its reply arrives; the rows that arrive that way rise in, and scrolling stays plain. */
        private void liftListsAsTheyFill(View v) {
            if (v instanceof RecyclerView) {
                final RecyclerView list = (RecyclerView) v;
                list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override public void onChildViewAttachedToWindow(@NonNull View row) {
                        if (list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
                        int at = list.getChildAdapterPosition(row);
                        if (at < 0 || at > 12) return;
                        settle(row, 30 + at * 34L, 12f);
                    }
                    @Override public void onChildViewDetachedFromWindow(@NonNull View row) { }
                });
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) liftListsAsTheyFill(g.getChildAt(i));
            }
        }

        private void settle(View v, long delay, float degrees) {
            float density = v.getResources().getDisplayMetrics().density;
            v.setCameraDistance(1400 * density);
            v.setPivotX(v.getWidth() > 0 ? v.getWidth() / 2f : 0);
            v.setPivotY(0);
            v.setAlpha(0f);
            v.setRotationX(degrees);
            v.setTranslationY(14 * density);
            v.animate().alpha(1f).rotationX(0f).translationY(0f)
                    .setStartDelay(delay).setDuration(340)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .withLayer().start();
        }

        // ── Anything tappable leans back under the finger ────────────

        private void pressTilt() {
            final Window w = activity.getWindow();
            final Window.Callback inner = w.getCallback();
            if (inner == null || Proxy.isProxyClass(inner.getClass())) return;
            final Press press = new Press(motion);
            // A proxy, because Window.Callback grows methods with every Android release
            // and the phones run all of them. Everything but the touch passes straight through.
            Window.Callback outer = (Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[] {Window.Callback.class},
                    (proxy, method, args) -> {
                        if ("dispatchTouchEvent".equals(method.getName())) {
                            press.on(w.getDecorView(), (MotionEvent) args[0]);
                        }
                        try {
                            return method.invoke(inner, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause() != null ? e.getCause() : e;
                        }
                    });
            w.setCallback(outer);
        }

        // ── Tilt and light, only while the screen is up ──────────────

        void roll() {
            if (!motion) return;
            if (plate != null || emblem != null) {
                sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
                Sensor g = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (g == null && sensors != null) g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_UI);
            }
            if (plate != null && sweeper == null) {
                sweeper = ValueAnimator.ofFloat(-0.15f, 3.6f);
                sweeper.setDuration(SWEEP_EVERY_MS);
                sweeper.setRepeatCount(ValueAnimator.INFINITE);
                sweeper.setInterpolator(new LinearInterpolator());
                sweeper.addUpdateListener(an -> {
                    if (plate != null) plate.setSweep((float) an.getAnimatedValue());
                });
                sweeper.start();
            }
        }

        void cut() {
            if (sensors != null) sensors.unregisterListener(this);
            if (sweeper != null) { sweeper.cancel(); sweeper = null; }
            if (plate != null) plate.setSweep(-1f);
        }

        @Override public void onSensorChanged(SensorEvent e) {
            float x = clamp(e.values[0] / 5f), y = clamp((e.values[1] - 6f) / 5f);
            smoothX += (x - smoothX) * 0.09f;
            smoothY += (y - smoothY) * 0.09f;
            if (plate != null) plate.setTilt(smoothX, smoothY);
            if (emblem != null) {
                emblem.setRotationY(smoothX * -16f);
                emblem.setRotationX(smoothY * 10f);
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) { }

        private static float clamp(float v) { return v < -1 ? -1 : v > 1 ? 1 : v; }
    }

    /** What the finger is on, and how to answer it. */
    private static final class Press {
        final boolean motion;
        final Rect box = new Rect();
        final int[] at = new int[2];
        View held;

        Press(boolean motion) { this.motion = motion; }

        void on(View decor, MotionEvent e) {
            if (!motion) return;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    held = find(decor, e.getRawX(), e.getRawY());
                    if (held != null) lean(held, e.getRawX(), e.getRawY());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (held != null && !inside(held, e.getRawX(), e.getRawY())) release();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    release();
                    break;
                default:
                    break;
            }
        }

        private void lean(View v, float rawX, float rawY) {
            v.getLocationOnScreen(at);
            float nx = (rawX - at[0]) / Math.max(1, v.getWidth()) * 2f - 1f;
            float ny = (rawY - at[1]) / Math.max(1, v.getHeight()) * 2f - 1f;
            float density = v.getResources().getDisplayMetrics().density;
            v.setCameraDistance(1100 * density);
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            // A long row has less of itself to turn before its ends travel too far.
            float reach = v.getWidth() > 2.4f * v.getHeight() ? 0.45f : 1f;
            v.animate().cancel();
            v.animate().rotationY(nx * TILT_DEGREES * reach).rotationX(-ny * TILT_DEGREES)
                    .scaleX(0.98f).scaleY(0.98f).setStartDelay(0).setDuration(110)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }

        private void release() {
            View v = held;
            held = null;
            if (v == null) return;
            v.animate().cancel();
            v.animate().rotationX(0).rotationY(0).scaleX(1f).scaleY(1f)
                    .setStartDelay(0).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }

        private boolean inside(View v, float x, float y) {
            v.getLocationOnScreen(at);
            box.set(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
            return box.contains((int) x, (int) y);
        }

        /** The innermost tappable thing of a card's size under the finger. Never a field, and never a table's own row. */
        private View find(View v, float x, float y) {
            if (v.getVisibility() != View.VISIBLE || !inside(v, x, y)) return null;
            if (v instanceof ViewGroup && !(v instanceof Spinner)) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) {
                    View f = find(g.getChildAt(i), x, y);
                    if (f != null) return f;
                }
            }
            if (!v.isClickable() || v instanceof EditText || v instanceof GridRowView) return null;
            if (v instanceof ScrollView || v instanceof HorizontalScrollView || v instanceof AbsListView || v instanceof RecyclerView) return null;
            float density = v.getResources().getDisplayMetrics().density;
            if (v.getWidth() < 72 * density || v.getHeight() < 34 * density) return null;
            return v;
        }
    }
}
