package me.aap.fermata.mirror;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Keep;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;

@Keep
final class AirPlayReceiver implements AutoCloseable {
	private static final String TAG = "FermataAirPlay";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final AtomicBoolean IN_USE = new AtomicBoolean();

	interface Listener {
		void status(int label, String detail);
		void dimensions(int width, int height);
		void frameRendered();
		void disconnected();
	}

	private final Context context;
	private final NsdManager nsd;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ArrayList<Advertisement> ads = new ArrayList<>();
	private final Listener listener;
	private final AirPlayDecoder decoder;
	private final Surface surface;
	private final int pin = RANDOM.nextInt(10_000);
	private final Thread worker;
	private final AtomicBoolean closed = new AtomicBoolean();
	private int registered;

	AirPlayReceiver(Context context, Surface surface, Listener listener) {
		this.context = context.getApplicationContext();
		this.surface = surface;
		nsd = this.context.getSystemService(NsdManager.class);
		this.listener = listener;
		decoder = new AirPlayDecoder(surface, () -> main.post(() -> {
			if (!closed.get()) listener.frameRendered();
		}));
		worker = new Thread(this::run, "Fermata AirPlay");
	}

	void start() {
		worker.start();
	}

	private void run() {
		if (!BuildConfig.AIRPLAY_RECEIVER || nsd == null) {
			fail(R.string.airplay_error);
			return;
		}
		if (!IN_USE.compareAndSet(false, true)) {
			fail(R.string.airplay_busy);
			return;
		}
		long handle = 0;
		try {
			if (closed.get()) return;
			System.loadLibrary("fermata_airplay");
			byte[] address = new byte[6];
			RANDOM.nextBytes(address);
			address[0] = (byte) ((address[0] & 0xfc) | 2);
			// A separate key per opt-in session forces a fresh pairing.
			var key = new File(context.getNoBackupFilesDir(), "airplay-session-key");
			if (key.exists() && !key.delete()) throw new IllegalStateException("Previous AirPlay key is in use");
			handle = nativeOpen(address, key.getAbsolutePath(), pin);
			if (key.exists() && !key.delete()) Log.w(TAG, "Could not remove temporary pairing key");
			if (handle == 0) throw new IllegalStateException("AirPlay server could not bind");
			int port = nativePort(handle);
			String[] airplay = nativeRecord(handle, true);
			String[] raop = nativeRecord(handle, false);
			main.post(() -> register(port, airplay, raop));
			synchronized (closed) {
				while (!closed.get()) closed.wait();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException | LinkageError e) {
			Log.e(TAG, "AirPlay startup failed", e);
			fail(R.string.airplay_error);
		} finally {
			try {
				if (handle != 0) nativeClose(handle);
			} finally {
				decoder.close();
				IN_USE.set(false);
			}
		}
	}

	private void register(int port, String[] airplay, String[] raop) {
		if (closed.get() || !surface.isValid()) {
			close();
			return;
		}
		try {
			register(port, "_airplay._tcp.", airplay);
			register(port, "_raop._tcp.", raop);
		} catch (RuntimeException e) {
			Log.e(TAG, "AirPlay registration failed", e);
			fail(R.string.airplay_error);
		}
	}

	private void register(int port, String type, String[] values) {
		if (port <= 0 || values == null || values.length < 3 || (values.length & 1) == 0)
			throw new IllegalArgumentException("Invalid AirPlay advertisement");
		var info = new NsdServiceInfo();
		info.setServiceName(values[0]);
		info.setServiceType(type);
		info.setPort(port);
		for (int i = 1; i < values.length; i += 2) info.setAttribute(values[i], values[i + 1]);
		var ad = new Advertisement();
		ads.add(ad);
		nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, ad);
	}

	private void fail(int label) {
		main.post(() -> {
			if (closed.get()) return;
			listener.status(label, "");
			close();
		});
	}

	private void onVideo(byte[] bytes, long timestampUs) {
		if (!closed.get()) decoder.video(bytes, timestampUs);
	}

	private void onSize(int width, int height) {
		if (closed.get()) return;
		decoder.dimensions(width, height);
		main.post(() -> {
			if (!closed.get()) listener.dimensions(width, height);
		});
	}

	private void onActive(boolean playing) {
		if (playing || closed.get()) return;
		decoder.close();
		main.post(() -> {
			if (!closed.get()) listener.disconnected();
		});
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) return;
		synchronized (closed) {
			closed.notifyAll();
		}
		main.post(() -> {
			for (var ad : ads) ad.remove();
			ads.clear();
		});
	}

	private final class Advertisement implements NsdManager.RegistrationListener {
		private boolean active;

		@Override
		public void onServiceRegistered(NsdServiceInfo info) {
			main.post(() -> {
				active = true;
				if (closed.get()) remove();
				else if (++registered == 2) listener.status(R.string.airplay_listening,
						String.format(java.util.Locale.ROOT, "%04d", pin));
			});
		}

		@Override public void onRegistrationFailed(NsdServiceInfo info, int error) { fail(R.string.airplay_error); }
		@Override public void onServiceUnregistered(NsdServiceInfo info) {}
		@Override public void onUnregistrationFailed(NsdServiceInfo info, int error) {}

		private void remove() {
			if (!active) return;
			active = false;
			try {
				nsd.unregisterService(this);
			} catch (IllegalArgumentException e) {
				Log.d(TAG, "AirPlay advertisement already removed");
			}
		}
	}

	private native long nativeOpen(byte[] address, String keyFile, int pairingPin);
	private native int nativePort(long handle);
	private native String[] nativeRecord(long handle, boolean airplay);
	private native void nativeClose(long handle);
}
