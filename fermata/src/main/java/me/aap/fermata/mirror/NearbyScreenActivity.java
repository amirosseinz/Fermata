package me.aap.fermata.mirror;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.UUID;

import me.aap.fermata.R;

public final class NearbyScreenActivity extends Activity {
	private static final int REQUEST_CAPTURE = 82;
	private static final String AIRPLAY_RECEIVER = "io.github.jqssun.airplay";
	private LinearLayout content;
	private NsdManager nsd;
	private ScreenReceiver receiver;
	private NsdManager.DiscoveryListener discovery;
	private boolean discovering;
	private boolean resolving;
	private boolean capturePending;
	private AlertDialog pairingDialog;
	private NsdServiceInfo destination;
	private String pairing;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		nsd = getSystemService(NsdManager.class);
		home();
	}

	@SuppressWarnings("deprecation")
	private LinearLayout page(int description) {
		var layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = Math.round(getResources().getDisplayMetrics().density * 16);
		layout.setPadding(pad, pad, pad, pad);
		layout.setOnApplyWindowInsetsListener((view, insets) -> {
			if (android.os.Build.VERSION.SDK_INT >= 30) {
				var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
				view.setPadding(pad + safe.left, pad + safe.top, pad + safe.right, pad + safe.bottom);
			} else {
				view.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
						pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
			}
			return insets;
		});
		var title = new TextView(this);
		title.setText(R.string.screen_nearby);
		title.setTextSize(24);
		layout.addView(title);
		var text = new TextView(this);
		text.setText(description);
		text.setPadding(0, pad / 2, 0, pad);
		layout.addView(text);
		content = layout;
		setContentView(layout);
		layout.requestApplyInsets();
		return layout;
	}

	private void home() {
		if (pairingDialog != null) pairingDialog.dismiss();
		stopReceiver();
		stopDiscovery();
		resolving = false;
		if (!capturePending) {
			destination = null;
			pairing = null;
		}
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		page(R.string.screen_nearby_intro);
		button(R.string.screen_receive, this::receive);
		button(R.string.screen_send, this::discover);
		button(R.string.screen_airplay, this::openAirPlay);
		button(R.string.screen_stop, () -> stopService(new Intent(this, ScreenCaptureService.class)));
	}

	private Button button(int label, Runnable action) {
		var button = new Button(this);
		button.setText(label);
		button.setOnClickListener(v -> action.run());
		content.addView(button);
		return button;
	}

	private void receive() {
		if (nsd == null) {
			message(R.string.screen_discovery_error);
			return;
		}
		var layout = page(R.string.screen_receive_help);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		var code = ScreenLink.newCode();
		var label = new TextView(this);
		label.setText(getString(R.string.screen_pairing_display, code.replaceAll("(.{8})(?=.)", "$1-")));
		label.setTextIsSelectable(true);
		layout.addView(label);
		var copy = button(R.string.screen_copy, () -> {
			var clip = ClipData.newPlainText(getString(R.string.screen_pairing_code), code);
			var extras = new PersistableBundle();
			extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
			clip.getDescription().setExtras(extras);
			getSystemService(ClipboardManager.class).setPrimaryClip(clip);
		});
		var status = new TextView(this);
		status.setText(R.string.screen_advertising);
		layout.addView(status);
		button(R.string.screen_stop_receiver, this::home);

		var box = new FrameLayout(this);
		box.setBackgroundColor(Color.BLACK);
		layout.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
		var surface = new SurfaceView(this);
		box.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
		var cover = new View(this);
		cover.setBackgroundColor(Color.BLACK);
		box.addView(cover, new FrameLayout.LayoutParams(-1, -1));
		var size = new int[2];
		box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
				fitVideo(box, surface, size[0], size[1]));

		var name = "Fermata Screen " + UUID.randomUUID().toString().substring(0, 4);
		surface.getHolder().addCallback(new SurfaceHolder.Callback() {
			@Override public void surfaceCreated(SurfaceHolder holder) {
				if (content != layout || isFinishing()) return;
				stopReceiver();
				var listener = new ScreenReceiver.Listener() {
					@Override public void status(int text, String serviceName) {
						runOnUiThread(() -> {
							if (content != layout || receiver == null) return;
							status.setText(getString(text, serviceName));
							boolean connected = text == R.string.screen_connected;
							cover.setVisibility(connected ? View.GONE : View.VISIBLE);
							label.setVisibility(connected ? View.GONE : View.VISIBLE);
							copy.setVisibility(connected ? View.GONE : View.VISIBLE);
						});
					}
					@Override public void videoSize(int width, int height) {
						runOnUiThread(() -> {
							if (content != layout || receiver == null) return;
							size[0] = width;
							size[1] = height;
							fitVideo(box, surface, width, height);
						});
					}
				};
				receiver = new ScreenReceiver(nsd, holder.getSurface(), name, code, listener);
				receiver.start();
			}
			@Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {}
			@Override public void surfaceDestroyed(SurfaceHolder holder) {
				if (content == layout) stopReceiver();
			}
		});
	}

	private static void fitVideo(FrameLayout box, SurfaceView surface, int width, int height) {
		if (width <= 0 || height <= 0 || box.getWidth() == 0 || box.getHeight() == 0) return;
		float scale = Math.min((float) box.getWidth() / width, (float) box.getHeight() / height);
		int w = Math.max(1, Math.round(width * scale));
		int h = Math.max(1, Math.round(height * scale));
		var params = (FrameLayout.LayoutParams) surface.getLayoutParams();
		if (params.width == w && params.height == h) return;
		params.width = w;
		params.height = h;
		surface.setLayoutParams(params);
	}

	private void discover() {
		if (nsd == null) {
			message(R.string.screen_discovery_error);
			return;
		}
		stopDiscovery();
		var layout = page(R.string.screen_discover_help);
		var list = new ListView(this);
		var entries = new ArrayList<NsdServiceInfo>();
		var adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
		list.setAdapter(adapter);
		layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
		button(R.string.screen_back, this::home);
		list.setOnItemClickListener((parent, row, position, id) -> resolve(entries.get(position), layout));
		discovery = new NsdManager.DiscoveryListener() {
			@Override public void onDiscoveryStarted(String type) {
				runOnUiThread(() -> {
					if (discovery == this) discovering = true;
					else stop(this);
				});
			}
			@Override public void onServiceFound(NsdServiceInfo info) {
				runOnUiThread(() -> {
					if (discovery != this || indexOf(entries, info) >= 0) return;
					entries.add(info);
					adapter.add(info.getServiceName());
				});
			}
			@Override public void onServiceLost(NsdServiceInfo info) {
				runOnUiThread(() -> {
					if (discovery != this) return;
					int index = indexOf(entries, info);
					if (index < 0) return;
					entries.remove(index);
					adapter.remove(info.getServiceName());
				});
			}
			@Override public void onStartDiscoveryFailed(String type, int error) {
				runOnUiThread(() -> { if (discovery == this) message(R.string.screen_discovery_error); });
			}
			@Override public void onStopDiscoveryFailed(String type, int error) {}
			@Override public void onDiscoveryStopped(String type) {}
		};
		try {
			nsd.discoverServices(ScreenLink.SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
		} catch (RuntimeException e) {
			stopDiscovery();
			message(R.string.screen_discovery_error);
		}
	}

	private static int indexOf(ArrayList<NsdServiceInfo> entries, NsdServiceInfo info) {
		for (int i = 0; i < entries.size(); i++)
			if (entries.get(i).getServiceName().equals(info.getServiceName())) return i;
		return -1;
	}

	@SuppressWarnings("deprecation")
	private void resolve(NsdServiceInfo info, LinearLayout layout) {
		if (resolving || capturePending || pairingDialog != null) return;
		resolving = true;
		try {
			nsd.resolveService(info, new NsdManager.ResolveListener() {
				@Override public void onResolveFailed(NsdServiceInfo service, int error) {
					runOnUiThread(() -> {
						if (content != layout) return;
						resolving = false;
						message(R.string.screen_discovery_error);
					});
				}
				@Override public void onServiceResolved(NsdServiceInfo resolved) {
					runOnUiThread(() -> {
						if (content != layout) return;
						resolving = false;
						if (!discovering) return;
						if (resolved.getHost() == null) message(R.string.screen_discovery_error);
						else askCode(resolved);
					});
				}
			});
		} catch (RuntimeException e) {
			resolving = false;
			message(R.string.screen_discovery_error);
		}
	}

	@SuppressWarnings("deprecation")
	private void askCode(NsdServiceInfo resolved) {
		var input = new EditText(this);
		input.setSingleLine();
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		var dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.screen_pairing_code)
				.setMessage(getString(R.string.screen_pairing_help, resolved.getServiceName()))
				.setView(input)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.screen_send, null)
				.show();
		pairingDialog = dialog;
		dialog.setOnDismissListener(d -> pairingDialog = null);
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			var code = input.getText().toString();
			try {
				ScreenLink.parseCode(code);
			} catch (IllegalArgumentException e) {
				input.setError(getString(R.string.screen_invalid_code));
				return;
			}
			destination = resolved;
			pairing = code;
			capturePending = true;
			dialog.dismiss();
			var manager = getSystemService(MediaProjectionManager.class);
			try {
				startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
			} catch (RuntimeException e) {
				capturePending = false;
				destination = null;
				pairing = null;
				message(R.string.screen_send_error);
			}
		});
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void onActivityResult(int request, int result, Intent consent) {
		super.onActivityResult(request, result, consent);
		if (request != REQUEST_CAPTURE) return;
		var device = destination;
		var code = pairing;
		capturePending = false;
		home();
		if (result != RESULT_OK || consent == null) return;
		if (device == null || code == null) {
			message(R.string.screen_send_error);
			return;
		}

		var metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
		float scale = Math.min(1, 1280f / Math.max(metrics.widthPixels, metrics.heightPixels));
		int width = Math.max(16, ((int) (metrics.widthPixels * scale)) & ~15);
		int height = Math.max(16, ((int) (metrics.heightPixels * scale)) & ~15);
		var intent = new Intent(this, ScreenCaptureService.class)
				.putExtra(ScreenCaptureService.HOST, device.getHost().getHostAddress())
				.putExtra(ScreenCaptureService.PORT, device.getPort())
				.putExtra(ScreenCaptureService.PAIRING, code)
				.putExtra(ScreenCaptureService.CONSENT, consent)
				.putExtra(ScreenCaptureService.WIDTH, width)
				.putExtra(ScreenCaptureService.HEIGHT, height)
				.putExtra(ScreenCaptureService.DENSITY, Math.max(80, (int) (metrics.densityDpi * scale)));
		try {
			startForegroundService(intent);
		} catch (RuntimeException e) {
			message(R.string.screen_send_error);
		}
	}

	private void openAirPlay() {
		var intent = getPackageManager().getLaunchIntentForPackage(AIRPLAY_RECEIVER);
		if (intent != null) {
			try {
				startActivity(intent);
				return;
			} catch (ActivityNotFoundException ignored) {}
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.screen_airplay)
				.setMessage(R.string.screen_airplay_companion)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.screen_airplay_get, (d, i) -> {
					try {
						startActivity(new Intent(Intent.ACTION_VIEW,
								Uri.parse("https://f-droid.org/packages/" + AIRPLAY_RECEIVER + "/")));
					} catch (ActivityNotFoundException e) {
						message(R.string.screen_airplay_no_browser);
					}
				}).show();
	}

	private void message(int res) {
		Toast.makeText(this, res, Toast.LENGTH_LONG).show();
	}

	private void stopReceiver() {
		if (receiver == null) return;
		var active = receiver;
		receiver = null;
		active.close();
	}

	private void stopDiscovery() {
		var listener = discovery;
		discovery = null;
		if (discovering && listener != null) stop(listener);
		discovering = false;
	}

	private void stop(NsdManager.DiscoveryListener listener) {
		try {
			nsd.stopServiceDiscovery(listener);
		} catch (IllegalArgumentException ignored) {}
	}

	@Override protected void onStop() {
		home();
		super.onStop();
	}
}
