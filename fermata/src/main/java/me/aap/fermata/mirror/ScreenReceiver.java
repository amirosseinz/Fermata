package me.aap.fermata.mirror;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import me.aap.fermata.R;

final class ScreenReceiver implements Closeable {
	interface Listener {
		void status(int label, String serviceName);
		void videoSize(int width, int height);
	}

	private final NsdManager nsd;
	private final Surface surface;
	private final String name;
	private final String code;
	private final Listener listener;
	private final Thread worker;
	private ServerSocket server;
	private Socket client;
	private boolean closed;
	private boolean registered;

	private final NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
		@Override public void onServiceRegistered(NsdServiceInfo info) {
			synchronized (ScreenReceiver.this) {
				registered = true;
				if (closed) unregister();
				else listener.status(R.string.screen_waiting, info.getServiceName());
			}
		}
		@Override public void onRegistrationFailed(NsdServiceInfo info, int error) {
			if (!isClosed()) listener.status(R.string.screen_discovery_error, "");
			close();
		}
		@Override public void onUnregistrationFailed(NsdServiceInfo info, int error) {}
		@Override public void onServiceUnregistered(NsdServiceInfo info) {}
	};

	ScreenReceiver(NsdManager nsd, Surface surface, String name, String code, Listener listener) {
		this.nsd = nsd;
		this.surface = surface;
		this.name = name;
		this.code = code;
		this.listener = listener;
		worker = new Thread(this::run, "Fermata screen receiver");
	}

	void start() {
		worker.start();
	}

	private void run() {
		try (var socket = new ServerSocket(0, 1)) {
			synchronized (this) {
				if (closed) return;
				server = socket;
				var info = new NsdServiceInfo();
				info.setServiceName(name);
				info.setServiceType(ScreenLink.SERVICE);
				info.setPort(socket.getLocalPort());
				nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
			}
			while (!isClosed()) {
				try (var sender = socket.accept()) {
					synchronized (this) {
						if (closed) return;
						client = sender;
					}
					var link = ScreenLink.receive(sender, code);
					ScreenFrames.play(link, surface, (width, height) -> {
						listener.videoSize(width, height);
						listener.status(R.string.screen_connected, "");
					});
				} catch (IOException | RuntimeException e) {
					if (!isClosed()) listener.status(R.string.screen_connection_ended, "");
				} finally {
					synchronized (this) { client = null; }
				}
			}
		} catch (IOException | RuntimeException e) {
			if (!isClosed()) listener.status(R.string.screen_receive_error, "");
		} finally {
			close();
		}
	}

	private synchronized boolean isClosed() {
		return closed;
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		unregister();
		closeQuietly(client);
		closeQuietly(server);
		worker.interrupt();
	}

	private void unregister() {
		if (!registered) return;
		registered = false;
		try {
			nsd.unregisterService(registration);
		} catch (IllegalArgumentException ignored) {
		}
	}

	static void closeQuietly(Closeable closeable) {
		if (closeable == null) return;
		try {
			closeable.close();
		} catch (IOException ignored) {
		}
	}
}
