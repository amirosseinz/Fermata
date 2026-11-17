package me.aap.fermata.mirror;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

final class ScreenFrames {
	static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
	private static final int MAX_CSD = 1024;
	private static final int FRAME_HEADER = Long.BYTES + Integer.BYTES;

	static byte[] configuration(MediaFormat format) throws IOException {
		var sps = data(format, "csd-0");
		var pps = data(format, "csd-1");
		if (sps.length == 0) throw new IOException("Missing AVC parameter sets");
		return ByteBuffer.allocate(16 + sps.length + pps.length)
				.putInt(format.getInteger(MediaFormat.KEY_WIDTH))
				.putInt(format.getInteger(MediaFormat.KEY_HEIGHT))
				.putInt(sps.length).put(sps).putInt(pps.length).put(pps).array();
	}

	private static byte[] data(MediaFormat format, String key) throws IOException {
		var buffer = format.getByteBuffer(key);
		if (buffer == null) return new byte[0];
		var data = buffer.duplicate();
		if (data.remaining() > MAX_CSD) throw new IOException("Invalid encoder configuration");
		var bytes = new byte[data.remaining()];
		data.get(bytes);
		return bytes;
	}

	static byte[] frame(ByteBuffer input, MediaCodec.BufferInfo info) {
		var buffer = input.duplicate();
		buffer.position(info.offset);
		buffer.limit(info.offset + info.size);
		return ByteBuffer.allocate(FRAME_HEADER + info.size).putLong(info.presentationTimeUs)
				.putInt(info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME).put(buffer).array();
	}

	static void play(ScreenLink link, Surface surface, BiConsumer<Integer, Integer> size)
			throws IOException {
		var header = ByteBuffer.wrap(link.read());
		if (header.remaining() < 16) throw new IOException("Missing video configuration");
		int width = header.getInt();
		int height = header.getInt();
		if (width < 16 || width > 1920 || height < 16 || height > 1920 || width * height > 1920 * 1080)
			throw new IOException("Unsupported screen resolution");
		var format = MediaFormat.createVideoFormat(MIME, width, height);
		for (String name : new String[]{"csd-0", "csd-1"}) {
			if (header.remaining() < 4) throw new IOException("Invalid video configuration");
			int count = header.getInt();
			if (count < 0 || count > MAX_CSD || count > header.remaining())
				throw new IOException("Invalid codec data");
			if (count > 0) {
				byte[] csd = new byte[count];
				header.get(csd);
				format.setByteBuffer(name, ByteBuffer.wrap(csd));
			}
		}
		if (header.hasRemaining() || !format.containsKey("csd-0")) throw new IOException("Missing AVC parameter sets");

		var codec = MediaCodec.createDecoderByType(MIME);
		try {
			codec.configure(format, surface, null, 0);
			codec.start();
			size.accept(width, height);
			var info = new MediaCodec.BufferInfo();
			while (surface.isValid() && !Thread.currentThread().isInterrupted()) {
				var packet = link.read();
				if (packet.length == 0) {
					drain(codec, info, 0);
					continue;
				}
				if (packet.length <= FRAME_HEADER) throw new IOException("Invalid video frame");
				var frame = ByteBuffer.wrap(packet);
				long pts = frame.getLong();
				int flags = frame.getInt();
				int index = -1;
				for (int attempts = 0; attempts < 10 && index < 0; attempts++) {
					drain(codec, info, 0);
					index = codec.dequeueInputBuffer(10_000);
				}
				if (index < 0) throw new IOException("Screen decoder stalled");
				var input = codec.getInputBuffer(index);
				if (input == null || input.capacity() < frame.remaining())
					throw new IOException("Screen frame does not fit decoder");
				int count = frame.remaining();
				input.clear();
				input.put(frame);
				codec.queueInputBuffer(index, 0, count, pts, flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
				drain(codec, info, 4_000);
			}
		} finally {
			codec.release();
		}
	}

	private static void drain(MediaCodec codec, MediaCodec.BufferInfo info, long timeout) {
		for (int index = codec.dequeueOutputBuffer(info, timeout); index != MediaCodec.INFO_TRY_AGAIN_LATER;
				 index = codec.dequeueOutputBuffer(info, 0)) {
			if (index >= 0) codec.releaseOutputBuffer(index, info.size > 0);
		}
	}

	private ScreenFrames() {}
}
