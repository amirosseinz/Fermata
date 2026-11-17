package me.aap.fermata.mirror;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.aap.fermata.R;

public final class AirPlayPanel extends LinearLayout implements AutoCloseable {
	private final TextView status;
	private final FrameLayout box;
	private final SurfaceView video;
	private final View cover;
	private AirPlayReceiver receiver;
	private boolean allowed;
	private int width;
	private int height;

	public AirPlayPanel(Context context, Runnable exit) {
		super(context);
		setOrientation(VERTICAL);
		setBackgroundColor(Color.BLACK);
		setClickable(true);
		int pad = Math.round(getResources().getDisplayMetrics().density * 12);

		var header = new LinearLayout(context);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(pad, pad, pad, pad);
		addView(header, new LayoutParams(-1, -2));
		status = new TextView(context);
		status.setTextColor(Color.WHITE);
		status.setText(R.string.airplay_parked_only);
		header.addView(status, new LayoutParams(0, -2, 1));
		var close = new Button(context);
		close.setText(R.string.screen_stop_receiver);
		close.setOnClickListener(v -> exit.run());
		header.addView(close);

		box = new FrameLayout(context);
		addView(box, new LayoutParams(-1, 0, 1));
		video = new SurfaceView(context);
		video.setVisibility(INVISIBLE);
		box.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
		cover = new View(context);
		cover.setBackgroundColor(Color.BLACK);
		box.addView(cover, new FrameLayout.LayoutParams(-1, -1));
		box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
		video.getHolder().addCallback(new SurfaceHolder.Callback() {
			@Override public void surfaceCreated(SurfaceHolder holder) { start(); }
			@Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) {}
			@Override public void surfaceDestroyed(SurfaceHolder holder) { stop(); }
		});
	}

	public void setVideoAllowed(boolean allowed) {
		if (this.allowed == allowed) return;
		this.allowed = allowed;
		if (allowed) {
			video.setVisibility(VISIBLE);
			cover.setVisibility(VISIBLE);
			status.setText(R.string.airplay_starting);
			start();
		} else {
			stop();
			video.setVisibility(INVISIBLE);
			status.setText(R.string.airplay_parked_only);
		}
	}

	private void start() {
		var surface = video.getHolder().getSurface();
		if (!allowed || !isAttachedToWindow() || !surface.isValid() || receiver != null) return;
		var listener = new AirPlayReceiver.Listener() {
			@Override public void status(int label, String detail) {
				if (allowed && receiver != null) status.setText(getContext().getString(label, detail));
			}
			@Override public void dimensions(int width, int height) {
				if (!allowed || receiver == null) return;
				AirPlayPanel.this.width = width;
				AirPlayPanel.this.height = height;
				fitVideo();
			}
			@Override public void frameRendered() {
				if (!allowed || receiver == null) return;
				cover.setVisibility(GONE);
				status.setText(R.string.airplay_connected);
			}
			@Override public void disconnected() {
				if (!allowed || receiver == null) return;
				cover.setVisibility(VISIBLE);
				status.setText(R.string.airplay_ended);
			}
		};
		receiver = new AirPlayReceiver(getContext(), surface, listener);
		receiver.start();
	}

	private void fitVideo() {
		if (width == 0 || height == 0 || box.getWidth() == 0 || box.getHeight() == 0) return;
		float scale = Math.min((float) box.getWidth() / width, (float) box.getHeight() / height);
		int w = Math.max(1, Math.round(width * scale));
		int h = Math.max(1, Math.round(height * scale));
		var lp = (FrameLayout.LayoutParams) video.getLayoutParams();
		if (lp.width == w && lp.height == h) return;
		lp.width = w;
		lp.height = h;
		video.setLayoutParams(lp);
	}

	private void stop() {
		cover.setVisibility(VISIBLE);
		var old = receiver;
		receiver = null;
		if (old != null) old.close();
	}

	@Override
	protected void onDetachedFromWindow() {
		close();
		super.onDetachedFromWindow();
	}

	@Override
	public void close() {
		allowed = false;
		stop();
		video.setVisibility(INVISIBLE);
	}
}
