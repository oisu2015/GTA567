package com.dushetv.box;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 「正在加载」背景：两张由 App 截图上下裁剪后拼成的海报长图，
 * 用 BitmapShader 纵向平铺、以不同速度上下循环滚动并半透明叠加，
 * 再压一层上下渐暗的遮罩和一条青色扫描线。
 *
 * 只在数据加载期间显示，替代原来的空白网格 + 小转圈。
 * 每帧只画 2 个 rect（GPU 侧 shader 平移），25fps，不占主线程、不抢焦点。
 */
public class LoadingPosterView extends View {

    private Bitmap a, b;
    private BitmapShader sa, sb;
    private final Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scan = new Paint();
    private final Matrix ma = new Matrix();
    private final Matrix mb = new Matrix();

    private float period = 1f;      // 平铺周期（屏幕像素）
    private float offA = 0f, offB = 0f;
    private boolean running = false;
    private LinearGradient dimGrad;
    private int lastH = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            offA = (offA + 1.7f) % period;
            offB = (offB + 3.4f) % period;   // 2 倍速，形成视差叠加
            invalidate();
            postDelayed(this, 66);           // v4.7：15fps（原 25fps）。老盒子加载期与测速并发，降帧减 CPU 争用
        }
    };

    public LoadingPosterView(Context c) { super(c); init(); }
    public LoadingPosterView(Context c, AttributeSet s) { super(c, s); init(); }
    public LoadingPosterView(Context c, AttributeSet s, int def) { super(c, s, def); init(); }

    private void init() {
        setFocusable(false);
        setClickable(false);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // 省一半内存，海报背景不需要透明
        try {
            a = BitmapFactory.decodeResource(getResources(), R.drawable.loading_strip_a, o);
            b = BitmapFactory.decodeResource(getResources(), R.drawable.loading_strip_b, o);
        } catch (Throwable ignored) {
        }
        if (a != null) {
            sa = new BitmapShader(a, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            pa.setShader(sa);
            pa.setAlpha(140);
        }
        if (b != null) {
            sb = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            pb.setShader(sb);
            pb.setAlpha(90);
        }
        scan.setColor(0x6622D3EE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (a != null) period = Math.max(1f, a.getHeight() * ((float) w / a.getWidth()));
        if (h != lastH) {
            lastH = h;
            dimGrad = new LinearGradient(0, 0, 0, h,
                    new int[]{0xD6081020, 0x8A081420, 0xD6081020},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            dim.setShader(dimGrad);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        c.drawColor(0xFF0A1424);
        if (a == null || sa == null) return;

        float sx = (float) w / a.getWidth();
        ma.setScale(sx, sx);
        ma.postTranslate(0, -offA);
        sa.setLocalMatrix(ma);
        c.drawRect(0, 0, w, h, pa);         // 后景：慢速

        if (sb != null) {
            mb.setScale(sx, sx);
            mb.postTranslate(0, -offB);
            sb.setLocalMatrix(mb);
            c.drawRect(0, 0, w, h, pb);     // 前景：2 倍速叠加
        }

        c.drawRect(0, 0, w, h, dim);        // 上下渐暗，保证文字可读

        float sy = (offA * 0.6f) % h;
        c.drawRect(0, sy, w, sy + 2, scan); // 扫描线：表示「正在加载」
    }

    /** 显示并启动滚动（可重复调用） */
    public void startLoading() {
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        if (running) return;
        running = true;
        removeCallbacks(tick);
        post(tick);
    }

    /** 隐藏并停止滚动，避免后台空转耗电 */
    public void stopLoading() {
        running = false;
        removeCallbacks(tick);
        if (getVisibility() != GONE) setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        // 窗口失焦（弹对话框、切后台）时暂停，回来再续，避免无谓重绘
        if (!has) {
            running = false;
            removeCallbacks(tick);
        } else if (getVisibility() == VISIBLE && !running) {
            running = true;
            post(tick);
        }
    }
}
