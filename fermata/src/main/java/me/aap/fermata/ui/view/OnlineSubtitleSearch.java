package me.aap.fermata.ui.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.StringRes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.R;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.sub.OpenSubtitlesClient;
import me.aap.utils.app.App;
import me.aap.utils.function.CheckedSupplier;
import me.aap.utils.function.Consumer;
import me.aap.utils.ui.UiUtils;

final class OnlineSubtitleSearch {
	private static final String[] LANGUAGES = {
			"English", "Persian (Farsi)", "Arabic", "French", "Spanish", "German", "Turkish",
			"Russian", "Italian", "Portuguese", "Brazilian Portuguese", "Chinese", "Japanese",
			"Korean", "Hindi", "Any language"
	};
	private static final String[] CODES = {
			"en", "fa", "ar", "fr", "es", "de", "tr", "ru", "it", "pt-pt", "pt-br",
			"zh-cn", "ja", "ko", "hi", ""
	};

	private final Context context;
	private final PlayableItem video;
	private final Consumer<SubtitleStreamInfo.External> onSelected;

	OnlineSubtitleSearch(Context context, PlayableItem video,
			Consumer<SubtitleStreamInfo.External> onSelected) {
		this.context = context;
		this.video = video;
		this.onSelected = onSelected;
	}

	void show() {
		var view = form();
		var title = field(view, R.string.subtitle_search_title, false);
		title.setHint(R.string.subtitle_search_title_hint);
		var filename = video.getResource().getName();
		int extension = filename.lastIndexOf('.');
		var suggested = (extension > 0 ? filename.substring(0, extension) : filename)
				.replaceAll("[._]+", " ")
				.replaceFirst("(?i)\\s+(480p|720p|1080p|2160p|WEB[- ]?DL|BluRay|WEBRip)\\b.*", "").trim();
		title.setText(suggested);
		var language = new Spinner(context);
		var adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, LANGUAGES);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		language.setAdapter(adapter);
		var preferences = context.getSharedPreferences("online-subtitles", Context.MODE_PRIVATE);
		int defaultLanguage = Locale.getDefault().getLanguage().equals("fa") ? 1 : 0;
		language.setSelection(Math.max(0, Math.min(CODES.length - 1,
				preferences.getInt("language", defaultLanguage))));
		addLabeledControl(view, R.string.subtitle_search_language, language);

