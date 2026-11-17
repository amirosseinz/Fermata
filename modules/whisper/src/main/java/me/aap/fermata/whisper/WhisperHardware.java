package me.aap.fermata.whisper;

final class WhisperHardware {
	private static final long MIB = 1024L * 1024L;

	static int cpuThreads(int cores, boolean powerSave) {
		return Math.max(1, Math.min(powerSave ? 2 : 4, cores - 2));
	}

	static boolean tryVulkan(boolean enabled, boolean supported, boolean powerSave,
			long availableMemory, long modelBytes) {
		// Small models can run on CPU without the cost of preparing Vulkan pipelines.
		return enabled && supported && !powerSave && modelBytes >= 128 * MIB &&
				availableMemory >= Math.max(1024 * MIB, modelBytes + 512 * MIB);
	}

	private WhisperHardware() {}
}
