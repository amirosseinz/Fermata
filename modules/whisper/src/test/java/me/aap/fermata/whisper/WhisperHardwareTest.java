package me.aap.fermata.whisper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WhisperHardwareTest {
	private static final long MIB = 1024L * 1024L;

	@Test
	public void cpuLeavesCoresForPlaybackAndUsesFewerInBatterySaver() {
		assertEquals(1, WhisperHardware.cpuThreads(2, false));
		assertEquals(2, WhisperHardware.cpuThreads(4, false));
		assertEquals(4, WhisperHardware.cpuThreads(8, false));
		assertEquals(2, WhisperHardware.cpuThreads(8, true));
	}

	@Test
	public void gpuRequiresVulkanMemoryAndSufficientModelSize() {
		long memory = 2048 * MIB;
		long model = 450 * MIB;
		assertTrue(WhisperHardware.tryVulkan(true, true, false, memory, model));
		assertFalse(WhisperHardware.tryVulkan(false, true, false, memory, model));
		assertFalse(WhisperHardware.tryVulkan(true, false, false, memory, model));
		assertFalse(WhisperHardware.tryVulkan(true, true, true, memory, model));
		assertFalse(WhisperHardware.tryVulkan(true, true, false, 768 * MIB, model));
		assertFalse(WhisperHardware.tryVulkan(true, true, false, memory, 59 * MIB));
		assertFalse(WhisperHardware.tryVulkan(true, true, false, memory, 1800 * MIB));
	}
}
