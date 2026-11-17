package me.aap.fermata.ui.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.sub.OpenSubtitlesClient;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
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
		var title = field(view, "Movie name or series S01E02", false);
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
		view.addView(language);
		var configuredKey = BuildConfig.OPENSUBTITLES_API_KEY;
		boolean usePublisherKey = !configuredKey.isEmpty();
		var key = usePublisherKey ? null : field(view, "OpenSubtitles API key", true);
		if (key != null) key.setText(preferences.getString("api-key", ""));

		var dialog = new AlertDialog.Builder(context).setTitle("Find subtitles online")
				.setView(view).setMessage("Search OpenSubtitles by video match or title.")
				.setPositiveButton("Search", null)
				.setNeutralButton("Open website", (d, which) -> openWebsite(title.getText().toString(),
						language.getSelectedItemPosition()))
				.setNegativeButton(android.R.string.cancel, null).show();
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
			var text = title.getText().toString().trim();
			var apiKey = key == null ? configuredKey : key.getText().toString().trim();
			if (text.isEmpty()) {
				title.setError("Enter a title");
				return;
			}
			if (key != null && apiKey.isEmpty()) {
				key.setError("Enter your API key or use Open website");
				return;
			}
			int selection = language.getSelectedItemPosition();
			var settings = preferences.edit().putInt("language", selection);
			if (key != null) settings.putString("api-key", apiKey);
			settings.apply();
			var code = CODES[selection];
			dialog.dismiss();
			var client = new OpenSubtitlesClient(apiKey);
			var task = App.get().execute(() -> client.search(text, code, text.equals(suggested) ?
					OpenSubtitlesClient.localHash(context.getContentResolver(), video.getLocation()) : null));
			run("Searching OpenSubtitles…", task, results -> {
				var resultDialog = new AlertDialog.Builder(context).setTitle("OpenSubtitles results")
						.setNegativeButton(android.R.string.cancel, null)
						.setNeutralButton("New search", (d, which) -> show());
				if (results.isEmpty()) resultDialog.setMessage("No subtitles found for this title and language.");
				else resultDialog.setItems(results.stream().map(OpenSubtitlesClient.Result::label)
						.toArray(String[]::new), (d, index) -> authenticate(client, results.get(index)));
				resultDialog.show();
			});
		});
	}

	private void authenticate(OpenSubtitlesClient client, OpenSubtitlesClient.Result result) {
		var view = form();
		var user = field(view, "OpenSubtitles username", false);
		var pass = field(view, "OpenSubtitles password", true);
		var dialog = new AlertDialog.Builder(context).setTitle("Download subtitle")
				.setMessage("Sign in to OpenSubtitles to download. Fermata does not save your password.")
				.setView(view).setPositiveButton("Download", null)
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
			run("Downloading subtitle…", App.get().execute(() ->
					save(result, client.download(result, username, password))), onSelected);
		});
	}

	private SubtitleStreamInfo.External save(OpenSubtitlesClient.Result result, byte[] bytes) throws IOException {
		var directory = new File(context.getFilesDir(), "online-subtitles");
		if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
			throw new IOException("Could not create the subtitle folder.");
		var file = File.createTempFile("opensubtitles-", ".srt", directory);
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
			stream.load(context);
			valid = true;
			return stream;
		} finally {
			if (!valid) file.delete();
		}
	}

	private void openWebsite(String title, int language) {
		var url = Uri.parse("https://www.opensubtitles.org/en/search2").buildUpon()
				.appendQueryParameter("MovieName", title)
				.appendQueryParameter("SubLanguageID", language < 2 ?
						(language == 0 ? "eng" : "per") : "all").build();
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

	private EditText field(LinearLayout parent, String hint, boolean secret) {
		var field = new EditText(context);
		field.setSingleLine();
		field.setHint(hint);
		if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		parent.addView(field);
		return field;
	}

	private <T> void run(String title, FutureSupplier<T> work, Consumer<T> result) {
		var contents = form();
		contents.addView(new ProgressBar(context));
		var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
		var dialog = new AlertDialog.Builder(context).setTitle(title).setView(contents)
				.setNegativeButton(android.R.string.cancel, (d, which) -> cancelled.set(true))
				.setOnCancelListener(d -> cancelled.set(true)).show();
		work.main().onCompletion((value, error) -> {
			dialog.dismiss();
			if (cancelled.get()) return;
			if (error == null) result.accept(value);
			else UiUtils.showAlert(context, "OpenSubtitles: " + error.getLocalizedMessage());
		});
	}
}
