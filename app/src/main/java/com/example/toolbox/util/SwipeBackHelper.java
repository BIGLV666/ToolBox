package com.example.toolbox.util;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

/**
 * 将“从左边缘右滑返回”附加到任意页面 View 的轻量控制器。
 *
 * <p>手势必须从左侧 30dp 内开始。移动期间页面按手指水平位移的 82% 跟手平移并逐渐降低透明度；
 * Android 12 及以上还按完成进度增加最大 12px 的实时模糊。模糊使用 {@link Shader.TileMode#CLAMP}
 * 延展边缘像素，避免卷积采样在 View 边缘产生透明黑边。旧系统只执行位移和透明度动画。</p>
 *
 * <p>调用方应从 Activity 的 {@code dispatchTouchEvent} 转交事件。控制器只在已经形成有效横向拖动后消费
 * 事件，因此普通点击和非边缘手势仍交给原 View 层级处理。</p>
 */
public final class SwipeBackHelper {
    private SwipeBackHelper() { }

    public static Controller create(View page, Runnable action) {
        return new Controller(page, action);
    }

    public static final class Controller {
        private final View page;
        private final Runnable action;
        private final float edge;
        private final float threshold;
        private float startX;
        private float startY;
        private boolean tracking;

        private Controller(View page, Runnable action) {
            this.page = page;
            this.action = action;
            float density = page.getResources().getDisplayMetrics().density;
            edge = 30 * density;
            threshold = 112 * density;
        }

        public boolean handle(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    tracking = startX <= edge;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!tracking) return false;
                    float distance = Math.max(0, event.getX() - startX);
                    // 垂直偏移明显超过横向意图时立即放弃，避免与列表滚动争抢手势。
                    if (Math.abs(event.getY() - startY) > Math.max(distance, edge * 2)) {
                        reset();
                        return false;
                    }
                    render(distance);
                    return distance > edge;
                case MotionEvent.ACTION_UP:
                    if (!tracking) return false;
                    float moved = Math.max(0, event.getX() - startX);
                    tracking = false;
                    if (moved >= threshold) {
                        // 达到 112dp 阈值后先把页面送出屏幕，再执行真正的页面返回动作并复位供下次显示。
                        page.animate().translationX(page.getWidth()).alpha(0.55f).setDuration(180)
                                .withEndAction(() -> {
                                    clearEffect();
                                    action.run();
                                    page.setTranslationX(0);
                                    page.setAlpha(1f);
                                }).start();
                    } else {
                        // 未达到阈值时回弹；结束回调负责清除 RenderEffect，避免静止页面残留模糊。
                        page.animate().translationX(0).alpha(1f).setDuration(180)
                                .withEndAction(this::clearEffect).start();
                    }
                    return moved > edge;
                case MotionEvent.ACTION_CANCEL:
                    reset();
                    return false;
                default:
                    return false;
            }
        }

        private void render(float distance) {
            float progress = Math.min(1f, distance / threshold);
            page.setTranslationX(distance * 0.82f);
            page.setAlpha(1f - progress * 0.18f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                float radius = progress * 12f;
                page.setRenderEffect(radius < 0.5f ? null
                        : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            }
        }

        private void reset() {
            tracking = false;
            page.animate().translationX(0).alpha(1f).setDuration(160)
                    .withEndAction(this::clearEffect).start();
        }

        private void clearEffect() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) page.setRenderEffect(null);
        }
    }
}
