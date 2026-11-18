package me.aap.fermata.media.sub;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class VlSubClientTest {
	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void xmlRpcPreservesPersianAndEscapesTheTitle() throws Exception {
		byte[] request = SubtitleXmlRpc.request("SearchSubtitles", "token",
				List.of(Map.of("query", "فیلم <نام> & test", "sublanguageid", "per")));
		String xml = new String(request, StandardCharsets.UTF_8);
		assertTrue(xml.contains("فیلم &lt;نام&gt; &amp; test"));
		var response = SubtitleXmlRpc.response(utf8("""
				<?xml version="1.0" encoding="utf-8"?>
				<methodResponse><params><param><value><struct>
				<member><name>status</name><value><string>200 OK</string></value></member>
				<member><name>data</name><value><array><data><value><struct>
				<member><name>SubFileName</name><value><string>زیرنویس فارسی.srt</string></value></member>
				<member><name>count</name><value><int>2</int></value></member>
				</struct></value></data></array></value></member>
				</struct></value></param></params></methodResponse>
				"""));
		var rows = (List<?>) response.get("data");
		assertEquals("زیرنویس فارسی.srt", ((Map<?, ?>) rows.get(0)).get("SubFileName"));
		assertEquals(2L, ((Map<?, ?>) rows.get(0)).get("count"));
	}

	@Test
	public void falseDataIsAnEmptyResultAndFaultsAreReported() throws Exception {
		var response = SubtitleXmlRpc.response(utf8("""
				<methodResponse><params><param><value><struct>
				<member><name>data</name><value><boolean>0</boolean></value></member>
				</struct></value></param></params></methodResponse>
				"""));
		var results = new LinkedHashMap<Long, OpenSubtitlesClient.Result>();
		VlSubClient.collect(response, results);
		assertTrue(results.isEmpty());
		var fault = assertThrows(IOException.class, () -> SubtitleXmlRpc.response(utf8("""
				<methodResponse><fault><value><struct>
				<member><name>faultString</name><value><string>Rate limit reached</string></value></member>
				</struct></value></fault></methodResponse>
				""")));
		assertTrue(fault.getMessage().contains("Rate limit"));
	}

	@Test
	public void malformedAndEntityExpandingXmlAreRejected() {
		for (String xml : List.of("<methodResponse>", "<html>Maintenance</html>",
				"<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><methodResponse>&a;</methodResponse>")) {
			assertThrows(IOException.class, () -> SubtitleXmlRpc.response(utf8(xml)));
		}
	}

	@Test
	public void titleQueryIncludesSeasonEpisodeAndCorrectLanguage() {
		assertEquals("per", VlSubClient.languageCode("fa"));
		assertEquals("pob", VlSubClient.languageCode("pt-br"));
		assertEquals("all", VlSubClient.languageCode(""));
		assertEquals(Map.of("query", "Example", "sublanguageid", "per", "season", "2", "episode", "3"),
				VlSubClient.titleQuery("Example S02E03 1080p", "per"));
		assertEquals("4", VlSubClient.titleQuery("Example 1x04", "eng").get("episode"));
	}

	@Test
	public void exactMatchesOutrankPopularityAndUnsafeOrUnsupportedRowsAreIgnored() throws Exception {
		var first = new LinkedHashMap<String, Object>();
		first.putAll(Map.of("IDSubtitleFile", "10", "SubFileName", "فیلم.srt", "SubLanguageID", "per",
				"SubFormat", "srt", "SubDownloadsCnt", "8", "MatchedBy", "moviehash",
				"SubDownloadLink", "https://dl.opensubtitles.org/file/10.gz"));
		var popular = new LinkedHashMap<>(first);
		popular.putAll(Map.of("IDSubtitleFile", "11", "MatchedBy", "tag", "SubDownloadsCnt", "9000"));
		var unsafe = new LinkedHashMap<>(first);
		unsafe.putAll(Map.of("IDSubtitleFile", "12", "SubDownloadLink", "https://opensubtitles.org.evil.test/file"));
		var unsupported = new LinkedHashMap<>(first);
		unsupported.putAll(Map.of("IDSubtitleFile", "13", "SubFormat", "ass", "SubFileName", "film.ass"));
		var results = new LinkedHashMap<Long, OpenSubtitlesClient.Result>();
		VlSubClient.collect(Map.of("data", List.of(popular, first, first, unsafe, unsupported)), results);
		assertEquals(2, results.size());
		var sorted = OpenSubtitlesClient.sorted(results);
		assertEquals(10L, sorted.get(0).id());
		assertEquals("fa", sorted.get(0).language());
	}

	@Test
	public void gzipAndZipDownloadsPreserveSubtitleBytes() throws Exception {
		byte[] subtitle = utf8("1\n00:00:01,000 --> 00:00:03,000\nسلام دنیا\n");
		var gzip = new ByteArrayOutputStream();
		try (var output = new GZIPOutputStream(gzip)) { output.write(subtitle); }
		assertArrayEquals(subtitle, OpenSubtitlesClient.unpack(gzip.toByteArray()));
		var archive = new ByteArrayOutputStream();
		try (var output = new ZipOutputStream(archive)) {
			output.putNextEntry(new ZipEntry("folder/"));
			output.closeEntry();
			output.putNextEntry(new ZipEntry("README.txt"));
			output.write(utf8("Metadata"));
			output.closeEntry();
			output.putNextEntry(new ZipEntry("folder/film.fa.srt"));
			output.write(subtitle);
			output.closeEntry();
		}
		assertArrayEquals(subtitle, OpenSubtitlesClient.unpack(archive.toByteArray()));
	}

	@Test
	public void advertAndOversizedDownloadsAreRejected() throws Exception {
		assertThrows(IOException.class, () -> OpenSubtitlesClient.unpack(utf8(
				"1\n00:00:00,001 --> 04:00:00,003\nBecome OpenSubtitles.org VIP Member\nosdb.link/vip")));
		byte[] dialogue = utf8("1\n00:00:00,000 --> 00:00:01,000\nosdb.link/vip\n\n" +
				"2\n00:00:02,000 --> 00:00:03,000\nسلام");
		assertArrayEquals(dialogue, OpenSubtitlesClient.unpack(dialogue));
		var gzip = new ByteArrayOutputStream();
		try (var output = new GZIPOutputStream(gzip)) { output.write(new byte[4 * 1024 * 1024 + 1]); }
		assertThrows(IOException.class, () -> OpenSubtitlesClient.unpack(gzip.toByteArray()));
		for (String link : List.of("http://dl.opensubtitles.org/1.gz", "https://127.0.0.1/1.gz",
				"https://dl.opensubtitles.org:444/1.gz", "https://a@dl.opensubtitles.org/1.gz")) {
			assertThrows(IOException.class, () -> OpenSubtitlesClient.validateDownloadUri(URI.create(link)));
		}
	}

	@Test
	public void invalidSearchDataIsReportedInsteadOfAnEmptyList() {
		assertThrows(IOException.class, () -> VlSubClient.collect(Map.of("data", "Service unavailable"),
				new LinkedHashMap<>()));
	}

	@Test
	public void cancelledDownloadsStopReading() {
		Thread.currentThread().interrupt();
		try {
			assertThrows(java.io.InterruptedIOException.class, () ->
					OpenSubtitlesClient.readBounded(new java.io.ByteArrayInputStream(utf8("subtitle"))));
		} finally {
			Thread.interrupted();
		}
	}
}
