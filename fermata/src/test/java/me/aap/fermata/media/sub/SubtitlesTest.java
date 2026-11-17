package me.aap.fermata.media.sub;


import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import me.aap.utils.concurrent.HandlerExecutor;
import me.aap.utils.function.Cancellable;

/**
 * @author Andrey Pavlenko
 */
public class SubtitlesTest extends Assert {

	@Test
	public void testGetNext() {
		int n = 1000;
		var rnd = new Random();
		Subtitles s = new Subtitles(b -> {});
		assertNull(s.getNext(1));

		s = new Subtitles(b -> {
			for (int i = 0; i < n; i++) {
				b.add("Text " + i, rnd.nextInt(100000), rnd.nextInt(100) + 1);
			}
		});

		for (int i = 0, c = s.size(); i < c; i++) {
			assertEquals(i, s.get(i).getIndex());
		}
		for (var t : s) {
			assertSame(t, s.getNext(t.getTime()));
		}
		s.get(s.size() / 2);
		for (int i = s.size() - 1; i >= 0; i--) {
			Subtitles.Text t = s.get(i);
			assertSame(t, s.getNext(t.getTime()));
		}
	}

	@Test
	public void testSrtSubtitles() throws IOException {
		// @formatter:off
		var text =
"""
WEBVTT
Kind: captions
Language: en

1
00:00:00.900 --> 00:00:01.100
{\\an7}TOP_LEFT

2
00:01:01,000 --> 00:01:03,000
{\\an5}MIDDLE_CENTER

3
09:59:59,999 --> 10:59:59.999
{\\an2}BOTTOM_CENTER

4
11:00:00.000 --> 11:00:00,001
BOTTOM_CENTER

5
12:00:00,000 --> 12:00:00,001
BOTTOM_CENTER
Multi line
""";
		// @formatter:on

		var sg = FileSubtitles.load(new ByteArrayInputStream(text.getBytes()));
		var s = sg.get(SubGrid.Position.TOP_LEFT).get(0);
		assertEquals(900, s.getTime());
		assertEquals(200, s.getDuration());
		assertEquals("TOP_LEFT", s.getText());
		s = sg.get(SubGrid.Position.MIDDLE_CENTER).get(0);
		assertEquals(61000, s.getTime());
		assertEquals(2000, s.getDuration());
		assertEquals("MIDDLE_CENTER", s.getText());
		s = sg.get(SubGrid.Position.BOTTOM_CENTER).get(0);
		assertEquals(9 * 60 * 60000 + 59 * 60000 + 59000 + 999, s.getTime());
		assertEquals(3600000, s.getDuration());
		assertEquals("BOTTOM_CENTER", s.getText());
		s = sg.get(SubGrid.Position.BOTTOM_CENTER).get(1);
		assertEquals(11 * 60 * 60000, s.getTime());
		assertEquals(1, s.getDuration());
		assertEquals("BOTTOM_CENTER", s.getText());
		s = sg.get(SubGrid.Position.BOTTOM_CENTER).get(2);
		assertEquals(12 * 60 * 60000, s.getTime());
		assertEquals(1, s.getDuration());
		assertEquals("BOTTOM_CENTER\nMulti line", s.getText());
	}

	@Test
	public void persianSurvivesUnicodeAndLegacyDecoding() throws IOException {
		var cue = "سلام دنیا! این زیرنویس فارسی است. English ۱۲۳";
		var srt = "1\r\n00:00:01,500 --> 00:00:02,750\r\n" + cue;
		for (String name : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
			var grid = FileSubtitles.load(new ByteArrayInputStream(
					("\uFEFF" + srt).getBytes(Charset.forName(name))));
			var parsed = grid.get(SubGrid.Position.BOTTOM_CENTER).get(0);
			assertEquals(cue, parsed.getText());
			assertEquals(1500, parsed.getTime());
			assertEquals(1250, parsed.getDuration());
		}
		var legacy = Charset.forName("windows-1256");
		var legacyCue = "سلام دنيا! فارسي و English";
		var grid = FileSubtitles.load(new ByteArrayInputStream(srt.replace(cue, legacyCue)
				.getBytes(legacy)), legacy);
		assertEquals(legacyCue, grid.get(SubGrid.Position.BOTTOM_CENTER).get(0).getText());
		var supplementary = srt + " 🎬";
		grid = FileSubtitles.load(new ByteArrayInputStream(supplementary.getBytes(StandardCharsets.UTF_8)));
		assertEquals(cue + " 🎬", grid.get(SubGrid.Position.BOTTOM_CENTER).get(0).getText());
	}

