package com.opencode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * The attachment area must stay one chip row tall however many tabs are open, and — the part an
 * earlier version of this test missed by only checking heights — the chips must actually be laid
 * out and visible inside it.
 */
public final class AttachedFilesBarTest {
	public static void main(String[] args) {
		Display display = new Display();
		Shell shell = new Shell(display);
		shell.setLayout(new GridLayout(1, false));
		shell.setSize(320, 400);

		int[] persisted = { -1 };
		AttachedFilesBar bar = new AttachedFilesBar(shell, 0, height -> persisted[0] = height);
		shell.open();
		pump(display);

		Control[] single = addChips(bar, 1);
		bar.chipsChanged();
		pump(display);
		int oneRow = bar.height();
		assert oneRow > 0 && oneRow < AttachedFilesBar.MAX_HEIGHT : oneRow;
		assert bar.chips().getSize().y > 0 : "the chip composite was never sized, so nothing paints";
		assert visible(bar, single[0]) : "the only chip must be visible: " + single[0].getBounds();

		// Many chips at a narrow width would wrap into several rows; the area must not grow, the
		// first chip stays visible, and the overflow moves below the visible area instead.
		Control[] many = addChips(bar, 30);
		bar.chipsChanged();
		pump(display);
		assert bar.height() == oneRow : "30 chips changed the height to " + bar.height() + " (one row is " + oneRow + ")";
		assert visible(bar, many[0]) : "the first chip must stay visible: " + many[0].getBounds();
		assert !visible(bar, many[29]) : "chips beyond the first row must scroll out of view, not enlarge the area";
		assert bar.scrolls() : "the overflow must be reachable by scrolling: " + bar.geometry();
		int visibleInOneRow = visibleCount(bar, many);
		assert visibleInOneRow >= 1 && visibleInOneRow < many.length : visibleInOneRow;
		assert bar.withinBounds() : "the widget grew past its own height: " + bar.geometry();

		// The user can drag it taller, which brings previously hidden chips into view.
		bar.setHeight(oneRow * 4);
		pump(display);
		assert bar.height() == oneRow * 4 : bar.height();
		assert visibleCount(bar, many) > visibleInOneRow
				: "a taller area must reveal more chips, still showing " + visibleInOneRow;

		// Clamped at both ends: never below one row, never above the maximum.
		bar.setHeight(1);
		pump(display);
		assert bar.height() == oneRow : bar.height();
		bar.setHeight(AttachedFilesBar.MAX_HEIGHT * 10);
		pump(display);
		assert bar.height() == AttachedFilesBar.MAX_HEIGHT : bar.height();

		// A restored height is honoured instead of collapsing back to one row.
		AttachedFilesBar restored = new AttachedFilesBar(shell, 120, height -> { });
		Control[] restoredChips = addChips(restored, 3);
		restored.chipsChanged();
		pump(display);
		assert restored.height() == 120 : restored.height();
		assert visible(restored, restoredChips[2]) : restoredChips[2].getBounds();

		assert persisted[0] == -1 : "only a finished sash drag may report a height";
		shell.dispose();
		display.dispose();
		System.out.println("ATTACHED FILES BAR OK");
	}

	private static int visibleCount(AttachedFilesBar bar, Control[] chips) {
		int count = 0;
		for (Control chip : chips) if (visible(bar, chip)) count++;
		return count;
	}

	/** True when the chip has a real size and sits inside the scrolled viewport. */
	private static boolean visible(AttachedFilesBar bar, Control chip) {
		var bounds = chip.getBounds();
		return bounds.height > 0 && bounds.width > 0 && bounds.y + bounds.height <= bar.viewportHeight();
	}

	private static Control[] addChips(AttachedFilesBar bar, int count) {
		for (var child : bar.chips().getChildren()) child.dispose();
		Control[] chips = new Control[count];
		for (int i = 0; i < count; i++) {
			Button chip = new Button(bar.chips(), SWT.PUSH | SWT.FLAT | SWT.BORDER);
			chip.setText("Attachment" + i + ".java  ×");
			chips[i] = chip;
		}
		return chips;
	}

	private static void pump(Display display) {
		long end = System.currentTimeMillis() + 300;
		while (System.currentTimeMillis() < end) if (!display.readAndDispatch()) display.sleep();
	}
}
