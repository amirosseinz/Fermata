package me.aap.fermata.mirror;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Host JNI fixture. Android discovery and surfaces require a real device. */
public final class AirPlayReceiver {
	private native long nativeOpen(byte[] address, String keyFile, int pairingPin);
	private native int nativePort(long handle);
	private native String[] nativeRecord(long handle, boolean airplay);
	private native void nativeClose(long handle);

	@SuppressWarnings("unused") private void onVideo(byte[] data, long pts) {}
	@SuppressWarnings("unused") private void onSize(int width, int height) {}
	@SuppressWarnings("unused") private void onActive(boolean active) {}

	static {
		System.loadLibrary("fermata_airplay");
	}

	public static void main(String[] args) throws Exception {
		var test = new AirPlayReceiver();
		var files = Files.createTempDirectory("fermata-airplay-test");
		var key = files.resolve("test-key");
		try {
			for (int run = 0; run < 2; run++) {
				long server = test.nativeOpen(new byte[]{2, 12, 34, 56, 78, (byte) run},
						key.toString(), 8355);
				check(server != 0, "Could not initialize native server");
				int port = test.nativePort(server);
				try {
					check(port > 0 && port < 65536, "No listening port");
					checkRecord(test.nativeRecord(server, true), "Fermata");
					checkRecord(test.nativeRecord(server, false), "020C22384E0" + run + "@Fermata");
					var info = request(port, "GET /info RTSP/1.0");
					check(info.startsWith("RTSP/1.0 200") && info.contains("bplist00"), "Invalid /info: " + info);
					var denied = request(port, "SETUP rtsp://127.0.0.1/42 RTSP/1.0");
					check(denied.startsWith("RTSP/1.0 470"), "Unpaired SETUP was not denied: " + denied);
					check(request(port, "POST /feedback RTSP/1.0").startsWith("RTSP/1.0 200"),
							"RTSP feedback failed");
				} finally {
					test.nativeClose(server);
				}
				try (Socket ignored = connect(port)) {
					throw new AssertionError("Port remained open after stopping AirPlay");
				} catch (java.net.ConnectException expected) {
					System.out.println("PASS native start, AirPlay and RAOP records, /info, " +
							"unpaired SETUP denial, feedback and clean stop (" + (run + 1) + ")");
				}
			}
		} finally {
			Files.deleteIfExists(key);
			Files.deleteIfExists(files);
		}
	}

	private static void checkRecord(String[] record, String name) {
		check(record != null && record.length >= 3 && (record.length & 1) == 1,
				"Malformed DNS-SD record");
		check(name.equalsIgnoreCase(record[0]), "Unexpected advertised name " + Arrays.toString(record));
		Map<String, String> txt = new HashMap<>();
		for (int i = 1; i < record.length; i += 2) txt.put(record[i], record[i + 1]);
		check("true".equals(txt.get("pw")), "PIN or password requirement missing");
		check(txt.containsKey("features") || txt.containsKey("ft"), "Feature flags missing");
	}

	private static Socket connect(int port) throws IOException {
		var socket = new Socket();
		try {
			socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1000);
			socket.setSoTimeout(1000);
			return socket;
		} catch (IOException e) {
			socket.close();
			throw e;
		}
	}

	private static String request(int port, String firstLine) throws IOException {
		try (var socket = connect(port)) {
			String request = firstLine + "\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n";
			socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
			var data = new java.io.ByteArrayOutputStream();
			var input = socket.getInputStream();
			while (data.size() < 16384) {
				int next = input.read();
				if (next < 0) break;
				data.write(next);
				if (data.size() >= 4 && next == '\n') {
					String header = data.toString(StandardCharsets.ISO_8859_1);
					if (!header.endsWith("\r\n\r\n")) continue;
					for (String line : header.split("\r\n")) {
						if (!line.regionMatches(true, 0, "Content-Length:", 0, 15)) continue;
						int size = Integer.parseInt(line.substring(15).strip());
						check(size < 32768, "Invalid protocol frame size");
						data.write(input.readNBytes(size));
					}
					break;
				}
			}
			return data.toString(StandardCharsets.ISO_8859_1);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
