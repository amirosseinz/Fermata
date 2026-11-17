package me.aap.fermata.addon;

import static java.util.Collections.emptyList;
import static me.aap.utils.async.Completed.completed;
import static me.aap.utils.ui.UiUtils.showInfo;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.media.sub.Subtitles;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.collection.CacheMap;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PrefCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.UiUtils;

@Keep
public class SubGenAddon implements FermataAddon {
	public static final Pref<BooleanSupplier> ENABLED = Pref.b("SG_ENABLED", false);
	public static final Pref<Supplier<String>> IMPL = Pref.s("SG_IMPL", "whisper");
	public static final Pref<Supplier<String>> LANG = Pref.s("SG_LANG", "auto");
	public static final Pref<IntSupplier> BUF_LEN = Pref.i("SG_BUF_LEN", 5);
	public static final Pref<IntSupplier> CHUNK_LEN = Pref.i("SG_CHUNK_LEN", 5);
	public static final PreferenceStore.Pref<BooleanSupplier> TRANSLATE =
			PreferenceStore.Pref.b("SG_TRANSLATE", false);
	public static final Pref<Supplier<String>> TRANSLATE_LANG = Pref.s("SG_TRANSLATE_LANG",
			() -> Locale.getDefault().getLanguage());
	private static final AddonInfo info = FermataAddon.findAddonInfo(SubGenAddon.class.getName());
	private final CacheMap<Object, Transcriptor> cache = new CacheMap<>(30);

