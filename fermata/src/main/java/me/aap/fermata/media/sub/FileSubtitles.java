package me.aap.fermata.media.sub;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.aap.utils.vfs.VirtualFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;

/**
 * @author Andrey Pavlenko
 */
public class FileSubtitles {
	private static final int MAX_BYTES = 16 * 1024 * 1024;
	private static final String TIMESTAMP = "(?:(\\d{2,6}):)?([0-5]\\d):([0-5]\\d)[.,](\\d{3})";
	private static final Pattern TIMING =
			Pattern.compile(TIMESTAMP + "\\s+-->\\s+" + TIMESTAMP + "(?:\\s+.*)?");
	public enum Type { SRT, VTT }

	public static boolean isSupported(String fileName) {
		return getType(fileName) != null;
	}

	@Nullable
	public static Type getType(String fileName) {
		var name = fileName.toLowerCase(Locale.ROOT);
		if (name.endsWith(".srt")) return Type.SRT;
		if (name.endsWith(".vtt")) return Type.VTT;
		return null;
	}

	public static boolean isSidecar(String baseName, String fileName) {
		if (!isSupported(fileName)) return false;
		var stem = fileName.substring(0, fileName.length() - 4);
		return stem.equalsIgnoreCase(baseName) ||
				(stem.length() > baseName.length() + 1 &&
						stem.regionMatches(true, 0, baseName + '.', 0, baseName.length() + 1));
	}

	public static SubGrid load(VirtualFile file) throws IOException {
		if (!isSupported(file.getName())) throw new IOException("Unsupported subtitle file");
		return load(file.getInputStream().asInputStream());
	}

	public static SubGrid load(InputStream in) throws IOException {
		return load(in, Charset.forName("windows-1252"));
	}

	public static SubGrid load(InputStream in, Charset legacyEncoding) throws IOException {
		try (in; var r = new BufferedReader(new StringReader(decode(in, legacyEncoding)))) {
			var grid = new SubGrid.Builder();
			var cue = new StringBuilder();
			long start = -1;
			int duration = 0;
			boolean skip = false;

			for (String line; (line = r.readLine()) != null; ) {
				if (line.isBlank()) {
					if (start >= 0) addCue(grid, cue, start, duration);
					start = -1;
					cue.setLength(0);
					skip = false;
				} else if (!skip) {
					if (start >= 0) {
						if (cue.length() != 0) cue.append('\n');
						cue.append(line);
					} else if (line.equals("NOTE") || line.startsWith("NOTE ") ||
							line.startsWith("NOTE\t") || line.equals("STYLE") || line.equals("REGION")) {
						skip = true;
					} else {
						var timing = TIMING.matcher(line.trim());
						if (!timing.matches()) continue;
						long begin = millis(timing, 1);
						long length = millis(timing, 5) - begin;
						if (length <= 0 || length > Integer.MAX_VALUE) {
							skip = true;
						} else {
							start = begin;
							duration = (int) length;
						}
					}
				}
			}
			if (start >= 0) addCue(grid, cue, start, duration);
			return grid.build();
		}
	}

	private static void addCue(SubGrid.Builder grid, StringBuilder cue, long start, int duration) {
		var text = cue.toString().trim();
		var position = SubGrid.Position.BOTTOM_CENTER;
		if (text.length() >= 6 && text.startsWith("{\\an") && text.charAt(5) == '}') {
			int id = text.charAt(4) - '0';
			if (id >= 1 && id <= 9) {
				position = SubGrid.Position.values()[id - 1];
				text = text.substring(6).trim();
			}
		}
		if (!text.isEmpty()) grid.add(text, start, duration, position);
	}

	private static long millis(Matcher timing, int group) {
		var hours = timing.group(group);
		return (hours == null ? 0 : Long.parseLong(hours) * 3600000)
				+ Integer.parseInt(timing.group(group + 1)) * 60000L
				+ Integer.parseInt(timing.group(group + 2)) * 1000L
				+ Integer.parseInt(timing.group(group + 3));
	}

	private static String decode(InputStream in, Charset legacy) throws IOException {
		var bytes = new ByteArrayOutputStream();
		var buffer = new byte[8192];
		for (int count; (count = in.read(buffer)) != -1; ) {
			if (bytes.size() + count > MAX_BYTES) throw new IOException("Subtitle file exceeds 16 MiB");
			bytes.write(buffer, 0, count);
		}
		byte[] data = bytes.toByteArray();
		if (data.length >= 4) {
			int bom = ByteBuffer.wrap(data).getInt();
			if (bom == 0x0000FEFF) return new String(data, 4, data.length - 4, Charset.forName("UTF-32BE"));
			if (bom == 0xFFFE0000) return new String(data, 4, data.length - 4, Charset.forName("UTF-32LE"));
		}
		if (data.length >= 2) {
			int bom = (data[0] & 255) << 8 | (data[1] & 255);
			if (bom == 0xFEFF) return new String(data, 2, data.length - 2, UTF_16BE);
			if (bom == 0xFFFE) return new String(data, 2, data.length - 2, UTF_16LE);
		}
		int offset = data.length >= 3 && data[0] == (byte) 0xEF &&
				data[1] == (byte) 0xBB && data[2] == (byte) 0xBF ? 3 : 0;
		try {
			return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
		} catch (CharacterCodingException err) {
			if (offset != 0) throw err;
			return new String(data, legacy);
		}
	}
}
