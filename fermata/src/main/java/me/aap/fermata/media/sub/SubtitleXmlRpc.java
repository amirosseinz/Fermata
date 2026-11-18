package me.aap.fermata.media.sub;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The small XML-RPC subset used by OpenSubtitles. No DTDs or external entities are accepted. */
final class SubtitleXmlRpc {
	private SubtitleXmlRpc() {
	}

	static byte[] request(String method, Object... parameters) throws IOException {
		var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><methodCall><methodName>");
		xml.append(escape(method)).append("</methodName><params>");
		for (var parameter : parameters) {
			xml.append("<param>");
			writeValue(xml, parameter);
			xml.append("</param>");
		}
		return xml.append("</params></methodCall>").toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void writeValue(StringBuilder xml, Object value) throws IOException {
		xml.append("<value>");
		if (value instanceof Map<?, ?> map) {
			xml.append("<struct>");
			for (var entry : map.entrySet()) {
				xml.append("<member><name>").append(escape(entry.getKey().toString())).append("</name>");
				writeValue(xml, entry.getValue());
				xml.append("</member>");
			}
			xml.append("</struct>");
		} else if (value instanceof List<?> list) {
			xml.append("<array><data>");
			for (var item : list) writeValue(xml, item);
			xml.append("</data></array>");
		} else if (value instanceof Number) {
			xml.append("<int>").append(value).append("</int>");
		} else if (value instanceof String text) {
			xml.append("<string>").append(escape(text)).append("</string>");
		} else {
			throw new IOException("Unsupported subtitle request value.");
		}
		xml.append("</value>");
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&apos;");
	}

	static Map<String, Object> response(byte[] bytes) throws IOException {
		String xml = new String(bytes, StandardCharsets.UTF_8);
		if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
			throw new IOException("Subtitle service returned an unsafe XML declaration.");
		try {
			var factory = XmlPullParserFactory.newInstance();
			factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
			var parser = factory.newPullParser();
			parser.setInput(new StringReader(xml));
			parser.nextTag();
			parser.require(XmlPullParser.START_TAG, null, "methodResponse");
			parser.nextTag();
			boolean fault = "fault".equals(parser.getName());
			if (!fault) {
				parser.require(XmlPullParser.START_TAG, null, "params");
				parser.nextTag();
				parser.require(XmlPullParser.START_TAG, null, "param");
			}
			parser.nextTag();
			Object value = readValue(parser, 0);
			parser.nextTag();
			parser.require(XmlPullParser.END_TAG, null, fault ? "fault" : "param");
			if (!fault) {
				parser.nextTag();
				parser.require(XmlPullParser.END_TAG, null, "params");
			}
			parser.nextTag();
			parser.require(XmlPullParser.END_TAG, null, "methodResponse");
			if (!(value instanceof Map<?, ?> map)) throw new IOException("Invalid subtitle service response.");
			var result = new LinkedHashMap<String, Object>();
			for (var entry : map.entrySet()) result.put(entry.getKey().toString(), entry.getValue());
			if (fault) throw new IOException("OpenSubtitles: " + result.getOrDefault("faultString", "service error"));
			return result;
		} catch (XmlPullParserException | NumberFormatException error) {
			throw new IOException("OpenSubtitles returned malformed XML.", error);
		}
	}

	private static Object readValue(XmlPullParser parser, int depth)
			throws IOException, XmlPullParserException {
		if (depth > 12) throw new IOException("Subtitle service response is nested too deeply.");
		parser.require(XmlPullParser.START_TAG, null, "value");
		int event = parser.next();
		// XML-RPC also allows an untyped string directly inside <value>.
		String text = event == XmlPullParser.TEXT ? parser.getText() : "";
		if (event == XmlPullParser.TEXT) event = parser.nextTag();
		if (event == XmlPullParser.END_TAG) {
			parser.require(XmlPullParser.END_TAG, null, "value");
			return text;
		}
		Object result;
		String type = parser.getName();
		switch (type) {
			case "struct" -> {
				var members = new LinkedHashMap<String, Object>();
				while (parser.nextTag() == XmlPullParser.START_TAG) {
					parser.require(XmlPullParser.START_TAG, null, "member");
					parser.nextTag();
					parser.require(XmlPullParser.START_TAG, null, "name");
					String name = parser.nextText();
					parser.nextTag();
					members.put(name, readValue(parser, depth + 1));
					parser.nextTag();
					parser.require(XmlPullParser.END_TAG, null, "member");
				}
				parser.require(XmlPullParser.END_TAG, null, "struct");
				result = members;
			}
			case "array" -> {
				parser.nextTag();
				parser.require(XmlPullParser.START_TAG, null, "data");
				var items = new ArrayList<>();
				while (parser.nextTag() == XmlPullParser.START_TAG) items.add(readValue(parser, depth + 1));
				parser.require(XmlPullParser.END_TAG, null, "data");
				parser.nextTag();
				parser.require(XmlPullParser.END_TAG, null, "array");
				result = items;
			}
			case "string", "base64", "dateTime.iso8601" -> result = parser.nextText();
			case "int", "i4", "i8" -> result = Long.parseLong(parser.nextText());
			case "double" -> result = Double.parseDouble(parser.nextText());
			case "boolean" -> result = "1".equals(parser.nextText());
			default -> throw new IOException("Unknown subtitle response type: " + type);
		}
		parser.nextTag();
		parser.require(XmlPullParser.END_TAG, null, "value");
		return result;
	}
}
