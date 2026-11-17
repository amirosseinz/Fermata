package me.aap.fermata.engine.exoplayer;

import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.async.Completed.completedNull;

import androidx.annotation.NonNull;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.SubGenAddon;
import me.aap.utils.async.FutureSupplier;

@UnstableApi
class PendingLoadAudioProcessor implements AudioProcessor {
	private final ExoPlayerEngine.Accessor player;
	private final AtomicInteger state = new AtomicInteger();
	private AudioFormat pendingConfiguration;
	private StreamMetadata streamMetadata = StreamMetadata.DEFAULT;
	private boolean enabled;
	private ByteBuffer fallbackOutput = EMPTY_BUFFER;
	private boolean fallbackEnded;
	private volatile FutureSupplier<AudioTranscriptProcessor> delegate = completedNull();

	PendingLoadAudioProcessor(ExoPlayerEngine.Accessor player) {
		this.player = player;
	}

	@Override
	public long getDurationAfterProcessorApplied(long durationUs) {
		var d = delegate.peek();
		return (d != null) ? d.getDurationAfterProcessorApplied(durationUs) : durationUs;
	}

	@NonNull
	@Override
	public AudioFormat configure(@NonNull AudioFormat inputAudioFormat)
			throws UnhandledAudioFormatException {
		pendingConfiguration = null;
		enabled = false;
		var source = player.getSource();
		if (source == null) {
			releaseDelegate();
			return inputAudioFormat;
		}

		var ps = source.getPrefs();
		enabled = ps.getBooleanPref(SubGenAddon.ENABLED);
		if (enabled) {
			var atp = delegate.peek();
			if (atp != null) {
				if (!atp.reconfigure(ps)) {
					atp.release();
					createDelegate();
				}
			} else if (delegate.isDone()) {
				createDelegate();
			}
		} else {
			releaseDelegate();
		}

		var d = delegate.peek();
		if (d == null) {
			return pendingConfiguration = inputAudioFormat;
		} else {
			pendingConfiguration = null;
			return d.configure(inputAudioFormat);
		}
	}

	@Override
	public boolean isActive() {
		return enabled;
	}

	@Override
	public void queueInput(@NonNull ByteBuffer inputBuffer) {
		var d = get();

		if (d != null) {
			d.queueInput(inputBuffer);
		} else if (delegate.isDone() && !fallbackOutput.hasRemaining()) {
			fallbackOutput = ByteBuffer.allocateDirect(inputBuffer.remaining())
					.order(java.nio.ByteOrder.nativeOrder());
			fallbackOutput.put(inputBuffer).flip();
		}
	}

	@Override
	public void queueEndOfStream() {
		var d = get();
		if (d != null) d.queueEndOfStream();
		else fallbackEnded = true;
	}

	@NonNull
	@Override
	public ByteBuffer getOutput() {
		var d = get();
		if (d != null) return d.getOutput();
		var output = fallbackOutput;
		fallbackOutput = EMPTY_BUFFER;
		return output;
	}

	@Override
	public boolean isEnded() {
		var d = get();
		return d != null ? d.isEnded() : fallbackEnded && !fallbackOutput.hasRemaining();
	}

	@Override
	public void flush(StreamMetadata metadata) {
		streamMetadata = metadata;
		fallbackOutput = EMPTY_BUFFER;
		fallbackEnded = false;
		var d = get();
		if (d != null) d.flush(metadata);
	}

	@Override
	public void reset() {
		enabled = false;
		streamMetadata = StreamMetadata.DEFAULT;
		pendingConfiguration = null;
		fallbackOutput = EMPTY_BUFFER;
		fallbackEnded = false;
		state.incrementAndGet();
		var d = delegate.peek();
		delegate = completedNull();
		if (d != null) {
			d.reset();
			d.release();
		}
	}

	private void createDelegate() {
		var st = state.incrementAndGet();
		var app = FermataApplication.get();
		delegate = me.aap.utils.async.Completed.completedVoid().main()
				.then(unused -> app.getAddonManager().getOrInstallAddon(SubGenAddon.class))
				.then(a -> {
					if (a == null || state.get() != st) return completedNull();
					var pi = player.getSource();
					if (pi == null || !pi.getPrefs().getBooleanPref(SubGenAddon.ENABLED))
						return completedNull();
					return a.getTranscriptor(pi.getPrefs());
				}).then(t -> {
					if (t == null) return completedNull();
					var pi = player.getSource();
					if (pi == null || state.get() != st ||
							!pi.getPrefs().getBooleanPref(SubGenAddon.ENABLED)) {
						t.release();
						return completedNull();
					}
					if (!t.reconfigure(pi.getPrefs())) {
						t.release();
						return completedNull();
					}
					var ps = pi.getPrefs();
					return completed(new AudioTranscriptProcessor(player, t,
							ps.getIntPref(SubGenAddon.BUF_LEN), ps.getIntPref(SubGenAddon.CHUNK_LEN)));
				});
	}

	private void releaseDelegate() {
		state.incrementAndGet();
		var atp = delegate.peek();
		delegate = completedNull();
		if (atp != null) atp.release();
	}

	private AudioTranscriptProcessor get() {
		var atp = delegate.peek();
		if (atp == null) return null;
		if (pendingConfiguration != null) {
			try {
				atp.configure(pendingConfiguration);
				atp.flush(streamMetadata);
				if (fallbackEnded) atp.queueEndOfStream();
			} catch (UnhandledAudioFormatException err) {
				throw new RuntimeException(err);
			}
			pendingConfiguration = null;
		}
		return atp;
	}
}
