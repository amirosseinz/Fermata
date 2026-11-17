package me.aap.fermata.ui.view;

import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
import static me.aap.utils.ui.UiUtils.getTextAppearanceSize;
import static me.aap.utils.ui.UiUtils.isVisible;
import static me.aap.utils.ui.UiUtils.toIntPx;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.provider.OpenableColumns;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DimenRes;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.GestureDetectorCompat;

import com.google.android.material.textview.MaterialTextView;

import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.SubGenAddon;
import me.aap.fermata.media.engine.AudioStreamInfo;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.pref.PlaybackControlPrefs;
import me.aap.fermata.media.pref.PlayableItemPrefs;
import me.aap.fermata.media.sub.FileSubtitles;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.app.App;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.Cancellable;
import me.aap.utils.function.ResultConsumer;
import me.aap.utils.log.Log;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.BasicPreferenceStore;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.GestureListener;
import me.aap.utils.ui.view.NavBarView;

/**
 * @author Andrey Pavlenko
 */
public class ControlPanelView extends ConstraintLayout
		implements MainActivityListener, PreferenceStore.Listener, OverlayMenu.SelectionHandler,
		GestureListener {
	private static final byte MASK_VISIBLE = 1;
	private static final byte MASK_VIDEO_MODE = 2;
	private final GestureDetectorCompat gestureDetector;
	private final ImageView showHideBars;
	@DimenRes
	private final int size;
	@StyleRes
	private final int textAppearance;
	private PlaybackControlPrefs prefs;
	private HideTimer hideTimer;
	private Cancellable hideTask = Cancellable.CANCELED;
	private byte mask;
	private View gestureSource;
	private TextView playbackTimer;
	private long scrollStamp;

	public ControlPanelView(Context context, AttributeSet attrs) {
		super(context, attrs, R.attr.appControlPanelStyle);
		gestureDetector = new GestureDetectorCompat(context, this);
		inflate(context, R.layout.control_panel_view, this);

		TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.ControlPanelView,
				R.attr.appControlPanelStyle, R.style.AppTheme_ControlPanelStyle);
		size = ta.getLayoutDimension(R.styleable.ControlPanelView_size, 0);
		textAppearance = ta.getResourceId(R.styleable.ControlPanelView_textAppearance, 0);
		setBackgroundColor(ta.getColor(R.styleable.ControlPanelView_android_colorBackground, 0));
		ta.recycle();

		MainActivityDelegate a = getActivity();
		a.addBroadcastListener(this, ACTIVITY_DESTROY);
		a.getPrefs().addBroadcastListener(this);

		ViewGroup g = findViewById(R.id.show_hide_bars);
		showHideBars = (ImageView) g.getChildAt(0);
		g.setOnClickListener(this::showHideBars);
		g = findViewById(R.id.control_menu_button);
		g.setOnClickListener(this::showMenu);
		findViewById(R.id.control_subtitles).setOnClickListener(this::showSubtitleMenu);
		findViewById(R.id.control_fullscreen).setOnClickListener(v -> getActivity().getBody().toggleCurrentVideo());
		if (!a.isCarActivity()) {
			for (int id : new int[]{R.id.control_menu_button, R.id.control_subtitles, R.id.control_prev,
					R.id.control_rw, R.id.control_ff, R.id.control_next}) {
				View control = findViewById(id);
				control.setTooltipText(control.getContentDescription());
			}
		}
		setShowHideBarsIcon(a);
	}

	@Nullable
	@Override
	protected Parcelable onSaveInstanceState() {
		Parcelable parentState = super.onSaveInstanceState();
		Bundle b = new Bundle();
		b.putByte("MASK", mask);
		b.putParcelable("PARENT", parentState);
		return b;
	}

	@Override
	protected void onRestoreInstanceState(Parcelable st) {
		if (st instanceof Bundle b) {
			super.onRestoreInstanceState(b.getParcelable("PARENT"));
			mask = b.getByte("MASK");
			if (mask != MASK_VISIBLE) super.setVisibility(GONE);
		}
	}

	public void bind(FermataServiceUiBinder b) {
		computeSize();
		prefs = b.getMediaSessionCallback().getPlaybackControlPrefs();
		b.bindControlPanel(this);
		b.bindPrevButton(findViewById(R.id.control_prev));
		b.bindRwButton(findViewById(R.id.control_rw));
		b.bindPlayPauseButton(findViewById(R.id.control_play_pause));
		b.bindFfButton(findViewById(R.id.control_ff));
		b.bindNextButton(findViewById(R.id.control_next));
		b.bindProgressBar(findViewById(R.id.seek_bar));
		b.bindProgressTime(findViewById(R.id.seek_time));
		b.bindProgressTotal(findViewById(R.id.seek_total));
		b.bound();
		post(() -> {
			var a = getActivity();
			if (a.getBody() != null) a.getBody().refreshFullscreenShortcut(a);
		});
	}

	void computeSize() {
		MainActivityDelegate a = getActivity();
		setSize(a.getPrefs().getControlPanelSizePref(a));
	}

	private void setSize(float scale) {
		TextView seekTime = findViewById(R.id.seek_time);
		TextView seekTotal = findViewById(R.id.seek_total);
		float textSize = getTextAppearanceSize(getContext(), textAppearance) * scale;
		int textPad = seekTime.getPaddingTop() + seekTime.getPaddingBottom();
		int pad = 2 * toIntPx(getContext(), 4) + textPad;
		boolean onCar = getActivity().isCarActivity();
		int minimumTarget = toIntPx(getContext(), onCar ? 76 : 48);
		int iconSize = Math.max(minimumTarget, (int) (textSize + pad));
		int buttonSize = Math.max(minimumTarget, (int) (size * scale - textSize - pad));
		ControlPanelSeekView seek = findViewById(R.id.seek_bar);
		boolean twoRows = seek.usesTwoRows();
		int shortcutHeight = twoRows ? iconSize : buttonSize;
		int shortcutWidth = twoRows ? minimumTarget : 0;
		setDimensions(R.id.control_subtitles, shortcutWidth, shortcutHeight);
		setDimensions(R.id.control_fullscreen, shortcutWidth, shortcutHeight);
		setHeight(R.id.show_hide_bars, shortcutHeight);
		setHeight(R.id.control_menu_button, shortcutHeight);
		findViewById(R.id.show_hide_bars).setMinimumWidth(shortcutWidth);
		findViewById(R.id.control_menu_button).setMinimumWidth(shortcutWidth);
		int icon = toIntPx(getContext(), onCar ? 36 : 24);
		setDimensions(R.id.show_hide_bars_icon, icon, icon);
		setDimensions(R.id.control_menu_button_icon, icon, icon);

		if (twoRows) {
			setHeight(seek, iconSize);
			setTextAppearance(seekTime, textSize);
			setTextAppearance(seekTotal, textSize);
			setHeight(R.id.control_rw, buttonSize);
			setHeight(R.id.control_ff, buttonSize);
		}
		setHeight(R.id.control_prev, buttonSize);
		setHeight(R.id.control_play_pause, buttonSize);
		setHeight(R.id.control_next, buttonSize);
		getLayoutParams().height = twoRows ? iconSize + buttonSize : buttonSize;
	}

	private void setTextAppearance(TextView t, float size) {
		t.setTextAppearance(textAppearance);
		t.setTextSize(COMPLEX_UNIT_PX, size);
	}

	private void setDimensions(@IdRes int id, int width, int height) {
		View v = findViewById(id);
		ViewGroup.LayoutParams lp = v.getLayoutParams();
		if (lp.width == width && lp.height == height) return;
		lp.width = width;
		lp.height = height;
		v.setLayoutParams(lp);
	}

	private void setHeight(@IdRes int id, int h) {
		setHeight(findViewById(id), h);
	}

	private void setHeight(View v, int h) {
		ViewGroup.LayoutParams lp = v.getLayoutParams();
		if (lp.height == h) return;
		lp.height = h;
		v.setLayoutParams(lp);
	}

	public boolean isActive() {
		return mask != 0;
	}

	@Override
	public void setVisibility(int visibility) {
		MainActivityDelegate a = getActivity();

		if (visibility == VISIBLE) {
			mask |= MASK_VISIBLE;
			if ((mask & MASK_VIDEO_MODE) != 0) return;

			super.setVisibility(VISIBLE);

			if (a.getPrefs().getHideBarsPref(a)) {
				a.setBarsHidden(true);
				setShowHideBarsIcon(a);
			}
		} else {
			mask &= ~MASK_VISIBLE;
			super.setVisibility(GONE);
			a.getFloatingButton().setVisibility(VISIBLE);

			if (a.isBarsHidden()) {
				a.setBarsHidden(false);
				setShowHideBarsIcon(a);
			}
		}

		checkPlaybackTimer(a);
	}

	public void enableVideoMode(@Nullable VideoView v) {
		MainActivityDelegate a = getActivity();
		cancelHide();
		mask |= MASK_VIDEO_MODE;

		a.setBarsHidden(true);
		setShowHideBarsIcon(a);

		View info = (v != null) ? v.getVideoInfoView() : null;
		View fb = a.getFloatingButton();
		int delay = getStartDelay();

		if (delay == 0) {
			fb.setVisibility(GONE);
			if (info != null) info.setVisibility(GONE);
			super.setVisibility(GONE);
		} else {
			fb.setVisibility(VISIBLE);
			if (info != null) info.setVisibility(VISIBLE);
			super.setVisibility(VISIBLE);
			scheduleHide(a, delay, false, info, fb);
		}

		checkPlaybackTimer(a);
	}

	public void disableVideoMode() {
		MainActivityDelegate a = getActivity();
		cancelHide();
		mask &= ~MASK_VIDEO_MODE;
		a.getFloatingButton().setVisibility(VISIBLE);

		if ((mask & MASK_VISIBLE) == 0) {
			super.setVisibility(GONE);
			a.setBarsHidden(false);
		} else {
			super.setVisibility(VISIBLE);
			a.setBarsHidden(a.getPrefs().getHideBarsPref(a));
		}

		setShowHideBarsIcon(a);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		MainActivityDelegate a = getActivity();
		if (hideTimer != null) scheduleHide(a, getTouchDelay(), false, hideTimer.views);
		return a.interceptTouchEvent(e, me -> {
			gestureSource = this;
			gestureDetector.onTouchEvent(me);
			return super.onTouchEvent(me);
		});
	}

	@Override
	public boolean onSwipeLeft(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextButtonClick(true);
		return true;
	}

	@Override
	public boolean onSwipeRight(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextButtonClick(false);
		return true;
	}

	@Override
	public boolean onSwipeUp(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextFolderClick(false);
		return true;
	}

	@Override
	public boolean onSwipeDown(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextFolderClick(true);
		return true;
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		boolean horizontal = Math.abs(distanceX) >= Math.abs(distanceY);
		long time = System.currentTimeMillis();
		long diff;

		if (horizontal) {
			diff = time - scrollStamp;
			if (diff < 100) return true;
			scrollStamp = time;
		} else {
			diff = time + scrollStamp;
			if (diff < 100) return true;
			scrollStamp = -time;
		}

		if (diff > 500) return true;

		if (horizontal) {
			FermataServiceUiBinder b = getActivity().getMediaServiceBinder();

			switch (e2.getPointerCount()) {
				case 1 -> b.onRwFfButtonClick(distanceX < 0);
				case 2 -> b.onRwFfButtonLongClick(distanceX < 0);
				default -> b.onPrevNextButtonLongClick(distanceX < 0);
			}

			onVideoSeek();
		} else if (e2.getPointerCount() == 2) {
			if (!getActivity().getPrefs().getChangeBrightnessPref()) return true;
			MainActivityDelegate a = getActivity();
			int br = a.getBrightness();
			br = (distanceY > 0) ? Math.min(255, br + 10) : Math.max(0, br - 10);
			a.setBrightness(br);
		} else {
			MediaEngine eng = getActivity().getMediaServiceBinder().getCurrentEngine();
			return (eng != null) && eng.adjustVolume((distanceY > 0) ? ADJUST_RAISE : ADJUST_LOWER);
		}

		return true;
	}

	@Override
	public boolean onDoubleTap(MotionEvent e) {
		if (!(gestureSource instanceof VideoView)) return false;
		getActivity().getMediaServiceBinder().onPlayPauseButtonClick();
		return true;
	}

	@Override
	public boolean onSingleTapConfirmed(MotionEvent e) {
		if (!(gestureSource instanceof VideoView)) return false;
		return onTouch((VideoView) gestureSource);
	}

	public boolean onTouch(VideoView video) {
		MainActivityDelegate a = getActivity();
		BodyLayout b = a.getBody();

		if (b.getMode() == BodyLayout.Mode.BOTH) {
			b.setMode(BodyLayout.Mode.VIDEO);
			return true;
		}

		int delay = getTouchDelay();
		if (delay == 0) return false;

		View info = video.getVideoInfoView();
		View fb = a.getFloatingButton();

		if (getVisibility() == VISIBLE) {
			cancelHide();
			super.setVisibility(GONE);
			fb.setVisibility(GONE);
			if (a.getPrefs().getSysBarsOnVideoTouchPref()) a.setFullScreen(true);
			if (info != null) info.setVisibility(GONE);
		} else {
			super.setVisibility(VISIBLE);
			fb.setVisibility(VISIBLE);
			if (a.getPrefs().getSysBarsOnVideoTouchPref()) a.setFullScreen(false);
			if (info != null) info.setVisibility(VISIBLE);
			clearFocus();
			scheduleHide(a, delay, false, info, fb);
		}

		checkPlaybackTimer(a);
		return true;
	}

	public void onVideoViewTouch(VideoView view, MotionEvent e) {
		gestureSource = view;
		gestureDetector.onTouchEvent(e);
	}

	public void onVideoSeek() {
		MainActivityDelegate a = getActivity();
		VideoView vv = a.getMediaServiceBinder().getMediaSessionCallback().getVideoView();

		if (vv == null) {
			if (gestureSource instanceof VideoView) vv = (VideoView) gestureSource;
			else return;
		}

		View info = vv.getVideoInfoView();
		View fb = a.getFloatingButton();
		int delay = getSeekDelay();
		super.setVisibility(VISIBLE);
		fb.setVisibility(VISIBLE);
		if (info != null) info.setVisibility(VISIBLE);
		clearFocus();
		scheduleHide(a, delay, true, info, fb);
		checkPlaybackTimer(a);
	}

	public boolean isVideoSeekMode() {
		HideTimer t = hideTimer;
		return (t != null) && t.seekMode;
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (handleActivityDestroyEvent(a, e)) {
			cancelHide();
			a.getMediaServiceBinder().unbind();
			a.getPrefs().removeBroadcastListener(this);
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		MainActivityDelegate a = getActivity();

		if (MainActivityPrefs.hasControlPanelSizePref(a, prefs)) {
			setSize(a.getPrefs().getControlPanelSizePref(a));
		} else if ((mask == MASK_VISIBLE) && MainActivityPrefs.hasHideBarsPref(a, prefs)) {
			if (a.getPrefs().getHideBarsPref(a)) a.setBarsHidden(getVisibility() == VISIBLE);
			else if (a.isBarsHidden()) a.setBarsHidden(false);
			setShowHideBarsIcon(a);
		}
	}

	public View focusSearch() {
		View v = findViewById(R.id.seek_bar);
		return v.isEnabled() && isVisible(v) ? v : findViewById(R.id.control_play_pause);
	}

	@Override
	public View focusSearch(View focused, int direction) {
		if (focused == null) return super.focusSearch(null, direction);

		if (direction == FOCUS_UP) {
			if (isLine1(focused)) {
				MainActivityDelegate a = getActivity();
				if (a.isVideoMode()) return a.getBody().getVideoView();
				View v = MediaItemListView.focusSearchLast(getContext(), focused);
				if (v != null) return v;
			} else {
				if (!isVisible(findViewById(R.id.seek_bar))) return findViewById(R.id.control_menu_button);
			}
		} else if (direction == FOCUS_DOWN) {
			if (!isLine1(focused)) {
				NavBarView n = getActivity().getNavBar();
				if (isVisible(n) && n.isBottom()) return n.focusSearch();
			}
		}

		return super.focusSearch(focused, direction);
	}

	private boolean isLine1(View v) {
		ControlPanelSeekView seek = findViewById(R.id.seek_bar);
		if (!seek.usesTwoRows()) return true;
		int id = v.getId();
		return id == R.id.seek_bar || id == R.id.show_hide_bars ||
				id == R.id.control_subtitles || id == R.id.control_fullscreen || id == R.id.control_menu_button;
	}

	private void showHideBars(View v) {
		MainActivityDelegate a = getActivity();
		a.setBarsHidden(!a.isBarsHidden());
		setShowHideBarsIcon(a);
	}

	public void showMenu() {
		if (isActive()) showMenu(this);
	}

	private void showMenu(View v) {
		MainActivityDelegate a = getActivity();
		MediaEngine eng = a.getMediaServiceBinder().getCurrentEngine();
		PlayableItem i = (eng == null) ? null : eng.getSource();
		if (i != null) new MenuHandler(getMenu(a), i, eng).show();
	}

	void updateVideoShortcuts(boolean available, boolean fullscreen, boolean subtitlesAvailable) {
		findViewById(R.id.control_subtitles).setVisibility(subtitlesAvailable ? VISIBLE : GONE);
		ImageView button = findViewById(R.id.control_fullscreen);
		button.setVisibility(available ? VISIBLE : GONE);
		int label = fullscreen ? R.string.exit_fullscreen_video : R.string.show_fullscreen_video;
		button.setImageResource(fullscreen ? R.drawable.player_fullscreen_exit : R.drawable.player_fullscreen);
		button.setContentDescription(getContext().getText(label));
		if (!getActivity().isCarActivity()) button.setTooltipText(getContext().getText(label));
		ControlPanelSeekView seek = findViewById(R.id.seek_bar);
		seek.updateLayout();
	}

	boolean hasVideoShortcuts() {
		return findViewById(R.id.control_fullscreen).getVisibility() == VISIBLE ||
				findViewById(R.id.control_subtitles).getVisibility() == VISIBLE;
	}

	private void showSubtitleMenu(View view) {
		var a = getActivity();
		var engine = a.getMediaServiceBinder().getCurrentEngine();
		var source = engine == null ? null : engine.getSource();
		if (source == null || !source.isVideo() || !engine.isSubtitlesSupported()) return;
		var menu = getMenu(a);
		menu.show(new MenuHandler(menu, source, engine)::buildSubtitlesMenu);
	}

	private OverlayMenu getMenu(MainActivityDelegate a) {
		return a.findViewById(R.id.control_menu);
	}

	private void setShowHideBarsIcon(MainActivityDelegate a) {
		a.post(() -> {
			boolean hidden = a.isBarsHidden();
			showHideBars.setImageResource(hidden ? R.drawable.expand : me.aap.utils.R.drawable.collapse);
			View button = findViewById(R.id.show_hide_bars);
			CharSequence label = getContext().getText(hidden ? R.string.show_navigation : R.string.hide_navigation);
			button.setContentDescription(label);
			if (!a.isCarActivity()) button.setTooltipText(label);
		});
	}

	private MainActivityDelegate getActivity() {
		return MainActivityDelegate.get(getContext());
	}

	@Override
	public boolean menuItemSelected(OverlayMenuItem item) {
		return true;
	}

	private void checkPlaybackTimer(MainActivityDelegate a) {
		MediaSessionCallback cb = a.getMediaSessionCallback();
		int t = cb.getPlaybackTimer();

		if (t <= 0) {
			if (playbackTimer != null) {
				((ViewGroup) getParent()).removeView(playbackTimer);
				playbackTimer = null;
			}
		} else {
			if (playbackTimer == null) {
				Context ctx = getContext();
				playbackTimer = new MaterialTextView(ctx);
				((ViewGroup) getParent()).addView(playbackTimer);
				playbackTimer.setBackgroundResource(R.drawable.playback_timer_bg);
				playbackTimer.setTextAppearance(textAppearance);
				ViewGroup.LayoutParams lp = playbackTimer.getLayoutParams();

				if (lp instanceof LayoutParams clp) {
					clp.startToStart = PARENT_ID;
					clp.endToEnd = PARENT_ID;
					clp.bottomToTop = getId();
					clp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				}

				playbackTimer.setOnClickListener(
						v -> getMenu(a).show(b -> new TimerMenuHandler(a).build(b)));
			}

			if (getVisibility() != VISIBLE) {
				playbackTimer.setVisibility(GONE);
				return;
			}

			try (SharedTextBuilder tb = SharedTextBuilder.get()) {
				TextUtils.timeToString(tb, t);
				playbackTimer.setText(tb);
			}

			playbackTimer.setVisibility(VISIBLE);
			a.postDelayed(() -> checkPlaybackTimer(a), 1000);
		}
	}

	private final class MenuHandler extends MediaItemMenuHandler {
		private final MediaEngine engine;

		public MenuHandler(OverlayMenu menu, Item item, MediaEngine engine) {
			super(menu, item);
			this.engine = engine;
		}

		@Override
		protected boolean addVideoMenu() {
			return !engine.hasVideoMenu();
		}

		@Override
		protected boolean addAudioMenu() {
			PlayableItem pi = engine.getSource();
			return (pi != null) && pi.isVideo() && ((engine.getAudioStreamInfo().size() > 1) ||
					getActivity().getMediaSessionCallback().getEngineManager().isVlcPlayerSupported());
		}

		@Override
		protected void buildAudioMenu(OverlayMenu.Builder b) {
			if (engine.getAudioStreamInfo().size() > 1) {
				b.addItem(R.id.select_audio_stream, R.string.select_audio_stream)
						.setSubmenu(this::buildAudioStreamMenu);
			}
			super.buildAudioMenu(b);
		}

		private void buildAudioStreamMenu(OverlayMenu.Builder b) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng == null) return;
			AudioStreamInfo ai = eng.getCurrentAudioStreamInfo();
			List<AudioStreamInfo> streams = eng.getAudioStreamInfo();
			b.setSelectionHandler(this::audioStreamSelected);

			for (int i = 0; i < streams.size(); i++) {
				AudioStreamInfo s = streams.get(i);
				b.addItem(UiUtils.getArrayItemId(i), s.toString()).setData(s).setChecked(s.equals(ai));
			}
		}

		private boolean audioStreamSelected(OverlayMenuItem i) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng != null) {
				AudioStreamInfo ai = i.getData();
				PlayableItem pi = (PlayableItem) getItem();

				if (ai.equals(eng.getCurrentAudioStreamInfo())) {
					pi.getPrefs().setAudioIdPref(null);
					eng.setCurrentAudioStream(null);
				} else {
					eng.setCurrentAudioStream(ai);
					pi.getPrefs().setAudioIdPref(ai.getId());
				}
			}
			return true;
		}

		@Override
		protected boolean addSubtitlesMenu() {
			return engine.isSubtitlesSupported();
		}

		@Override
		protected void buildSubtitlesMenu(OverlayMenu.Builder b) {
			b.addItem(R.id.select_subtitles, R.drawable.subtitles, R.string.subtitle_choose_track)
					.setFutureSubmenu(this::buildSubtitleStreamMenu);
			var source = engine.getSource();
			if (source != null && source.isVideo()) {
				var onCar = getActivity().getAppActivity().isCarActivity();
				b.addItem(R.id.subtitle_document_load, me.aap.utils.R.drawable.folder, onCar ?
						R.string.subtitle_document_load_phone : R.string.subtitle_document_load).setHandler(i -> {
					pickSubtitle(source);
					return true;
				});
				b.addItem(R.id.subtitle_online_search, onCar ? R.string.subtitle_online_search_phone :
						R.string.subtitle_online_search).setHandler(i -> {
					var ctx = getActivity().getContext();
					if (onCar) UiUtils.showInfo(ctx, ctx.getString(R.string.subtitle_online_use_phone));
					else new OnlineSubtitleSearch(ctx, source,
							stream -> applyExternalSubtitle(source, stream)).show();
					return true;
				});
				b.addItem(R.id.subtitle_auto_start, R.drawable.subgen, onCar ?
						R.string.subtitle_generate_phone : R.string.subtitle_generate).setHandler(i -> {
					startAutoSubtitles(source);
					return true;
				});
				if (engine.getCurrentSubtitleStreamInfo() != null ||
						source.getPrefs().getBooleanPref(SubGenAddon.ENABLED)) {
					b.addItem(R.id.subtitle_stream_off, R.string.subtitle_stream_off).setHandler(i -> {
						if (!isCurrent(source)) return true;
						source.getPrefs().applyBooleanPref(SubGenAddon.ENABLED, false);
						source.getPrefs().setSubIdPref(PlayableItemPrefs.SUB_OFF);
						engine.setCurrentSubtitleStream(null);
						return true;
					});
				}
			}
			super.buildSubtitlesMenu(b);
		}

		private boolean isCurrent(PlayableItem source) {
			return getActivity().getMediaSessionCallback().getEngine() == engine &&
					engine.getSource() == source;
		}

		private void pickSubtitle(PlayableItem source) {
			var a = getActivity();
			var ctx = a.getContext();
			if (a.getAppActivity().isCarActivity()) {
				UiUtils.showInfo(ctx, ctx.getString(R.string.subtitle_document_phone));
				return;
			}
			a.startActivityForResult(() -> new Intent(Intent.ACTION_OPEN_DOCUMENT)
					.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
							Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)).onSuccess(result -> {
				if (result == null || !isCurrent(source)) return;
				var uri = result.getData();
				if (uri == null) return;
				var resolver = ctx.getContentResolver();
				String displayName = null;
				try (var cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
						null, null, null)) {
					if (cursor != null && cursor.moveToFirst()) displayName = cursor.getString(0);
				} catch (RuntimeException err) {
					Log.w(err, "Unable to read subtitle document metadata");
				}
				var name = displayName == null ? uri.getLastPathSegment() : displayName;
				if (name == null || !FileSubtitles.isSupported(name)) {
					UiUtils.showAlert(ctx, R.string.subtitle_document_type);
					return;
				}
				var labels = new String[]{"Automatic (Unicode / Western European)",
						"Persian / Arabic (Windows-1256)", "Cyrillic (Windows-1251)"};
				var encodings = new String[]{"windows-1252", "windows-1256", "windows-1251"};
				new AlertDialog.Builder(ctx).setTitle(R.string.subtitle_document_encoding)
						.setItems(labels, (dialog, index) -> {
							if (!isCurrent(source)) return;
							try {
								resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
							} catch (SecurityException err) {
								UiUtils.showAlert(ctx, R.string.subtitle_document_error, err.getLocalizedMessage());
								return;
							}
							var stream = new SubtitleStreamInfo.External(uri, name, encodings[index]);
							App.get().execute(() -> stream.load(ctx)).main()
									.onSuccess(grid -> applyExternalSubtitle(source, stream))
									.onFailure(err -> UiUtils.showAlert(ctx, R.string.subtitle_document_error,
											err.getLocalizedMessage()));
						}).setNegativeButton(android.R.string.cancel, null).show();
			}).onFailure(err -> {
				if (!ResultConsumer.Cancel.isCancellation(err))
					UiUtils.showAlert(ctx, R.string.subtitle_document_error, err.getLocalizedMessage());
			});
		}

		private void applyExternalSubtitle(PlayableItem source, SubtitleStreamInfo.External stream) {
			if (!isCurrent(source)) return;
			var prefs = source.getPrefs();
			try (var edit = prefs.editPreferenceStore()) {
				edit.setStringPref(PlayableItemPrefs.SUB_DOCUMENT, stream.uri().toString());
				edit.setStringPref(PlayableItemPrefs.SUB_DOCUMENT_NAME, stream.getDescription());
				edit.setStringPref(PlayableItemPrefs.SUB_DOCUMENT_ENCODING, stream.legacyEncoding());
				edit.setLongPref(PlayableItemPrefs.SUB_ID, stream.getId());
				edit.setBooleanPref(MediaPrefs.SUB_ENABLED, true);
				edit.setBooleanPref(SubGenAddon.ENABLED, false);
			}
			engine.setCurrentSubtitleStream(stream);
		}

		private void startAutoSubtitles(PlayableItem source) {
			var a = getActivity();
			var ctx = a.getContext();
			if (a.getAppActivity().isCarActivity()) {
				UiUtils.showInfo(ctx, ctx.getString(R.string.sub_gen_model_phone));
				return;
			}
			var cb = a.getMediaSessionCallback();
			if (!cb.getEngineManager().isExoPlayerSupported()) {
				UiUtils.showAlert(ctx, R.string.sub_gen_no_engine);
				return;
			}
			SubGenAddon.manageModels(ctx, source.getPrefs(), () -> {
				if (!isCurrent(source)) return;
				engine.getPosition().main().onSuccess(position -> {
					if (!isCurrent(source)) return;
					try (var edit = source.getPrefs().editPreferenceStore()) {
						edit.setBooleanPref(MediaPrefs.SUB_ENABLED, true);
						edit.setBooleanPref(SubGenAddon.ENABLED, true);
						edit.setLongPref(PlayableItemPrefs.SUB_ID, Long.MIN_VALUE);
					}
					android.widget.Toast.makeText(ctx, R.string.subtitle_generation_started,
							android.widget.Toast.LENGTH_SHORT).show();
					cb.playItem(source, position);
				});
			});
		}

		private FutureSupplier<Void> buildSubtitleStreamMenu(OverlayMenu.Builder b) {
			b.setSelectionHandler(this::subtitleStreamSelected);
			return engine.getSubtitleStreamInfo().main().map(streams -> {
				SubtitleStreamInfo si = engine.getCurrentSubtitleStreamInfo();
				for (int i = 0; i < streams.size(); i++) {
					SubtitleStreamInfo s = streams.get(i);
					b.addItem(UiUtils.getArrayItemId(i), s.toString()).setData(s).setChecked(s.equals(si));
				}
				return null;
			});
		}

		private boolean subtitleStreamSelected(OverlayMenuItem i) {
			if (getActivity().getMediaSessionCallback().getEngine() != engine) return true;

			SubtitleStreamInfo si = i.getData();
			PlayableItem pi = (PlayableItem) getItem();

			if (engine.getSource() != pi || si.equals(engine.getCurrentSubtitleStreamInfo())) return true;
			if (si instanceof SubtitleStreamInfo.Generated) {
				startAutoSubtitles(pi);
				return true;
			}
			pi.getPrefs().applyBooleanPref(SubGenAddon.ENABLED, false);
			engine.setCurrentSubtitleStream(si);
			pi.getPrefs().setSubIdPref(si.getId());

			return true;
		}

		@Override
		protected void buildPlayableMenu(MainActivityDelegate a, OverlayMenu.Builder b,
																		 PlayableItem pi,
																		 boolean initRepeat) {
			super.buildPlayableMenu(a, b, pi, false);

			BrowsableItemPrefs p = pi.getParent().getPrefs();
			MediaEngine eng = a.getMediaSessionCallback().getEngine();
			if (eng == null) return;

			boolean stream = (pi.isStream());
			eng.contributeToMenu(b);

			if (!stream && !pi.isExternal()) {
				if (pi.isRepeatItemEnabled() || p.getRepeatPref()) {
					b.addItem(R.id.repeat, R.drawable.repeat_filled, R.string.repeat).setSubmenu(s -> {
						buildRepeatMenu(s);
						s.addItem(R.id.repeat_disable_all, R.string.repeat_disable);
					});
				} else {
					b.addItem(R.id.repeat_enable, R.drawable.repeat, R.string.repeat)
							.setSubmenu(this::buildRepeatMenu);
				}

				if (p.getShufflePref()) {
					b.addItem(R.id.shuffle_disable, R.drawable.shuffle_filled, R.string.shuffle_disable);
				} else {
					b.addItem(R.id.shuffle_enable, R.drawable.shuffle, R.string.shuffle);
				}
			}

			if (eng.supportsPlaybackVolume()) {
				b.addItem(R.id.playback_volume, a.getContext().getString(R.string.playback_volume_percent,
						pi.getPrefs().getPlaybackVolumePref())).setSubmenu(this::buildVolumeMenu);
			}

			if (pi.isVideo() && eng.isVideoModeRequired() && eng.isSplitModeSupported()) {
				b.addItem(R.id.control_fullscreen, a.getBody().isVideoMode() ?
						R.string.exit_fullscreen_video : R.string.show_fullscreen_video).setHandler(i -> {
					a.getBody().toggleCurrentVideo();
					return true;
				});
			}

			b.addItem(R.id.player_appearance, R.string.player_appearance).setSubmenu(this::buildAppearanceMenu);

			if (eng.getAudioEffects() != null) {
				b.addItem(R.id.audio_effects_fragment, R.drawable.equalizer, R.string.audio_effects);
			}

			if (!stream) {
				b.addItem(R.id.speed, R.drawable.speed, R.string.speed)
						.setSubmenu(s -> new SpeedMenuHandler().build(s, getItem()));
			}

			b.addItem(R.id.timer, R.drawable.timer, R.string.timer)
					.setSubmenu(s -> new TimerMenuHandler(a).build(s));
		}

		private void buildVolumeMenu(OverlayMenu.Builder menu) {
			int[] levels = {0, 25, 50, 75, 100, 125, 150, 175, 200};
			var source = engine.getSource();
			if (source == null) return;
			var a = getActivity();
			for (int i = 0; i < levels.length; i++) {
				int percent = levels[i];
				menu.addItem(UiUtils.getArrayItemId(i), a.getContext().getString(
								R.string.playback_volume_value, percent))
						.setChecked(source.getPrefs().getPlaybackVolumePref() == percent)
						.setHandler(item -> {
							if (!isCurrent(source)) return true;
							if (engine.setPlaybackVolume(percent)) source.getPrefs().setPlaybackVolumePref(percent);
							else UiUtils.showInfo(a.getContext(), a.getContext().getString(R.string.playback_volume_unavailable));
							return true;
						});
			}
		}

		private void buildAppearanceMenu(OverlayMenu.Builder menu) {
			var a = getActivity();
			var prefs = a.getPrefs();
			var pref = a.isCarActivity() ? MainActivityPrefs.THEME_AA : MainActivityPrefs.THEME_MAIN;
			int[] values = {MainActivityPrefs.THEME_SYSTEM, MainActivityPrefs.THEME_LIGHT, MainActivityPrefs.THEME_DARK};
			int[] labels = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};
			for (int i = 0; i < values.length; i++) {
				int value = values[i];
				menu.addItem(UiUtils.getArrayItemId(i), labels[i])
						.setChecked(prefs.getIntPref(pref) == value)
						.setHandler(item -> {
							if (prefs.getIntPref(pref) != value) prefs.applyIntPref(pref, value);
							return true;
						});
			}
		}

		private void buildRepeatMenu(OverlayMenu.Builder b) {
			b.setSelectionHandler(this);
			b.addItem(R.id.repeat_track, R.string.current_track);
			b.addItem(R.id.repeat_folder, R.string.current_folder);
		}

		@Override
		public boolean menuItemSelected(OverlayMenuItem i) {
			int id = i.getItemId();
			PlayableItem pi;
			MediaEngine eng;

			if (id == R.id.audio_effects_fragment) {
				eng = getActivity().getMediaSessionCallback().getEngine();
				if ((eng != null) && (eng.getAudioEffects() != null))
					getActivity().showFragment(R.id.audio_effects_fragment);
				return true;
			} else if (id == R.id.repeat_track || id == R.id.repeat_folder ||
					id == R.id.repeat_disable_all) {
				pi = (PlayableItem) getItem();
				pi.setRepeatItemEnabled(id == R.id.repeat_track);
				pi.getParent().getPrefs().setRepeatPref(id == R.id.repeat_folder);
				return true;
			} else if (id == R.id.shuffle_enable || id == R.id.shuffle_disable) {
				pi = (PlayableItem) getItem();
				pi.getParent().getPrefs().setShufflePref(id == R.id.shuffle_enable);
				return true;
			}

			return super.menuItemSelected(i);
		}
	}

	private final class SpeedMenuHandler implements OverlayMenu.CloseHandler {
		private PrefStore store;

		void build(OverlayMenu.Builder b, Item item) {
			store = new PrefStore(item);
			PreferenceSet set = new PreferenceSet();

			set.addFloatPref(o -> {
				o.title = R.string.speed;
				o.store = store;
				o.pref = MediaPrefs.SPEED;
				o.scale = 0.1f;
				o.seekMin = 1;
				o.seekMax = 20;
			});
			set.addBooleanPref(o -> {
				o.title = R.string.current_track;
				o.store = store;
				o.pref = store.TRACK;
			});
			set.addBooleanPref(o -> {
				o.title = R.string.current_folder;
				o.store = store;
				o.pref = store.FOLDER;
			});

			set.addToMenu(b, true);
			b.setCloseHandlerHandler(this);
		}

		@Override
		public void menuClosed(OverlayMenu menu) {
			store.apply();
		}

		private class PrefStore extends BasicPreferenceStore {
			final Pref<BooleanSupplier> TRACK = Pref.b("TRACK", false);
			final Pref<BooleanSupplier> FOLDER = Pref.b("FOLDER", false);
			private final MediaSessionCallback cb =
					getActivity().getMediaServiceBinder().getMediaSessionCallback();
			private final Item item;

			PrefStore(Item item) {
				this.item = item;
				MediaPrefs prefs = item.getPrefs();
				BrowsableItem p = item.getParent();
				boolean set = false;

				try (PreferenceStore.Edit edit = editPreferenceStore()) {
					if (prefs.hasPref(MediaPrefs.SPEED)) {
						edit.setBooleanPref(TRACK, true);
						edit.setFloatPref(MediaPrefs.SPEED, prefs.getFloatPref(MediaPrefs.SPEED));
						set = true;
					} else {
						edit.setBooleanPref(TRACK, false);
					}

					if (p != null) {
						prefs = p.getPrefs();

						if (prefs.hasPref(MediaPrefs.SPEED)) {
							edit.setBooleanPref(FOLDER, true);

							if (!set) {
								edit.setFloatPref(MediaPrefs.SPEED, prefs.getFloatPref(MediaPrefs.SPEED));
								set = true;
							}
						} else {
							edit.setBooleanPref(FOLDER, false);
						}
					} else {
						edit.setBooleanPref(FOLDER, false);
					}

					if (!set) edit.setFloatPref(MediaPrefs.SPEED,
							cb.getPlaybackControlPrefs().getFloatPref(MediaPrefs.SPEED));
				}
			}

			void apply() {
				BrowsableItem p = item.getParent();
				boolean set = false;

				if (getBooleanPref(TRACK)) {
					item.getPrefs().applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
					set = true;
				} else {
					item.getPrefs().removePref(MediaPrefs.SPEED);
				}

				if (p != null) {
					if (getBooleanPref(FOLDER)) {
						p.getPrefs().applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
						set = true;
					} else {
						p.getPrefs().removePref(MediaPrefs.SPEED);
					}
				}

				if (!set) {
					cb.getPlaybackControlPrefs()
							.applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
				}
			}

			@Override
			public void applyFloatPref(boolean removeDefault, Pref<? extends DoubleSupplier> pref,
																 float value) {
				if (value == 0.0f) value = 0.1f;
				super.applyFloatPref(removeDefault, pref, value);
				if (cb.isPlaying()) cb.onSetPlaybackSpeed(value);
			}
		}
	}

	private final class TimerMenuHandler extends BasicPreferenceStore
			implements OverlayMenu.CloseHandler {
		private final Pref<IntSupplier> H = Pref.i("H", 0);
		private final Pref<IntSupplier> M = Pref.i("M", 0);
		private final MainActivityDelegate activity;
		private boolean changed;
		private boolean closed;

		TimerMenuHandler(MainActivityDelegate activity) {
			this.activity = activity;
		}

		void build(OverlayMenu.Builder b) {
			PreferenceSet set = new PreferenceSet();
			int time = activity.getMediaSessionCallback().getPlaybackTimer();

			if (time > 0) {
				int h = time / 3600;
				int m = (time - h * 3600) / 60;
				applyIntPref(H, h);
				applyIntPref(M, m);
			}

			set.addIntPref(o -> {
				o.title = R.string.hours;
				o.store = this;
				o.pref = H;
				o.seekMin = 0;
				o.seekMax = 12;
			});
			set.addIntPref(o -> {
				o.title = R.string.minutes;
				o.store = this;
				o.pref = M;
				o.seekMin = 0;
				o.seekMax = 60;
				o.seekScale = 5;
			});

			set.addToMenu(b, true);
			b.setCloseHandlerHandler(this);
			changed = false;
			startTimer();
		}

		@Override
		public void applyIntPref(boolean removeDefault, Pref<? extends IntSupplier> pref, int value) {
			super.applyIntPref(removeDefault, pref, value);
			changed = true;
			startTimer();
		}

		@Override
		public void menuClosed(OverlayMenu menu) {
			closed = true;
			if (!changed) return;
			int h = getIntPref(H);
			int m = getIntPref(M);
			activity.getMediaSessionCallback().setPlaybackTimer(h * 3600 + m * 60);
			checkPlaybackTimer(activity);
		}

		private void startTimer() {
			activity.postDelayed(() -> {
				if (!closed) getMenu(getActivity()).hide();
			}, 60000);
		}
	}

	private int getStartDelay() {
		return (prefs == null) ? 0 : prefs.getVideoControlStartDelayPref() * 1000;
	}

	private int getTouchDelay() {
		return (prefs == null) ? 5000 : prefs.getVideoControlTouchDelayPref() * 1000;
	}

	private int getSeekDelay() {
		return (prefs == null) ? 3000 : prefs.getVideoControlSeekDelayPref() * 1000;
	}

	private void cancelHide() {
		hideTask.cancel();
		hideTask = Cancellable.CANCELED;
		hideTimer = null;
	}

	private void scheduleHide(MainActivityDelegate activity, int delay, boolean seekMode, View... views) {
		cancelHide();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && delay > 0) {
			var accessibility = getContext().getSystemService(AccessibilityManager.class);
			if (accessibility != null) {
				delay = accessibility.getRecommendedTimeoutMillis(delay,
						AccessibilityManager.FLAG_CONTENT_ICONS | AccessibilityManager.FLAG_CONTENT_CONTROLS);
			}
		}
		hideTimer = new HideTimer(activity, delay, seekMode, views);
		hideTask = activity.postDelayed(hideTimer, delay);
	}

	private final class HideTimer implements Runnable {
		final MainActivityDelegate activity;
		final int delay;
		final boolean seekMode;
		final View[] views;

		HideTimer(MainActivityDelegate activity, int delay, boolean seekMode, View... views) {
			this.activity = activity;
			this.delay = delay;
			this.seekMode = seekMode;
			this.views = views;
		}

		@Override
		public void run() {
			if ((hideTimer != this) || ((mask & MASK_VIDEO_MODE) == 0)) return;

			if (ControlPanelView.this.hasFocus()) {
				hideTask = activity.postDelayed(this, Math.max(250, delay));
				return;
			}

			hideTimer = null;
			hideTask = Cancellable.CANCELED;
			if (activity.getPrefs().getSysBarsOnVideoTouchPref()) activity.setFullScreen(true);
			ControlPanelView.super.setVisibility(GONE);

			for (View v : views) {
				if (v != null) v.setVisibility(GONE);
			}
		}
	}
}
