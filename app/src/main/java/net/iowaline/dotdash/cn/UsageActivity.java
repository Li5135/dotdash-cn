package net.iowaline.dotdash.cn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;

/**
 * 桌面引导页：展示使用方法，并提供"启用输入法"入口。
 * （输入法服务本体在系统设置中启用；此页仅为方便首次使用）
 */
public class UsageActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.usage);

        setTitle(getString(R.string.usage_title));

        Button enable = findViewById(R.id.btn_enable_ime);
        enable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });
    }
}