	@Test
	public void singleTrackMovesExplicitSrtPositionsToBottomCenter() throws IOException {
		var cue = "سلام دنیا! English";
		var input = "1\n00:00:01,000 --> 00:00:03,000\n{\\an3}" + cue;
		var grid = FileSubtitles.load(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
		assertEquals(cue, grid.get(SubGrid.Position.BOTTOM_RIGHT).get(0).getText());
		grid.mergeAtPosition(SubGrid.Position.BOTTOM_CENTER);
		assertTrue(grid.get(SubGrid.Position.BOTTOM_RIGHT).isEmpty());
		var centered = grid.get(SubGrid.Position.BOTTOM_CENTER).get(0);
		assertEquals(cue, centered.getText());
		assertEquals(1000L, centered.getTime());
		assertEquals(2000, centered.getDuration());
	}

	@Test
	public void webVttSkipsMetadataAndInvalidCues() throws IOException {
		var text = """
				WEBVTT

				NOTE ignored
				00:00.000 --> 00:10.000
				ignored

				00:10.000 --> 00:09.000
				invalid

				cue-id
				00:10.000 --> 00:11.500 align:start
				سلام
				English
				""";
		var grid = FileSubtitles.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
		var cues = grid.get(SubGrid.Position.BOTTOM_CENTER);
		assertEquals(1, cues.size());
		assertEquals("سلام\nEnglish", cues.get(0).getText());
		assertEquals(10000, cues.get(0).getTime());
		assertNull(cues.getNext(11500));
		assertTrue(FileSubtitles.load(new ByteArrayInputStream("invalid".getBytes())).isEmpty());
	}

	@Test
	public void sidecarDiscoveryRequiresExactNameOrLanguageSuffix() {
		assertTrue(FileSubtitles.isSidecar("Movie", "Movie.en.srt"));
		assertTrue(FileSubtitles.isSidecar("Movie", "MOVIE.fa.VTT"));
		assertTrue(FileSubtitles.isSidecar("Movie", "Movie.srt"));
		assertFalse(FileSubtitles.isSidecar("Movie", "Movie2.en.srt"));
		assertFalse(FileSubtitles.isSidecar("Movie", "Movie.en.txt"));
	}

	@Test
	public void previewDoesNotDisplayFutureCuesAndHonorsDelayWhenSeeking() {
		var grid = new SubGrid(b -> b.add("سلام", 1000, 1000, SubGrid.Position.BOTTOM_CENTER));
		var delivered = new ArrayList<String>();
		HandlerExecutor executor = new HandlerExecutor() {
			@Override
			public Cancellable schedule(Runnable task, long delay) {
				return failScheduling();
			}
		};
		var scheduler = new SubScheduler(executor, grid,
				(position, cue) -> delivered.add(cue == null ? "" : cue.getText()));
		scheduler.preview(1200, 300);
		scheduler.preview(1300, 300);
		scheduler.preview(2500, 300);
		scheduler.preview(1500, 300);
		assertEquals(List.of("", "سلام", "", "سلام"), delivered);
	}

	private static Cancellable failScheduling() {
		fail("A paused preview must not schedule playback work");
		return null;
	}

	@Test
	public void testScheduler() throws Exception {
		var pending = new java.util.PriorityQueue<Scheduled>(
				java.util.Comparator.comparingLong(Scheduled::time));
		var clock = new java.util.concurrent.atomic.AtomicLong();
		HandlerExecutor executor = new HandlerExecutor() {
			@Override
			public Cancellable schedule(Runnable task, long delay) {
				var scheduled = new Scheduled(clock.get() + delay, task);
				pending.add(scheduled);
				return () -> pending.remove(scheduled);
			}
		};
		var grid = new SubGrid(b -> {
			b.add("one", 1000, 200, SubGrid.Position.BOTTOM_CENTER);
			b.add("two", 2000, 200, SubGrid.Position.BOTTOM_CENTER);
		});
		var events = new ArrayList<String>();
		var scheduler = new SubScheduler(executor, grid, (position, text) -> {
			if (text != null) events.add(text.getText() + "@" + clock.get());
		}, clock::get);
		scheduler.start(0, 200, 2f);
		while (!pending.isEmpty()) {
			var task = pending.remove();
			clock.set(task.time());
			task.task().run();
		}
		assertEquals(List.of("one@600", "two@1100"), events);
		scheduler.stop(true);
		events.clear();
		scheduler.start(2100, 0, 1f);
		var task = pending.remove();
		task.task().run();
		assertEquals(List.of("two@" + clock.get()), events);
		scheduler.stop(false);
		assertTrue(pending.isEmpty());
	}

	private record Scheduled(long time, Runnable task) {}
}
