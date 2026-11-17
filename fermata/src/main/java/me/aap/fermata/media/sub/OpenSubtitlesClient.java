package me.aap.fermata.media.sub;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import me.aap.fermata.BuildConfig;

public final class OpenSubtitlesClient {
	private static final String API_HOST = "api.opensubtitles.com";
	private static final String VIP_HOST = "vip-api.opensubtitles.com";
	private static final int HASH_BLOCK = 65536;
	private static final int MAX_RESPONSE = 4 * 1024 * 1024;
	private static final Pattern EPISODE = Pattern.compile("(?i)(.*?)\\bS(\\d{1,3})E(\\d{1,4})\\b");
	private final String key;

	public OpenSubtitlesClient(String key) {
		this.key = key;
	}

	public List<Result> search(String title, String language, String hash) throws Exception {
		var results = new LinkedHashMap<Long, Result>();
		String filters = language.isEmpty() ? "" : "&languages=" + Uri.encode(language);
		if (hash != null) {
			collect(request(API_HOST, "/subtitles?moviehash=" + hash + filters, null, null), results);
			if (!results.isEmpty()) return sorted(results);
		}
		var episode = EPISODE.matcher(title);
		String numbers = "";
		if (episode.find() && !episode.group(1).isBlank()) {
			title = episode.group(1).trim();
			numbers = "&season_number=" + episode.group(2) + "&episode_number=" + episode.group(3);
		}
		collect(request(API_HOST, "/subtitles?query=" + Uri.encode(title) + filters + numbers,
				null, null), results);
		return sorted(results);
	}

