package me.aap.fermata.media.sub;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.LinkedHashMap;

public class OpenSubtitlesClientTest {
	@Test
	public void videoHashUsesFileLengthAndBothLittleEndianBlocks() throws Exception {
		var file = Files.createTempFile("video-hash", ".mp4");
		try {
			var bytes = new byte[3 * 65536];
			var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			data.putLong(0, 15);
			data.putLong(65536, 1000);
			data.putLong(2 * 65536, 25);
			Files.write(file, bytes);
			try (var stream = FileChannel.open(file)) {
				assertEquals("0000000000030028", OpenSubtitlesClient.hash(stream));
			}
			Files.write(file, new byte[10]);
			try (var stream = FileChannel.open(file)) {
				assertNull(OpenSubtitlesClient.hash(stream));
			}
		} finally {
			Files.delete(file);
		}
	}

	@Test
	public void apiRedirectAcceptsCanonicalQueryOnTheSameEndpoint() throws Exception {
		var request = URI.create("https://api.opensubtitles.com/api/v1/subtitles?query=Inception&languages=fa");
		assertEquals(URI.create("https://api.opensubtitles.com/api/v1/subtitles?languages=fa&query=inception"),
				OpenSubtitlesClient.apiRedirect(request, "/api/v1/subtitles?languages=fa&query=inception"));
		for (String location : new String[] { null, "http://api.opensubtitles.com/api/v1/subtitles",
				"https://example.com/api/v1/subtitles", "https://api.opensubtitles.com:444/api/v1/subtitles",
				"//user@api.opensubtitles.com/api/v1/subtitles", "/api/v1/download" }) {
			assertThrows(IOException.class, () -> OpenSubtitlesClient.apiRedirect(request, location));
		}
	}

	@Test
	public void persianResultsKeepExactMatchFirstAndDeduplicateFiles() throws Exception {
		var fixture = new JSONObject("""
				{"data":[
				  {"attributes":{"language":"fa","download_count":2000,"release":"Other Cut",
				    "files":[{"file_id":50,"file_name":"other.fa.srt"}]}},
				  {"attributes":{"language":"fa","moviehash_match":true,"download_count":12,
				    "files":[{"file_id":51,"file_name":"زیرنویس فارسی.srt"},{"file_id":50}]}},
				  {"attributes":{"files":[{},{"file_id":-1}]}}
				]}
				""");
		var entries = new LinkedHashMap<Long, OpenSubtitlesClient.Result>();
		OpenSubtitlesClient.collect(fixture, entries);
		var ordered = OpenSubtitlesClient.sorted(entries);
		assertEquals(2, ordered.size());
		assertEquals(51L, ordered.get(0).id());
		assertTrue(ordered.get(0).label().contains("زیرنویس فارسی.srt"));
		assertEquals("fa", ordered.get(0).language());
		assertEquals("Other Cut", ordered.get(1).release());
	}
}
