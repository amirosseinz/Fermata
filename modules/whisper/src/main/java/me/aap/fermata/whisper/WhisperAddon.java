package me.aap.fermata.whisper;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ProgressBar;
import android.os.Build;
import android.util.Pair;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.fermata.addon.SubGenAddon;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;

@Keep
@SuppressWarnings("unused")
public class WhisperAddon extends SubGenAddon {
	public static final PreferenceStore.Pref<BooleanSupplier>
			USE_GPU = PreferenceStore.Pref.b("SG_WHISPER_USE_GPU", true);
	public static final PreferenceStore.Pref<Supplier<String>> MODEL =
			PreferenceStore.Pref.s("SG_WHISPER_MODEL", "tiny-q5_1");
	private static final AddonInfo info = FermataAddon.findAddonInfo(WhisperAddon.class.getName());

	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.whisper_addon;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@Override
	public FutureSupplier<Transcriptor> getTranscriptor(PreferenceStore ps) {
		return Whisper.create(ps).cast();
	}

	@Override
	protected Object createCacheKey(PreferenceStore ps) {
		return ps.getStringPref(MODEL) + "|" + ps.getBooleanPref(USE_GPU);
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore ps, PreferenceSet set,
																 ChangeableCondition visibility, boolean isGlobalSettings) {
		super.contributeSettings(ctx, ps, set, visibility, isGlobalSettings);

		set.addBooleanPref(o -> {
			o.store = ps;
			o.pref = USE_GPU;
			o.title = me.aap.fermata.R.string.sub_gen_use_gpu;
			o.visibility = visibility.copy();
		});
	}

	@Override
	protected void showModels(Context ctx, PreferenceStore prefs, Runnable onSelected) {
		try {
			Whisper.ensureNativeAvailable();
		} catch (java.io.IOException err) {
			UiUtils.showAlert(ctx, me.aap.fermata.R.string.sub_gen_error, err.getLocalizedMessage());
			return;
		}
		var store = new WhisperModelStore(ctx.getFilesDir());
		var language = prefs.getStringPref(LANG);
		var models = new ArrayList<WhisperModels.Model>();
		var labels = new ArrayList<String>();
		for (var model : WhisperModels.MODELS) {
			var fixed = model.fixedLanguage();
			if (fixed != null && !language.equals("auto") && !language.equals(fixed)) continue;
			models.add(model);
		}
		var selected = prefs.getStringPref(MODEL);
		models.sort(java.util.Comparator.comparingInt(model -> store.installed(model) ?
				(model.id().equals(selected) ? 0 : 1) : 2));
		for (var model : models) {
			var guidance = switch (model.id()) {
				case "base.en-q5_1" -> ctx.getString(me.aap.fermata.R.string.whisper_model_compact_english);
				case "tiny-q5_1", "base-q4_k", "small-q4_k" -> "Compact multilingual";
				case "tiny.en-q5_1" -> ctx.getString(me.aap.fermata.R.string.whisper_model_compact_english);
				case "large-v3-turbo-q3_k" -> "Smaller Turbo · Multilingual";
				case "nava-q5_0" -> "Persian-tuned · High memory use";
				case "large-v3-turbo-q4_0" -> ctx.getString(me.aap.fermata.R.string.whisper_model_turbo);
				default -> model.englishOnly() ? "English" : "Multilingual";
			};
			labels.add(String.format(Locale.getDefault(), "%s · %.1f MB · %s · %s",
					model.id(), model.bytes() / 1_000_000.0, guidance,
					store.installed(model) ? "Installed" : "Download"));
		}
		new AlertDialog.Builder(ctx).setTitle(me.aap.fermata.R.string.sub_gen_manage_models)
				.setItems(labels.toArray(new String[0]), (dialog, index) -> {
					var model = models.get(index);
					if (store.installed(model)) showInstalled(ctx, prefs, onSelected, store, model);
					else download(ctx, prefs, onSelected, store, model);
				}).setNegativeButton(android.R.string.cancel, null).show();
	}