	public byte[] download(Result result, String username, String password) throws Exception {
		var login = request(API_HOST, "/login", new JSONObject().put("username", username)
				.put("password", password), null);
		var host = login.optString("base_url", API_HOST);
		if (!API_HOST.equals(host) && !VIP_HOST.equals(host)) throw new IOException("Unexpected API host.");
		var response = request(host, "/download", new JSONObject().put("file_id", result.id())
				.put("sub_format", "srt"), login.getString("token"));
		var link = URI.create(response.getString("link"));
		var bytes = getDownload(link);
		if (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
			try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
				for (int i = 0; i < 16; i++) {
					var entry = zip.getNextEntry();
					if (entry == null) break;
					if (entry.isDirectory() || !FileSubtitles.isSupported(entry.getName()))
						throw new IOException("The archive needs to contain an SRT or VTT subtitle.");
					var file = readBounded(zip);
					if (file.length > 0) return file;
				}
			}
			throw new IOException("The archive contains no subtitle file.");
		}
		return bytes;
	}

	private JSONObject request(String host, String path, JSONObject body, String token) throws Exception {
		var uri = URI.create("https://" + host + "/api/v1" + path);
		for (int attempt = 0; attempt < 5; attempt++) {
			var connection = connection(uri);
			try {
				connection.setRequestProperty("Accept", "application/json");
				connection.setRequestProperty("Api-Key", key);
				if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
				if (body != null) {
					connection.setRequestMethod("POST");
					connection.setDoOutput(true);
					connection.setRequestProperty("Content-Type", "application/json");
					try (var stream = connection.getOutputStream()) {
						stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
					}
				}
				int status = connection.getResponseCode();
				if (body == null && isRedirect(status)) {
					uri = apiRedirect(uri, connection.getHeaderField("Location"));
					continue;
				}
				checkResponse(status, body != null && path.equals("/login"));
				try (var stream = connection.getInputStream()) {
					return new JSONObject(new String(readBounded(stream), StandardCharsets.UTF_8));
				}
			} finally {
				connection.disconnect();
			}
		}
		throw new IOException("Too many OpenSubtitles API redirects.");
	}

	static URI apiRedirect(URI current, String location) throws IOException {
		if (location == null || location.isBlank())
			throw new IOException("OpenSubtitles redirect has no destination.");
		URI redirect;
		try {
			redirect = current.resolve(location);
		} catch (IllegalArgumentException err) {
			throw new IOException("OpenSubtitles returned an invalid redirect.", err);
		}
		if (!"https".equalsIgnoreCase(redirect.getScheme()) ||
				!current.getHost().equalsIgnoreCase(redirect.getHost()) ||
				(redirect.getPort() != -1 && redirect.getPort() != 443) ||
				!current.getRawPath().equals(redirect.getRawPath()) ||
				redirect.getUserInfo() != null) {
			throw new IOException("OpenSubtitles redirected outside its API endpoint.");
		}
		return redirect;
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private static byte[] getDownload(URI uri) throws IOException {
		for (int attempt = 0; attempt < 5; attempt++) {
			var connection = connection(uri);
			try {
				int status = connection.getResponseCode();
				if (isRedirect(status)) {
					var location = connection.getHeaderField("Location");
					if (location == null) throw new IOException("Subtitle download redirect has no destination.");
					uri = uri.resolve(location);
					continue;
				}
				checkResponse(status, false);
				try (var stream = connection.getInputStream()) {
					return readBounded(stream);
				}
			} finally {
				connection.disconnect();
			}
		}
		throw new IOException("Too many subtitle download redirects.");
	}

	private static HttpURLConnection connection(URI uri) throws IOException {
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
			throw new IOException("Subtitle server must use HTTPS.");
		var connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setInstanceFollowRedirects(false);
		connection.setConnectTimeout(15000);
		connection.setReadTimeout(15000);
		connection.setRequestProperty("User-Agent", "Fermata v" + BuildConfig.VERSION_NAME);
		return connection;
	}

	private static void checkResponse(int status, boolean login) throws IOException {
		if (status >= 200 && status < 300) return;
		if (status == 429 || status == 406) throw new IOException("OpenSubtitles quota or rate limit reached.");
		if (status == 401 || status == 403) throw new IOException(login ?
				"OpenSubtitles rejected the credentials or API key." :
				"OpenSubtitles rejected the API key or account access.");
		throw new IOException("OpenSubtitles returned HTTP " + status + ".");
	}

	private static byte[] readBounded(InputStream stream) throws IOException {
		var output = new ByteArrayOutputStream();
		var buffer = new byte[8192];
		for (int read; (read = stream.read(buffer)) != -1; ) {
			if (output.size() + read > MAX_RESPONSE) throw new IOException("Subtitle response exceeds 4 MiB.");
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	static void collect(JSONObject response, LinkedHashMap<Long, Result> results) {
		var data = response.optJSONArray("data");
		if (data == null) return;
		for (int i = 0; i < data.length(); i++) {
			var row = data.optJSONObject(i);
			var attrs = row == null ? null : row.optJSONObject("attributes");
			if (attrs == null) continue;
			var files = attrs.optJSONArray("files");
			if (files == null) continue;
			for (int f = 0; f < files.length(); f++) {
				var file = files.optJSONObject(f);
				if (file == null) continue;
				long id = file.optLong("file_id", 0);
				if (id <= 0) continue;
				var name = file.optString("file_name", "Subtitle");
				results.putIfAbsent(id, new Result(id, name.isBlank() ? "Subtitle" : name,
						attrs.optString("release"), attrs.optString("language"),
						attrs.optBoolean("moviehash_match"), attrs.optInt("download_count")));
			}
		}
	}

	static List<Result> sorted(LinkedHashMap<Long, Result> results) {
		var list = new ArrayList<>(results.values());
		list.sort(Comparator.comparing(Result::exact).reversed()
				.thenComparing(Comparator.comparingInt(Result::downloads).reversed()));
		return list;
	}

	public static String localHash(ContentResolver resolver, Uri video) {
		if (video == null || !("file".equals(video.getScheme()) || "content".equals(video.getScheme())))
			return null;
		try {
			var descriptor = resolver.openFileDescriptor(video, "r");
			if (descriptor == null) return null;
			try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
				return hash(stream.getChannel());
			}
		} catch (IOException | RuntimeException ignored) {
			return null;
		}
	}

	static String hash(FileChannel channel) throws IOException {
		long size = channel.size();
		if (size < HASH_BLOCK * 2L) return null;
		long sum = size;
		var block = ByteBuffer.allocate(HASH_BLOCK).order(ByteOrder.LITTLE_ENDIAN);
		for (long offset : new long[]{0, size - HASH_BLOCK}) {
			channel.position(offset);
			block.clear();
			while (block.hasRemaining()) if (channel.read(block) <= 0) return null;
			block.flip();
			while (block.hasRemaining()) sum += block.getLong();
		}
		return String.format(Locale.ROOT, "%016x", sum);
	}

	public record Result(long id, String filename, String release, String language, boolean exact, int downloads) {
		public String label() {
			return filename + "\n" + (exact ? "Exact video match · " : "") +
					language + " · " + downloads + " downloads" +
					(release.isBlank() || release.equals(filename) ? "" : "\n" + release);
		}
	}
}
