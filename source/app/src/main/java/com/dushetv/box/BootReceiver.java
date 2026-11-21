package com.dushetv.box;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启动接收器：监听 {@code BOOT_COMPLETED}，在设备<b>真正重启 / 断电后来电</b>时
 * 自动拉起主界面。
 *
 * <p>注意（Android TV 重要限制）：电视日常「按电源键开机」绝大多数情况是<b>从待机（standby）
 * 唤醒</b>，并非真正重启，因此不会触发 {@code BOOT_COMPLETED}——本接收器只在真重启时生效。
 * 这是系统行为，无法绕过；若要做到「开屏即本软件」需把 App 设为默认桌面（HOME Launcher），
 * 那对内容类 App 过于侵入，本项目不采用。</p>
 *
 * <p>设计遵循现有工程纪律：零新依赖、零弹窗、不阻塞。</p>
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                // 尊重设置页的「开机自启动」开关：用户在设置里关掉则不再拉起主界面
                android.content.SharedPreferences sp =
                        context.getSharedPreferences("box", android.content.Context.MODE_PRIVATE);
                if (!sp.getBoolean("boot", true)) return;
                Intent it = new Intent(context, MainActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(it);
            } catch (Throwable ignored) {
                // 任何异常都不影响系统开机流程
            }
        }
    }
}
