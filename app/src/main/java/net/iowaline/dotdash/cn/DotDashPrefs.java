package net.iowaline.dotdash.cn;

import android.os.Bundle;
import android.preference.PreferenceActivity;

/**
 * 设置页（从系统输入法设置中的"设置"进入）。
 * 目前提供：默认输入模式（中文/英文）。
 */
@SuppressWarnings("deprecation")
public class DotDashPrefs extends PreferenceActivity {

    /** SharedPreferences 键：默认输入模式（"0"=中文拼音，"1"=英文） */
    public static final String DEFAULT_MODE = "default_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.prefs);
    }
}
