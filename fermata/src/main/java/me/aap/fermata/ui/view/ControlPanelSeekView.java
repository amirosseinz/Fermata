package me.aap.fermata.ui.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import me.aap.fermata.R;

/**
 * @author Andrey Pavlenko
 */
public class ControlPanelSeekView extends AppCompatSeekBar {
	private ConstraintSet constraints;
	private ConstraintSet constraintsNoSeek;
	private boolean twoRows = true;

	public ControlPanelSeekView(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public ControlPanelSeekView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	public void setEnabled(boolean enabled) {
		if (isEnabled() == enabled) return;
		super.setEnabled(enabled);
		updateLayout();
	}

	void updateLayout() {
		ControlPanelView p = getPanel();
		boolean showTwoRows = isEnabled() || p.hasVideoShortcuts();
		if (twoRows == showTwoRows) return;
		twoRows = showTwoRows;

		if (showTwoRows) {
			if (constraints == null) constraints = load(R.layout.control_panel_view);
			constraints.applyTo(p);
		} else {
			if (constraintsNoSeek == null) constraintsNoSeek = load(R.layout.control_panel_view2);
			constraintsNoSeek.applyTo(p);
		}

		p.computeSize();
	}

	boolean usesTwoRows() {
		return twoRows;
	}

	private ConstraintSet load(@LayoutRes int layout) {
		Context ctx = getContext();
		ConstraintLayout l = new ConstraintLayout(ctx);
		inflate(ctx, layout, l);
		ConstraintSet cs = new ConstraintSet();
		cs.clone(l);
		for (int id : new int[]{R.id.seek_bar, R.id.control_rw, R.id.control_ff,
				R.id.control_subtitles, R.id.control_fullscreen}) {
			cs.setVisibilityMode(id, ConstraintSet.VISIBILITY_MODE_IGNORE);
		}
		return cs;
	}

	private ControlPanelView getPanel() {
		return (ControlPanelView) getParent();
	}
}
