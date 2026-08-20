package com.opencode.eclipse.ui;

import java.util.function.IntConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Sash;

/**
 * The attachment chip row above the prompt.
 *
 * <p>Its whole reason to exist as a widget is the height policy: with many editor tabs open the
 * chips previously wrapped into as many rows as they needed and pushed the conversation off
 * screen. Here the chips live in a {@link ScrolledComposite} that is exactly one chip row tall by
 * default; further chips scroll, and the {@link Sash} on top lets the user drag the area taller
 * up to {@link #MAX_HEIGHT}. Chosen heights are reported through the constructor's callback so
 * the caller can persist them.
 *
 * <p>Chips themselves are built by the owner into {@link #chips()}, which must call
 * {@link #chipsChanged()} afterwards.
 */
final class AttachedFilesBar extends Composite {
	/** Upper bound for the resizable area, so dragging can never swallow the view. */
	static final int MAX_HEIGHT = 240;
	/** Row height assumed before any chip exists to measure. */
	private static final int DEFAULT_ROW = 26;

	private final Sash sash;
	private final ScrolledComposite scroll;
	private final Composite chips;
	private final IntConsumer onHeightChosen;
	private int height;

	AttachedFilesBar(Composite parent, int initialHeight, IntConsumer onHeightChosen) {
		super(parent, SWT.NONE);
		this.onHeightChosen = onHeightChosen;
		this.height = initialHeight;
		GridLayout layout = new GridLayout(1, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		layout.verticalSpacing = 0;
		setLayout(layout);
		setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		sash = new Sash(this, SWT.HORIZONTAL | SWT.SMOOTH);
		sash.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		sash.setToolTipText("Drag to resize the attached files area");
		sash.addListener(SWT.Selection, e -> {
			e.doit = false;
			setHeight(height + sash.getBounds().y - e.y);
			if (e.detail != SWT.DRAG) onHeightChosen.accept(height);
		});

		scroll = new ScrolledComposite(this, SWT.V_SCROLL);
		scroll.setExpandHorizontal(true);
		GridData scrollData = new GridData(SWT.FILL, SWT.TOP, true, false);
		scrollData.heightHint = initialHeight;
		scroll.setLayoutData(scrollData);
		scroll.addListener(SWT.Resize, e -> updateScrollSize());

		chips = new Composite(scroll, SWT.NONE);
		RowLayout row = new RowLayout(SWT.HORIZONTAL);
		row.center = true;
		row.marginTop = 0;
		row.marginBottom = 0;
		row.wrap = true;
		chips.setLayout(row);
		scroll.setContent(chips);
	}

	/** Parent for the chip controls; the owner disposes and rebuilds these itself. */
	Composite chips() {
		return chips;
	}

	/** Re-measure after the owner rebuilt the chips. */
	void chipsChanged() {
		chips.layout(true, true);
		setHeight(height > 0 ? height : oneRow());
	}

	int height() {
		return height;
	}

	/** Clamped to between one chip row and {@link #MAX_HEIGHT}. */
	void setHeight(int wanted) {
		height = Math.max(oneRow(), Math.min(wanted, MAX_HEIGHT));
		((GridData) scroll.getLayoutData()).heightHint = height;
		getParent().layout(true, true);
		updateScrollSize();
	}

	/** True when the chips do not fit the current height and the area scrolls instead of growing. */
	boolean scrolls() {
		return scroll.getMinHeight() > scroll.getClientArea().height;
	}

	/**
	 * True when the area really occupies no more than the chosen height plus its drag handle.
	 *
	 * <p>Deliberately not phrased as "shorter than the conversation": the chat view is often docked
	 * into a short stack where the conversation is a few pixels tall, and the chip area is still
	 * behaving correctly there.
	 */
	boolean withinBounds() {
		return height <= MAX_HEIGHT && getSize().y <= height + sash.getSize().y;
	}

	/** Height of the visible chip viewport, excluding anything scrolled out of sight. */
	int viewportHeight() {
		return scroll.getClientArea().height;
	}

	/** Diagnostics for the height-policy test. */
	String geometry() {
		return "height=" + height + " minHeight=" + scroll.getMinHeight()
				+ " clientHeight=" + scroll.getClientArea().height
				+ " clientWidth=" + scroll.getClientArea().width
				+ " chips=" + chips.getSize();
	}

	/** Tallest chip: the natural height of a single attachment row. */
	private int oneRow() {
		int tallest = 0;
		for (Control child : chips.getChildren()) {
			tallest = Math.max(tallest, child.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
		}
		return tallest > 0 ? tallest : DEFAULT_ROW;
	}

	/**
	 * Re-wrap the chips at the current width so the scrollbar reflects the real content height.
	 *
	 * <p>The content is sized explicitly: {@code ScrolledComposite} only stretches it in the
	 * directions it was told to expand, and vertical expansion is off here on purpose (that is what
	 * makes the overflow scroll). Without this the chip composite keeps its initial zero height and
	 * nothing paints at all.
	 */
	private void updateScrollSize() {
		if (scroll.isDisposed()) return;
		int width = scroll.getClientArea().width;
		if (width <= 0) return;
		Point size = chips.computeSize(width, SWT.DEFAULT);
		chips.setSize(size);
		scroll.setMinSize(size);
	}
}