		var dialog = new AlertDialog.Builder(context).setTitle(R.string.subtitle_online_search)
				.setView(scroll(view)).setMessage(R.string.subtitle_search_help)
				.setPositiveButton(R.string.subtitle_search_action, null)
				.setNeutralButton(R.string.subtitle_open_website, (d, which) -> openWebsite(title.getText().toString(),
						language.getSelectedItemPosition()))
				.setNegativeButton(android.R.string.cancel, null).show();
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
			var text = title.getText().toString().trim();
			if (text.isEmpty()) {
				title.setError(context.getString(R.string.subtitle_search_title_required));
				return;
			}
			int selection = language.getSelectedItemPosition();
			preferences.edit().putInt("language", selection).apply();
			var code = CODES[selection];
			dialog.dismiss();
			var client = new OpenSubtitlesClient(BuildConfig.OPENSUBTITLES_API_KEY);
			run(context.getString(R.string.subtitle_search_working),
					() -> client.searchVideo(text, code, text.equals(suggested) ?
							OpenSubtitlesClient.localFingerprint(context.getContentResolver(), video.getLocation()) : null),
					results -> {
				var resultDialog = new AlertDialog.Builder(context).setTitle(R.string.subtitle_search_results)
						.setNegativeButton(android.R.string.cancel, null)
						.setNeutralButton(R.string.subtitle_search_again, (d, which) -> show());
				if (results.isEmpty()) resultDialog.setMessage(R.string.subtitle_search_empty);
				else resultDialog.setItems(results.stream().map(OpenSubtitlesClient.Result::label)
						.toArray(String[]::new), (d, index) -> authenticate(client, results.get(index)));
				resultDialog.show();
			});
		});
	}

	private void authenticate(OpenSubtitlesClient client, OpenSubtitlesClient.Result result) {
		if (result.directLink() != null) {
			run(context.getString(R.string.subtitle_download_working),
					() -> save(result, client.download(result, "", "")), onSelected);
			return;
		}
		var view = form();
		var user = field(view, R.string.subtitle_search_username, false);
		var pass = field(view, R.string.subtitle_search_password, true);
		user.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
		pass.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
		var dialog = new AlertDialog.Builder(context).setTitle("Download subtitle")
				.setMessage("Sign in to OpenSubtitles to download. Fermata does not save your password.")
				.setView(scroll(view)).setPositiveButton("Download", null)
				.setNegativeButton(android.R.string.cancel, null).show();
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
			var username = user.getText().toString().trim();
			var password = pass.getText().toString();
			if (username.isEmpty() || password.isEmpty()) {
				(pass.length() == 0 ? pass : user).setError("Required");
				return;
			}
			pass.getText().clear();
			dialog.dismiss();
			run(context.getString(R.string.subtitle_download_working),
					() -> save(result, client.download(result, username, password)), onSelected);
		});
	}

	private SubtitleStreamInfo.External save(OpenSubtitlesClient.Result result, byte[] bytes) throws IOException {
		var directory = new File(context.getFilesDir(), "online-subtitles");
		if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
			throw new IOException("Could not create the subtitle folder.");
		var extension = result.filename().toLowerCase(Locale.ROOT).endsWith(".vtt") ? ".vtt" : ".srt";
		var file = File.createTempFile("opensubtitles-", extension, directory);
		var lang = result.language();
		var encoding = switch (lang) {
			case "fa", "ar", "ur" -> "windows-1256";
			case "ru", "uk", "bg" -> "windows-1251";
			default -> "windows-1252";
		};
		var stream = new SubtitleStreamInfo.External(Uri.fromFile(file), result.filename(), encoding);
		boolean valid = false;
		try {
			try (var out = new FileOutputStream(file)) {
				out.write(bytes);
			}
			if (stream.load(context).isEmpty())
				throw new IOException(context.getString(R.string.subtitle_download_invalid));
			valid = true;
			return stream;
		} finally {
			if (!valid) file.delete();
		}
	}

	private void openWebsite(String title, int language) {
		String[] languages = {"eng", "per", "ara", "fre", "spa", "ger", "tur", "rus",
				"ita", "por", "pob", "chi", "jpn", "kor", "hin", "all"};
		var url = Uri.parse("https://www.opensubtitles.org/en/search2").buildUpon()
				.appendQueryParameter("MovieName", title)
				.appendQueryParameter("SubLanguageID", languages[language]).build();
		try {
			context.startActivity(new Intent(Intent.ACTION_VIEW, url));
		} catch (android.content.ActivityNotFoundException ignored) {
			UiUtils.showAlert(context, "No browser is available to open OpenSubtitles.");
		}
	}

	private LinearLayout form() {
		var layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int inset = me.aap.utils.ui.UiUtils.toIntPx(context, 24);
		layout.setPadding(inset, 0, inset, 0);
		return layout;
	}

	private ScrollView scroll(LinearLayout contents) {
		var scroll = new ScrollView(context);
		scroll.addView(contents);
		return scroll;
	}

	private EditText field(LinearLayout parent, @StringRes int label, boolean secret) {
		var field = new EditText(context);
		field.setSingleLine();
		if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		addLabeledControl(parent, label, field);
		return field;
	}

	private void addLabeledControl(LinearLayout parent, @StringRes int title, View control) {
		int spacing = me.aap.utils.ui.UiUtils.toIntPx(context, 8);
		var label = new TextView(context);
		label.setText(title);
		label.setPadding(0, spacing, 0, 0);
		control.setId(View.generateViewId());
		label.setLabelFor(control.getId());
		control.setMinimumHeight(me.aap.utils.ui.UiUtils.toIntPx(context, 48));
		parent.addView(label);
		parent.addView(control, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
	}

	private <T> void run(String title, CheckedSupplier<T, Throwable> operation, Consumer<T> result) {
		var contents = form();
		contents.addView(new ProgressBar(context));
		var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
		var work = App.get().execute(operation);
		var dialog = new AlertDialog.Builder(context).setTitle(title).setView(contents)
				.setNegativeButton(android.R.string.cancel, (d, which) -> {
					cancelled.set(true);
					work.cancel();
				})
				.setOnCancelListener(d -> {
					cancelled.set(true);
					work.cancel();
				}).show();
		work.main().onCompletion((value, error) -> {
			dialog.dismiss();
			if (cancelled.get()) return;
			if (error == null) result.accept(value);
			else new AlertDialog.Builder(context).setTitle(R.string.subtitle_search_error)
					.setMessage(error.getLocalizedMessage())
					.setPositiveButton(R.string.subtitle_search_retry, (d, which) -> run(title, operation, result))
					.setNeutralButton(R.string.subtitle_search_again, (d, which) -> show())
					.setNegativeButton(android.R.string.cancel, null).show();
		});
	}
}
