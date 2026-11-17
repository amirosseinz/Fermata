package me.aap.fermata.mirror;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import me.aap.fermata.R;

public final class ScreenCaptureService extends Service {
	static final String HOST = "host";
	static final String PORT = "port";
	static final String PAIRING = "pairing";
	static final String CONSENT = "consent";
	static final String WIDTH = "width";
	static final String HEIGHT = "height";
	static final String DENSITY = "density";
	private static final String STOP = "stop";
	private static final String CHANNEL = "fermata_screen_share";
	private static final int NOTIFICATION = 467895;
	private volatile Socket client;
	private volatile boolean stopping;
	private Thread worker;

	@Override public IBinder onBind(Intent intent) { return null; }

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || STOP.equals(intent.getAction())) {
			stopSharing();
			return START_NOT_STICKY;
		}
		if (worker != null) {
			showMessage(R.string.screen_already_sharing);
			return START_NOT_STICKY;
		}
		try {
			var notification = createNotification();
			if (Build.VERSION.SDK_INT >= 29)
				startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
			else startForeground(NOTIFICATION, notification);
		} catch (RuntimeException e) {
			showMessage(R.string.screen_send_error);
			stopSelf();
			return START_NOT_STICKY;
		}
		worker = new Thread(() -> capture(intent), "Fermata screen sender");
		worker.start();
		return START_NOT_STICKY;
	}

	private Notification createNotification() {
		getSystemService(NotificationManager.class)
				.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.screen_nearby),
						NotificationManager.IMPORTANCE_LOW));
		var stop = PendingIntent.getService(this, 0,
				new Intent(this, ScreenCaptureService.class).setAction(STOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		return new Notification.Builder(this, CHANNEL)
				.setSmallIcon(R.drawable.notification)
				.setContentTitle(getString(R.string.screen_capture_active))
				.setOngoing(true)
				.addAction(new Notification.Action.Builder(null, getString(R.string.screen_stop), stop).build())
				.build();
	}

	@SuppressWarnings("deprecation")
	private void capture(Intent intent) {
		MediaProjection projection = null;
		VirtualDisplay display = null;
		MediaCodec codec = null;
		Surface input = null;
		var callback = new MediaProjection.Callback() {
			@Override public void onStop() { stopSharing(); }
		};
		try (var socket = new Socket()) {
			client = socket;
			if (stopping) return;
			socket.connect(new InetSocketAddress(intent.getStringExtra(HOST), intent.getIntExtra(PORT, 0)), 5_000);
			var link = ScreenLink.send(socket, intent.getStringExtra(PAIRING));
			if (stopping) return;

			Intent consent = intent.getParcelableExtra(CONSENT);
			int width = intent.getIntExtra(WIDTH, 720);
			int height = intent.getIntExtra(HEIGHT, 1280);
			int density = intent.getIntExtra(DENSITY, 160);
			var manager = getSystemService(MediaProjectionManager.class);
			projection = manager.getMediaProjection(Activity.RESULT_OK, consent);
			if (projection == null) throw new IOException("Screen capture consent expired");
			projection.registerCallback(callback, new Handler(Looper.getMainLooper()));
			var format = MediaFormat.createVideoFormat(ScreenFrames.MIME, width, height);
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			format.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
			format.setInteger(MediaFormat.KEY_FRAME_RATE, 24);
			format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
			format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000);
			codec = MediaCodec.createEncoderByType(ScreenFrames.MIME);
			codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			input = codec.createInputSurface();
			codec.start();
			display = projection.createVirtualDisplay("Fermata Nearby Screen", width, height, density,
					DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null);
			if (display == null) throw new IOException("Could not create screen capture");
			sendVideo(link, codec);
		} catch (IOException | RuntimeException e) {
			if (!stopping) showMessage(R.string.screen_send_error);
		} finally {
			try {
				if (display != null) display.release();
				if (codec != null) codec.release();
				if (input != null) input.release();
			} finally {
				client = null;
				try {
					if (projection != null) {
						projection.unregisterCallback(callback);
						projection.stop();
					}
				} finally {
					stopSelf();
				}
			}
		}
	}

	private void sendVideo(ScreenLink link, MediaCodec codec) throws IOException {
		var info = new MediaCodec.BufferInfo();
		boolean configured = false;
		long lastWrite = SystemClock.elapsedRealtime();
		while (!stopping) {
			int index = codec.dequeueOutputBuffer(info, 50_000);
			if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !configured) {
				link.write(ScreenFrames.configuration(codec.getOutputFormat()));
				configured = true;
				lastWrite = SystemClock.elapsedRealtime();
			} else if (index >= 0) {
				try {
					var frame = codec.getOutputBuffer(index);
					if (configured && frame != null && info.size > 0 &&
							(info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
						link.write(ScreenFrames.frame(frame, info));
						lastWrite = SystemClock.elapsedRealtime();
					}
				} finally {
					codec.releaseOutputBuffer(index, false);
				}
			} else if (configured && SystemClock.elapsedRealtime() - lastWrite >= 4_000) {
				link.write(new byte[0]);
				lastWrite = SystemClock.elapsedRealtime();
			}
		}
	}

	private void showMessage(int res) {
		new Handler(Looper.getMainLooper()).post(() ->
				Toast.makeText(this, res, Toast.LENGTH_LONG).show());
	}

	private void stopSharing() {
		stopping = true;
		ScreenReceiver.closeQuietly(client);
		stopSelf();
	}

	@Override public void onDestroy() {
		stopping = true;
		ScreenReceiver.closeQuietly(client);
		stopForeground(STOP_FOREGROUND_REMOVE);
		super.onDestroy();
	}
}
