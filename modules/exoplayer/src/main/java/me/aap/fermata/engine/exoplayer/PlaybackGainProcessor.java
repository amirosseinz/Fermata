package me.aap.fermata.engine.exoplayer;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@UnstableApi
final class PlaybackGainProcessor extends BaseAudioProcessor {
	private volatile int percent = 100;

	void setPercent(int percent) {
		this.percent = Math.max(0, Math.min(200, percent));
	}

	@Override
	protected AudioFormat onConfigure(AudioFormat inputFormat) {
		return inputFormat.encoding == C.ENCODING_PCM_16BIT ? inputFormat : AudioFormat.NOT_SET;
	}

	@Override
	public void queueInput(ByteBuffer input) {
		var output = replaceOutputBuffer(input.remaining());
		int gain = percent;
		if (gain == 100) {
			output.put(input);
		} else {
			input.order(ByteOrder.nativeOrder());
			while (input.remaining() >= Short.BYTES) {
				output.putShort(scale(input.getShort(), gain));
			}
			output.put(input);
		}
		output.flip();
	}

	static short scale(short sample, int percent) {
		int boosted = sample * percent / 100;
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, boosted));
	}
}
