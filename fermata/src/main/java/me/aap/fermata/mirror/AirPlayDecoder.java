package me.aap.fermata.mirror;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;

final class AirPlayDecoder implements AutoCloseable {
	private final Surface surface;
	private final Runnable rendered;
	private final MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
	private MediaCodec codec;
	private int width;
	private int height;
	private boolean signalled;

	AirPlayDecoder(Surface surface, Runnable rendered) {
		this.surface = surface;
		this.rendered = rendered;
	}

	synchronized void dimensions(int width, int height) {
		if (width == this.width && height == this.height) return;
		close();
		this.width = width;
		this.height = height;
	}

	synchronized void video(byte[] data, long timestampUs) {
		if (width == 0 || height == 0 || !surface.isValid()) return;
		try {
			if (codec == null) {
				if (!initialNal(data)) return;
				var format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
				format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
				codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
				codec.configure(format, surface, null, 0);
				codec.start();
			}
			for (int attempt = 0; attempt < 4; attempt++) {
				drain(0);
				int index = codec.dequeueInputBuffer(4_000);
				if (index < 0) continue;
				var input = codec.getInputBuffer(index);
				if (input == null || input.capacity() < data.length) {
					close();
					return;
				}
				input.clear();
				input.put(data);
				codec.queueInputBuffer(index, 0, data.length, Math.max(timestampUs, 0), 0);
				drain(4_000);
				return;
			}
			close();
		} catch (IOException | RuntimeException e) {
			Log.w("FermataAirPlay", "Decoder reset", e);
			close();
		}
	}

	private void drain(long timeoutUs) {
		for (int i = codec.dequeueOutputBuffer(output, timeoutUs);
				i != MediaCodec.INFO_TRY_AGAIN_LATER; i = codec.dequeueOutputBuffer(output, 0)) {
			if (i < 0) continue;
			boolean picture = output.size > 0 && (output.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
			codec.releaseOutputBuffer(i, picture);
			if (picture && !signalled) {
				signalled = true;
				rendered.run();
			}
		}
	}

	static boolean initialNal(byte[] data) {
		for (int i = 2; i + 1 < data.length; i++) {
			if (data[i] != 1 || data[i - 1] != 0 || data[i - 2] != 0) continue;
			int type = data[i + 1] & 31;
			if (type == 5 || type == 7) return true;
		}
		return false;
	}

	@Override
	public synchronized void close() {
		signalled = false;
		if (codec != null) {
			var decoder = codec;
			codec = null;
			try {
				decoder.release();
			} catch (RuntimeException e) {
				Log.w("FermataAirPlay", "Could not release decoder", e);
			}
		}
	}
}
