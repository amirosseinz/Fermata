package me.aap.fermata.engine.exoplayer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaybackGainProcessorTest {
	@Test
	public void gainPreservesUnityAndScalesQuietSamples() {
		assertEquals(500, PlaybackGainProcessor.scale((short) 500, 100));
		assertEquals(1000, PlaybackGainProcessor.scale((short) 500, 200));
		assertEquals(-750, PlaybackGainProcessor.scale((short) -500, 150));
		assertEquals(0, PlaybackGainProcessor.scale((short) -500, 0));
	}

	@Test
	public void gainClampsInsteadOfWrappingPcm() {
		assertEquals(Short.MAX_VALUE, PlaybackGainProcessor.scale((short) 20000, 200));
		assertEquals(Short.MIN_VALUE, PlaybackGainProcessor.scale((short) -20000, 200));
	}
}
