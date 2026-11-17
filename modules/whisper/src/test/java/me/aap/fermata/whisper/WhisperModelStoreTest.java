package me.aap.fermata.whisper;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;

public class WhisperModelStoreTest {
	@Rule public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void catalogContainsUniqueModelsWithValidMetadata() {
		var ids = new HashSet<String>();
		for (var model : WhisperModels.MODELS) {
			assertTrue(ids.add(model.id()));
			assertTrue(model.bytes() > 0);
			assertTrue(model.sha256().matches("[a-f0-9]{64}"));
			assertEquals("https", URI.create(model.url()).getScheme());
		}
		assertEquals(32152673L, WhisperModels.find("tiny-q5_1").bytes());
		assertFalse(WhisperModels.find("tiny-q5_1").englishOnly());
		assertTrue(WhisperModels.find("medium.en").url().contains("ggml-medium.en.bin"));
		var turbo = WhisperModels.find("large-v3-turbo-q4_0");
		assertEquals(473992235L, turbo.bytes());
		assertFalse(turbo.englishOnly());
		assertTrue(turbo.url().contains("/resolve/2df9591948d91c0a80d47faa2a3a96d195ce138a/"));
		assertTrue(turbo.bytes() < WhisperModels.find("large-v3-turbo-q5_0").bytes());
	}

	@Test
	public void downloadRetriesAfterHttpIntegrityAndCancellationFailures() throws Exception {
		byte[] body = "test model data".getBytes(StandardCharsets.UTF_8);
		var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		var model = new WhisperModels.Model("test", "unused", body.length, hash);
		var store = new WhisperModelStore(tmp.getRoot());
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/failed", exchange -> {
			exchange.sendResponseHeaders(503, -1);
			exchange.close();
		});
		server.createContext("/corrupt", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(new byte[body.length]);
			}
		});
		server.createContext("/truncated", exchange -> {
			exchange.sendResponseHeaders(200, body.length - 1);
			try (var out = exchange.getResponseBody()) {
				out.write(body, 0, body.length - 1);
			}
		});
		server.createContext("/ok", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		try {
			var root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			var url = root.resolve("/ok").toURL();
			for (String path : new String[]{"/failed", "/corrupt", "/truncated"}) {
				assertThrows(java.io.IOException.class,
						() -> store.download(model, root.resolve(path).toURL(), () -> false, unused -> {}));
				assertFalse(store.installed(model));
			}
			assertThrows(java.io.InterruptedIOException.class,
					() -> store.download(model, url, () -> true, unused -> {}));
			assertEquals(0, store.directory().list().length);
			var progress = new ArrayList<Integer>();
			store.download(model, url, () -> false, progress::add);
			assertTrue(store.installed(model));
			assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
			assertArrayEquals(body, Files.readAllBytes(store.require(model).toPath()));
			store.delete(model);
			assertFalse(store.installed(model));
		} finally {
			server.stop(0);
		}
	}
}
