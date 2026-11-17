package me.aap.fermata.mirror;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class ScreenLinkTest {
	@Test
	public void pairingCodeAcceptsGroupingAndCase() {
		String code = ScreenLink.newCode();
		assertNotEquals(code, ScreenLink.newCode());
		assertArrayEquals(ScreenLink.parseCode(code),
				ScreenLink.parseCode(" " + code.substring(0, 16).toUpperCase(Locale.ROOT)
						+ "-" + code.substring(16) + " "));
	}

	@Test
	public void malformedPairingCodesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> ScreenLink.parseCode("1234"));
		assertThrows(IllegalArgumentException.class, () -> ScreenLink.parseCode("z".repeat(32)));
		assertThrows(IllegalArgumentException.class, () -> ScreenLink.parseCode("0".repeat(33)));
	}

	@Test
	public void pairedDevicesExchangeBinaryFramesAndHeartbeatInOrder() throws Exception {
		String code = ScreenLink.newCode();
		byte[] first = {0, 1, 2, -1};
		byte[] second = {3, 0, -1};
		var executor = Executors.newSingleThreadExecutor();
		try (var server = listen()) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					var link = ScreenLink.receive(socket, code);
					assertThrows(IOException.class, () -> link.write(first));
					assertArrayEquals(first, link.read());
					assertArrayEquals(new byte[0], link.read());
					assertArrayEquals(second, link.read());
				}
				return null;
			});
			try (var socket = connect(server)) {
				var link = ScreenLink.send(socket, code);
				assertThrows(IOException.class, link::read);
				assertThrows(IOException.class, () -> link.write(new byte[2 * 1024 * 1024 + 1]));
				link.write(first);
				link.write(new byte[0]);
				link.write(second);
			}
			receiver.get(5, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void wrongPairingCodeIsRejectedOnBothSides() throws Exception {
		var executor = Executors.newSingleThreadExecutor();
		try (var server = listen()) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					ScreenLink.receive(socket, "0123456789abcdef0123456789abcdef");
				}
				return null;
			});
			try (var socket = connect(server)) {
				assertThrows(IOException.class,
						() -> ScreenLink.send(socket, "ffeeddccbbaa99887766554433221100"));
			}
			assertTrue(assertThrows(ExecutionException.class,
					() -> receiver.get(5, TimeUnit.SECONDS)).getCause() instanceof IOException);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void rejectsUnauthenticatedFrameAndOversizedRecord() throws Exception {
		checkInvalidRecord(16, "Screen packet authentication failed");
		checkInvalidRecord(Integer.MAX_VALUE, "Invalid screen packet length");
	}

	private static void checkInvalidRecord(int length, String message) throws Exception {
		String code = ScreenLink.newCode();
		var executor = Executors.newSingleThreadExecutor();
		try (var server = listen()) {
			var receiver = executor.submit(() -> {
				try (var socket = server.accept()) {
					return assertThrows(IOException.class, ScreenLink.receive(socket, code)::read);
				}
			});
			try (var socket = connect(server)) {
				ScreenLink.send(socket, code);
				var wire = new DataOutputStream(socket.getOutputStream());
				wire.writeInt(length);
				if (length == 16) wire.write(new byte[16]);
				wire.flush();
			}
			assertEquals(message, receiver.get(5, TimeUnit.SECONDS).getMessage());
		} finally {
			executor.shutdownNow();
		}
	}

	private static ServerSocket listen() throws IOException {
		var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		server.setSoTimeout(5_000);
		return server;
	}

	private static Socket connect(ServerSocket server) throws IOException {
		var socket = new Socket();
		socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 5_000);
		return socket;
	}
}
