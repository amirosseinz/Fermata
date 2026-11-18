package me.aap.fermata.media.sub;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import me.aap.fermata.BuildConfig;

/** VLSub-compatible anonymous search, using Fermata's own client identity. */
final class VlSubClient {
	private static final URI ENDPOINT = URI.create("https://api.opensubtitles.org/xml-rpc");
	private static final Pattern EPISODE = Pattern.compile("(?i)(.*?)\\bS(\\d{1,3})E(\\d{1,4})\\b");
	private static final Pattern EPISODE_ALTERNATIVE = Pattern.compile("(?i)(.*?)\\b(\\d{1,3})x(\\d{1,4})\\b");

	List<OpenSubtitlesClient.Result> search(String title, String language,
			OpenSubtitlesClient.Fingerprint fingerprint) throws Exception {
		var login = call("LogIn", "", "", "en", "Fermata " + BuildConfig.VERSION_NAME);
		String token = string(login, "token");
		if (token.isBlank()) throw new IOException("OpenSubtitles did not start an anonymous search session.");
		try {
			var results = new LinkedHashMap<Long, OpenSubtitlesClient.Result>();
			String legacyLanguage = languageCode(language);
			if (fingerprint != null && fingerprint.bytes() >= 131072) {
				collect(call("SearchSubtitles", token, List.of(Map.of(
						"moviehash", fingerprint.hash(), "moviebytesize", Long.toString(fingerprint.bytes()),
						"sublanguageid", legacyLanguage)), Map.of("limit", 100)), results);
				if (!results.isEmpty()) return OpenSubtitlesClient.sorted(results);
			}
			collect(call("SearchSubtitles", token, List.of(titleQuery(title, legacyLanguage)),
					Map.of("limit", 100)), results);
			return OpenSubtitlesClient.sorted(results);
		} finally {
			// Direct download links remain usable after search ends. Logout is best effort.
			if (!Thread.currentThread().isInterrupted()) {
				try {
					call("LogOut", token);
				} catch (IOException ignored) {
					// A failed logout must not discard valid search results.
				}
			}
		}
	}

	static Map<String, String> titleQuery(String title, String language) {
		var query = new LinkedHashMap<String, String>();
		query.put("sublanguageid", language);
		var episode = EPISODE.matcher(title);
		if (!episode.find()) {
			episode = EPISODE_ALTERNATIVE.matcher(title);
			if (!episode.find()) {
				query.put("query", title);
				return query;
			}
		}
		String name = episode.group(1).trim();
		query.put("query", name.isBlank() ? title : name);
		query.put("season", Integer.toString(Integer.parseInt(episode.group(2))));
		query.put("episode", Integer.toString(Integer.parseInt(episode.group(3))));
		return query;
	}

	static String languageCode(String language) {
		return switch (language) {
			case "", "all" -> "all";
			case "en" -> "eng";
			case "fa" -> "per";
			case "ar" -> "ara";
			case "fr" -> "fre";
			case "es" -> "spa";
			case "de" -> "ger";
			case "tr" -> "tur";
			case "ru" -> "rus";
			case "it" -> "ita";
			case "pt-pt", "pt" -> "por";
			case "pt-br" -> "pob";
			case "zh-cn", "zh" -> "chi";
			case "ja" -> "jpn";
			case "ko" -> "kor";
			case "hi" -> "hin";
			case "ur" -> "urd";
			default -> throw new IllegalArgumentException("Unsupported subtitle language: " + language);
		};
	}

	private static String modernLanguage(String code) {
		return switch (code) {
			case "eng" -> "en";
			case "per", "fas" -> "fa";
			case "ara" -> "ar";
			case "fre", "fra" -> "fr";
			case "spa" -> "es";
			case "ger", "deu" -> "de";
			case "tur" -> "tr";
			case "rus" -> "ru";
			case "ita" -> "it";
			case "por" -> "pt-pt";
			case "pob" -> "pt-br";
			case "chi", "zho" -> "zh-cn";
			case "jpn" -> "ja";
			case "kor" -> "ko";
			case "hin" -> "hi";
			case "urd" -> "ur";
			default -> code;
		};
	}

	static void collect(Map<String, Object> response, LinkedHashMap<Long, OpenSubtitlesClient.Result> results)
			throws IOException {
		Object value = response.get("data");
		if (Boolean.FALSE.equals(value)) return; // The legacy API uses false for an empty result.
		if (!(value instanceof List<?> data)) throw new IOException("OpenSubtitles returned an invalid search result.");
		for (var item : data) {
			if (!(item instanceof Map<?, ?> row)) continue;
			String filename = string(row, "SubFileName");
			String format = string(row, "SubFormat");
			if (!FileSubtitles.isSupported(filename) || !(format.equals("srt") || format.equals("vtt"))) continue;
			String link = string(row, "SubDownloadLink");
			long id = positiveLong(row, "IDSubtitleFile");
			try {
				OpenSubtitlesClient.validateDownloadUri(URI.create(link));
			} catch (IOException | IllegalArgumentException error) {
				continue;
			}
			if (id <= 0) continue;
			var result = new OpenSubtitlesClient.Result(id, filename, string(row, "MovieReleaseName"),
					modernLanguage(string(row, "SubLanguageID")), "moviehash".equals(string(row, "MatchedBy")),
					(int) Math.min(Integer.MAX_VALUE, positiveLong(row, "SubDownloadsCnt")), link);
			results.merge(id, result, (old, incoming) -> incoming.exact() ? incoming : old);
		}
	}

	private static long positiveLong(Map<?, ?> row, String key) {
		try {
			return Math.max(0L, Long.parseLong(string(row, key)));
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	private static String string(Map<?, ?> row, String key) {
		Object value = row.get(key);
		return value == null ? "" : value.toString();
	}

	private static Map<String, Object> call(String method, Object... parameters) throws IOException {
		if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Subtitle search cancelled.");
		byte[] body = SubtitleXmlRpc.request(method, parameters);
		var connection = OpenSubtitlesClient.connection(ENDPOINT);
		try {
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
			try (var stream = connection.getOutputStream()) {
				stream.write(body);
			}
			int status = connection.getResponseCode();
			if (status < 200 || status >= 300)
				throw new IOException("OpenSubtitles search service returned HTTP " + status + ". Try again later.");
			Map<String, Object> result;
			try (var stream = connection.getInputStream()) {
				result = SubtitleXmlRpc.response(OpenSubtitlesClient.readBounded(stream));
			}
			String serviceStatus = string(result, "status");
			if (!serviceStatus.startsWith("200"))
				throw new IOException("OpenSubtitles: " + serviceStatus + ". Try again or use the website.");
			return result;
		} finally {
			connection.disconnect();
		}
	}
}
