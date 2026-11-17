package me.aap.fermata.mirror;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AirPlayDecoderTest {
	@Test
	public void acceptsParameterSetsAndIdrWithBothAnnexBStartCodes() {
		assertTrue(AirPlayDecoder.initialNal(new byte[] {0, 0, 0, 1, 0x67, 0}));
		assertTrue(AirPlayDecoder.initialNal(new byte[] {0, 0, 1, 0x65}));
		assertTrue(AirPlayDecoder.initialNal(new byte[] {0, 0, 1, 9, 0, 0, 0, 1, 0x65}));
	}

	@Test
	public void ignoresPFramesAndTruncatedStartCodesPriorToInitialization() {
		assertFalse(AirPlayDecoder.initialNal(new byte[] {0, 0, 1, 0x41}));
		assertFalse(AirPlayDecoder.initialNal(new byte[] {0, 0, 0, 1}));
		assertFalse(AirPlayDecoder.initialNal(new byte[] {0, 0, 3, 0x67}));
	}
}
