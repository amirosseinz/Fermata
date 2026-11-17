package me.aap.fermata.whisper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

final class WhisperModelStore {
	private static final Set<File> DOWNLOADING = new HashSet<>();
	private final File directory;

	WhisperModelStore(File filesDirectory) {
		this.directory = new File(filesDirectory, "whisper");
	}

	File directory() {
		return directory;
	}

	boolean installed(WhisperModels.Model model) {
		var file = file(model);
		return file.isFile() && file.length() == model.bytes();
	}

	File require(WhisperModels.Model model) throws IOException {
		if (!installed(model)) throw new IOException("Download " + model.id() + " in Whisper models first");
		var file = file(model);
		var digest = digest();
		try (var in = new FileInputStream(file)) {
			var buffer = new byte[128 * 1024];
			for (int n; (n = in.read(buffer)) != -1; ) digest.update(buffer, 0, n);
		}
		verify(model, digest);
		return file;
	}

	void delete(WhisperModels.Model model) throws IOException {
		var file = file(model);
		synchronized (DOWNLOADING) {
			if (DOWNLOADING.contains(file)) throw new IOException("A download is in progress");
			if (file.exists() && !file.delete()) throw new IOException("Could not delete " + model.id());
		}
	}

	void download(WhisperModels.Model model, BooleanSupplier cancelled, IntConsumer progress)
			throws IOException {
		download(model, URI.create(model.url()).toURL(), cancelled, progress);
	}

	void download(WhisperModels.Model model, URL url, BooleanSupplier cancelled, IntConsumer progress)
			throws IOException {
		var file = file(model);
		synchronized (DOWNLOADING) {
			if (!DOWNLOADING.add(file)) throw new IOException("A download is already in progress");
		}
		File temp = null;
		try {
			if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
				throw new IOException("Could not create Whisper model directory");
			if (directory.getUsableSpace() < model.bytes())
				throw new IOException("Insufficient storage for " + model.id());
			temp = File.createTempFile("model-", ".part", directory);
			var connection = (HttpURLConnection) url.openConnection();
			try {
				connection.setConnectTimeout(15000);
				connection.setReadTimeout(15000);
				connection.setRequestProperty("Accept-Encoding", "identity");
				checkCancelled(cancelled);
				int status = connection.getResponseCode();
				if (status != 200) throw new IOException("Model server returned HTTP " + status);
				if ("https".equals(url.getProtocol()) &&
						!"https".equals(connection.getURL().getProtocol()))
					throw new IOException("Model server redirected to an insecure connection");
				var digest = digest();
				long received = 0;
				int lastProgress = 0;
				try (var in = connection.getInputStream(); var out = new FileOutputStream(temp)) {
					var buffer = new byte[128 * 1024];
					for (int n; (n = in.read(buffer)) != -1; ) {
						checkCancelled(cancelled);
						received += n;
						if (received > model.bytes()) throw new IOException("Unexpected model size");
						digest.update(buffer, 0, n);
						out.write(buffer, 0, n);
						int percent = (int) (received * 100 / model.bytes());
						if (percent > lastProgress) progress.accept(lastProgress = percent);
					}
				}
				checkCancelled(cancelled);
				if (received != model.bytes()) throw new IOException("Incomplete model download");
				verify(model, digest);
				if (!temp.renameTo(file)) throw new IOException("Could not install " + model.id());
			} finally {
				connection.disconnect();
			}
		} finally {
			if (temp != null && temp.exists()) temp.delete();
			synchronized (DOWNLOADING) {
				DOWNLOADING.remove(file);
			}
		}
	}

	private File file(WhisperModels.Model model) {
		return new File(directory, model.filename());
	}

	private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
		if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException err) {
			throw new IllegalStateException(err);
		}
	}

	private static void verify(WhisperModels.Model model, MessageDigest digest) throws IOException {
		var hex = model.sha256();
		var expected = new byte[hex.length() / 2];
		for (int i = 0; i < expected.length; i++) {
			expected[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		if (!Arrays.equals(expected, digest.digest()))
			throw new IOException("SHA-256 integrity check failed for " + model.id());
	}
}
