package me.aap.fermata.mirror;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

public final class AirPlayActivity extends Activity {
	private AirPlayPanel panel;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		panel = new AirPlayPanel(this, this::finish);
		panel.setFitsSystemWindows(true);
		setContentView(panel);
	}

	@Override
	protected void onStart() {
		super.onStart();
		panel.setVideoAllowed(true);
	}

	@Override
	protected void onStop() {
		panel.close();
		super.onStop();
	}
}
