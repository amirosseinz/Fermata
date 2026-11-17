package me.aap.fermata.whisper;

import static java.util.Collections.emptyList;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import me.aap.fermata.addon.SubGenAddon;
import me.aap.fermata.media.sub.Subtitles;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.io.FileUtils;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;

public final class Whisper implements SubGenAddon.Transcriptor {
	private static final String VAD_FILE_NAME = "ggml-silero-v6.2.0.bin";
	private static final class Native {
		static { System.loadLibrary("whisper_jni"); }

		static void ensureLoaded() {}
	}

	private final long sessionPtr;
	private boolean released = false;


	private Whisper(long sessionPtr) {
		this.sessionPtr = sessionPtr;
		Log.d("Session created: ", sessionPtr);
	}

	static void ensureNativeAvailable() throws IOException {
		try {
			Native.ensureLoaded();
		} catch (LinkageError err) {
			throw new IOException("Whisper JNI could not load for this device", err);
		}
	}

	public static FutureSupplier<Whisper> create(PreferenceStore ps) {
		var modelNameOrPath = ps.getStringPref(WhisperAddon.MODEL);
		var lang = ps.getStringPref(WhisperAddon.LANG);
		var useGpu = ps.getBooleanPref(WhisperAddon.USE_GPU);
		var app = App.get();
		return app.execute(() -> {
			ensureNativeAvailable();
			var store = new WhisperModelStore(app.getFilesDir());
			var file = modelNameOrPath.contains("/") ? new File(modelNameOrPath) :
					store.require(WhisperModels.find(modelNameOrPath));
			if (!file.isFile()) throw new IOException("Whisper model file is missing: " + file.getName());
			var vad = extractVad(app, store.directory());
			var power = app.getSystemService(PowerManager.class);
			boolean powerSave = power != null && power.isPowerSaveMode();
			var manager = app.getSystemService(ActivityManager.class);
			var memory = new ActivityManager.MemoryInfo();
			if (manager != null) manager.getMemoryInfo(memory);
			int vulkan12 = (1 << 22) | (2 << 12);
			var vulkan = app.getPackageManager().hasSystemFeature(
					PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, vulkan12);
			boolean tryGpu = !memory.lowMemory && WhisperHardware.tryVulkan(
					useGpu, vulkan, powerSave, memory.availMem, file.length());
			int threads = WhisperHardware.cpuThreads(Runtime.getRuntime().availableProcessors(), powerSave);
			return new Whisper(create(file.getAbsolutePath(), vad.getAbsolutePath(),
					modelLanguage(modelNameOrPath, lang), tryGpu, threads));
		});
	}

	private static synchronized File extractVad(Context ctx, File directory) throws IOException {
		var vad = new File(directory, VAD_FILE_NAME);
		if (vad.isFile() && vad.length() > 0) return vad;
		if (!directory.isDirectory() && !directory.mkdirs())
			throw new IOException("Could not create Whisper model directory");
		var tmp = File.createTempFile("vad-", ".part", directory);
		try {
			try (var in = ctx.getAssets().open(VAD_FILE_NAME); var out = new FileOutputStream(tmp)) {
				FileUtils.copy(in, out);
			}
			if (!tmp.renameTo(vad)) throw new IOException("Could not install voice activity model");
		} finally {
			if (tmp.exists()) tmp.delete();
		}
		return vad;
	}

	private static String modelLanguage(String model, String language) {
		if (model.contains("/")) return language;
		var fixed = WhisperModels.find(model).fixedLanguage();
		return fixed == null ? language : fixed;
	}

	@Override
	public boolean reconfigure(PreferenceStore ps) {
		reconfigure(sessionPtr, modelLanguage(ps.getStringPref(WhisperAddon.MODEL),
				ps.getStringPref(WhisperAddon.LANG)));
		return true;
	}

	@Override
	public boolean read(ByteBuffer buf, int chunkLen, int bytesPerSample, int channels,
											int frameRate) {
		assert buf.isDirect();
		var slice = buf.position() == 0 && buf.limit() == buf.capacity() ? buf : buf.slice();
		int consumed = resample(sessionPtr, slice, chunkLen, bytesPerSample, channels, frameRate);
		buf.position(buf.position() + Math.abs(consumed));
		return consumed < 0;
	}

	@Override
	public List<Subtitles.Text> transcribe(long timeOffset) {
		Log.d("Transcribing...");
		int segments = fullTranscribe(sessionPtr);
		Log.d("Number of segments: ", segments);
		if (segments == 0) return emptyList();

		var subtitles = new ArrayList<Subtitles.Text>(segments);
		for (int i = 0; i < segments; i++) {
			String text = new String(text(sessionPtr, i), java.nio.charset.StandardCharsets.UTF_8).trim();
			if (text.isEmpty()) continue;
			long start = timeOffset + start(sessionPtr, i);
			long duration = timeOffset + end(sessionPtr, i) - start;
			if (duration > 0 && duration <= Integer.MAX_VALUE)
				subtitles.add(new Subtitles.Text(text, start, (int) duration));
		}
		return subtitles;
	}


	@Override
	public String getLang() {
		return lang(sessionPtr);
	}

	public void reset() {
		reset(sessionPtr);
	}

	@Override
	public synchronized void release() {
		if (!released) {
			released = true;
			release(sessionPtr);
			Log.d("Session released: ", sessionPtr);
		}
	}

	@Override
	protected void finalize() {
		release();
	}

	static Collection<String> cleanUp(Context ctx, PreferenceStore ps) {
		var store = new WhisperModelStore(ctx.getFilesDir());
		var selected = ps.getStringPref(WhisperAddon.MODEL);
		var deleted = new ArrayList<String>();
		for (var model : WhisperModels.MODELS) {
			if (model.id().equals(selected) || !store.installed(model)) continue;
			try {
				store.delete(model);
				deleted.add(model.id());
			} catch (IOException err) {
				Log.w(err, "Could not delete model ", model.id());
			}
		}
		return deleted;
	}

	private static native long create(String modelPath, String vadPath, String lang,
																		boolean useGpu, int threads);

	private static native void reconfigure(long sessionPtr, String lang);

	private static native int resample(long sessionPtr, ByteBuffer buf, int chunkLen,
																		 int bytesPerSample, int channels, int frameRate);

	private static native int fullTranscribe(long sessionPtr);

	private static native byte[] text(long sessionPtr, int segmentIdx);

	private static native long start(long sessionPtr, int segmentIdx);

	private static native long end(long sessionPtr, int segmentIdx);

	private static native String lang(long sessionPtr);

	private static native void reset(long sessionPtr);

	private static native void release(long sessionPtr);
}