	private void showInstalled(Context ctx, PreferenceStore prefs, Runnable onSelected,
			WhisperModelStore store, WhisperModels.Model model) {
		new AlertDialog.Builder(ctx).setTitle(model.id())
				.setPositiveButton(onSelected == null ? "Select" : "Start transcription", (dialog, which) -> {
					App.get().execute(() -> store.require(model)).main().onSuccess(file -> {
						prefs.applyStringPref(MODEL, model.id());
						if (onSelected != null) onSelected.run();
					}).onFailure(err -> showDownloadError(ctx, prefs, onSelected, store, model, err));
				}).setNeutralButton("Delete", (dialog, which) -> {
					try {
						store.delete(model);
						showModels(ctx, prefs, onSelected);
					} catch (java.io.IOException err) {
						UiUtils.showAlert(ctx, me.aap.fermata.R.string.sub_gen_error, err.getLocalizedMessage());
					}
				}).setNegativeButton(android.R.string.cancel, null).show();
	}

	private void download(Context ctx, PreferenceStore prefs, Runnable onSelected,
			WhisperModelStore store, WhisperModels.Model model) {
		var progress = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
		progress.setMax(100);
		var cancelled = new AtomicBoolean();
		var dialog = new AlertDialog.Builder(ctx).setTitle("Downloading " + model.id())
				.setView(progress).setNegativeButton(android.R.string.cancel,
						(d, which) -> cancelled.set(true))
				.setOnCancelListener(d -> cancelled.set(true)).show();
		App.get().execute(() -> store.download(model, cancelled::get,
				percent -> App.get().run(() -> progress.setProgress(percent))))
				.main().onCompletion((unused, err) -> {
					dialog.dismiss();
					if (cancelled.get()) showModels(ctx, prefs, onSelected);
					else if (err != null) showDownloadError(ctx, prefs, onSelected, store, model, err);
					else showInstalled(ctx, prefs, onSelected, store, model);
				});
	}

	private void showDownloadError(Context ctx, PreferenceStore prefs, Runnable onSelected,
			WhisperModelStore store, WhisperModels.Model model, Throwable error) {
		new AlertDialog.Builder(ctx).setTitle(model.id()).setMessage(error.getLocalizedMessage())
				.setPositiveButton("Retry download", (dialog, which) ->
						download(ctx, prefs, onSelected, store, model))
				.setNegativeButton(android.R.string.cancel, null).show();
	}

	@Override
	protected List<Pair<String, String>> getSupportedLanguages() {
		var supported = new String[]{
				"af", "am", "ar", "as", "az", "ba", "be", "bg", "bn", "bo", "br", "bs", "ca", "cs", "cy",
				"da", "de", "el", "en", "es", "et", "eu", "fa", "fi", "fo", "fr", "gl", "gu", "ha", "haw",
				"he", "hi", "hr", "ht", "hu", "hy", "id", "is", "it", "ja", "jw", "ka", "kk", "km", "kn",
				"ko", "la", "lb", "ln", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt",
				"my", "ne", "nl", "nn", "no", "oc", "pa", "pl", "ps", "pt", "ro", "ru", "sa", "sd", "si",
				"sk", "sl", "sn", "so", "sq", "sr", "su", "sv", "sw", "ta", "te", "tg", "th", "tk", "tl",
				"tr", "tt", "uk", "ur", "uz", "vi", "yi", "yo", "zh", "yue"
		};
		var locale = Locale.getDefault(Locale.Category.DISPLAY);
		var values = new ArrayList<Pair<String, String>>(supported.length + 1);
		values.add(
				new Pair<>("auto", FermataApplication.get().getString(me.aap.fermata.R.string.auto)));
		for (String lang : supported) {
			values.add(new Pair<>(lang, new Locale(lang).getDisplayLanguage(locale)));
		}
		values.subList(1, values.size()).sort((a, b) -> a.second.compareToIgnoreCase(b.second));
		return values;
	}

	@Override
	public Collection<String> doCleanUp(Context ctx, PreferenceStore ps) {
		return Whisper.cleanUp(ctx, ps);
	}
}