	@Override
	public int getAddonId() {
		return R.id.subgen_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@Override
	public final void contributeSettings(Context ctx, PreferenceStore ps, PreferenceSet set,
																			 ChangeableCondition visibility) {
		contributeSettings(ctx, ps, set, visibility, true);
	}

	public void contributeSettings(Context ctx, PreferenceStore ps, PreferenceSet set,
																 ChangeableCondition visibility, boolean isGlobalSettings) {
		var mps = isGlobalSettings ?
				MainActivityDelegate.get(ctx).getMediaServiceBinder().getLib().getPrefs() : ps;

		if (getClass() == SubGenAddon.class) {
			var impl = getImpl().peek();
			if (impl != null) {
				impl.contributeSettings(ctx, mps, set, visibility, isGlobalSettings);
				return;
			}
		}

		set.addBooleanPref(o -> {
			o.store = mps;
			o.pref = TRANSLATE;
			o.title = me.aap.fermata.R.string.sub_gen_translate;
			o.visibility = visibility.copy();
		});
		set.addListPref(o -> {
			o.setStringValues(mps, LANG, getSupportedLanguages());
			o.title = R.string.lang;
			o.subtitle = R.string.string_format;
			o.formatSubtitle = true;
			o.visibility = visibility.copy();
		});
		TranslateAddon.get().onSuccess(a -> {
			a.contributeSettings(ctx, mps, set,
					visibility.copy().and(PrefCondition.create(mps, TRANSLATE)));
			set.addListPref(o -> {
				var srcLang = mps.getStringPref(LANG);
				var langs = a.getSupportedLanguages(mps, "auto".equals(srcLang) ? null : srcLang);
				o.setStringValues(mps, TRANSLATE_LANG, langs);
				o.title = R.string.trans_lang;
				o.subtitle = R.string.string_format;
				o.formatSubtitle = true;
				o.visibility = visibility.copy().and(PrefCondition.create(mps, TRANSLATE));
			});
		});
		set.addIntPref(o -> {
			o.store = mps;
			o.pref = BUF_LEN;
			o.title = R.string.sub_gen_buf;
			o.subtitle = R.string.sub_gen_buf_sub;
			o.seekMin = 5;
			o.seekMax = 30;
			o.seekScale = 5;
			o.visibility = visibility.copy();
		});
		set.addIntPref(o -> {
			o.store = mps;
			o.pref = CHUNK_LEN;
			o.title = R.string.sub_gen_chunk;
			o.subtitle = R.string.sub_gen_chunk_sub;
			o.seekMin = 5;
			o.seekMax = 20;
			o.seekScale = 5;
			o.visibility = visibility.copy();
		});
		set.addButton(o -> {
			o.title = me.aap.fermata.R.string.sub_gen_cleanup;
			o.onClick = () -> {
				var tr = TranslateAddon.get();
				var deleted = new ArrayList<>(cleanUp(ctx, mps));
				var a = tr.peek();
				if (a != null) {
					deleted.addAll(
							a.cleanUp(mps, mps.getStringPref(LANG), mps.getStringPref(TRANSLATE_LANG)));
				}
				showInfo(ctx, ctx.getString(me.aap.fermata.R.string.sub_gen_cleanup_done, deleted));
			};
			o.visibility = visibility.copy();
		});
	}

	@Override
	public void install() {
		if (getClass() != SubGenAddon.class) return;
		var ps = FermataApplication.get().getPreferenceStore();
		ps.applyBooleanPref(getImplInfo().enabledPref, true);
	}

	@Override
	public void uninstall() {
		FermataApplication.get().getPreferenceStore()
				.applyBooleanPref(getImplInfo().enabledPref, false);
	}

	public static void manageModels(Context ctx, PreferenceStore prefs, Runnable onSelected) {
		var app = FermataApplication.get();
		var module = app.getPreferenceStore().getStringPref(IMPL);
		app.getAddonManager().getOrInstallAddon(module).main().onSuccess(addon -> {
			if (addon instanceof SubGenAddon impl) impl.showModels(ctx, prefs, onSelected);
		}).onFailure(err -> UiUtils.showAlert(ctx, R.string.sub_gen_error, err.getLocalizedMessage()));
	}

	protected void showModels(Context ctx, PreferenceStore prefs, Runnable onSelected) {
		UiUtils.showAlert(ctx, R.string.sub_gen_error, "Model manager is unavailable");
	}

	public Collection<String> cleanUp(Context ctx, PreferenceStore ps) {
		var impl = getImpl().peek();
		return impl != null ? impl.doCleanUp(ctx, ps) : emptyList();
	}

	protected Collection<String> doCleanUp(Context ctx, PreferenceStore ps) {
		return emptyList();
	}

	public FutureSupplier<Transcriptor> getTranscriptor(PreferenceStore ps) {
		var getImpl = getImpl();
		var impl = getImpl.peek();
		if (impl != null) {
			var key = impl.createCacheKey(ps);
			var cached = cache.remove(key);
			if (cached != null) {
				if (cached.reconfigure(ps)) {
					Log.d("Returning cached transcriptor: ", key);
					return completed(new CachedTranscriptor(key, cached));
				} else {
					cached.release();
				}
			}
		}

		var t = getImpl.then(
				i -> i.getTranscriptor(ps)
						.map(r -> (Transcriptor) new CachedTranscriptor(i.createCacheKey(ps), r)));
		t.main().onFailure(err -> {
			if (me.aap.utils.function.ResultConsumer.Cancel.isCancellation(err)) return;
			Log.e(err, "Automatic subtitle initialization failed");
			var ctx = App.get();
			android.widget.Toast.makeText(ctx, ctx.getString(R.string.sub_gen_error,
					err.getLocalizedMessage()), android.widget.Toast.LENGTH_LONG).show();
		});
		return t;
	}

	protected Object createCacheKey(PreferenceStore ps) {
		return ps.getStringPref(IMPL) + "|" + ps.getStringPref(LANG) + "|" +
				ps.getIntPref(BUF_LEN) + "|" + ps.getIntPref(CHUNK_LEN);
	}

	protected List<Pair<String, String>> getSupportedLanguages() {
		return Collections.singletonList(
				new Pair<>("auto", FermataApplication.get().getString(R.string.auto)));
	}

	private AddonInfo getImplInfo() {
		var app = FermataApplication.get();
		return FermataAddon.findAddonInfo(app.getPreferenceStore().getStringPref(IMPL));
	}

	private FutureSupplier<SubGenAddon> getImpl() {
		var app = FermataApplication.get();
		return app.getAddonManager().getOrInstallAddon(app.getPreferenceStore()
				.getStringPref(IMPL)).cast();
	}

	public interface Transcriptor {
		boolean reconfigure(PreferenceStore ps);

		boolean read(ByteBuffer buf, int chunkLen, int bytesPerSample, int channels, int frameRate);

		default List<Subtitles.Text> transcribe() {return transcribe(0);}

		List<Subtitles.Text> transcribe(long timeOffset);

		String getLang();

		void reset();

		void release();
	}

	private class CachedTranscriptor implements Transcriptor {
		private final Object key;
		private Transcriptor transcriptor;

		CachedTranscriptor(Object key, Transcriptor transcriptor) {
			this.key = key;
			this.transcriptor = transcriptor;
		}

		@Override
		public synchronized boolean reconfigure(PreferenceStore ps) {
			var impl = getImpl().peek();
			if (impl == null) return false;
			return key.equals(impl.createCacheKey(ps)) && get().reconfigure(ps);
		}

		@Override
		public synchronized boolean read(ByteBuffer buf, int chunkLen, int bytesPerSample,
																		 int channels,
																		 int frameRate) {
			return get().read(buf, chunkLen, bytesPerSample, channels, frameRate);
		}

		@Override
		public synchronized List<Subtitles.Text> transcribe(long timeOffset) {
			return get().transcribe(timeOffset);
		}

		@Override
		public String getLang() {
			return get().getLang();
		}

		@Override
		public synchronized void reset() {
			get().reset();
		}

		@Override
		public synchronized void release() {
			var t = get();
			transcriptor = null;
			if (cache.putIfAbsent(key, t) == null) {
				Log.d("Transcriptor cached: ", key);
			} else {
				Log.d("Releasing transcriptor: ", key);
				t.release();
			}
		}

		private Transcriptor get() {
			if (transcriptor == null) throw new IllegalStateException("Transcriptor released!");
			return transcriptor;
		}
	}
}
