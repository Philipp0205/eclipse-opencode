package com.opencode.eclipse.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;

/** Search field plus fuzzy-filtered model list, anchored below the model button. */
final class ModelPicker {
	private Shell popup;
	private Listener outsideClickFilter;

	/** Clicking the anchor toggles the popup; only one popup can exist per picker. */
	void toggle(Button anchor, List<String> models, Consumer<String> onSelect) {
		toggle(anchor, models, Function.identity(), "Search models", onSelect);
	}

	<T> void toggle(Button anchor, List<T> items, Function<T, String> label,
			String searchMessage, Consumer<T> onSelect) {
		if (isOpen()) {
			close();
			return;
		}

		popup = new Shell(anchor.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.BORDER);
		popup.setLayout(new GridLayout(1, false));

		Text search = new Text(popup, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
		search.setMessage(searchMessage);
		search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		org.eclipse.swt.widgets.List results = new org.eclipse.swt.widgets.List(
				popup, SWT.SINGLE | SWT.V_SCROLL | SWT.LEFT);
		GridData listData = new GridData(SWT.FILL, SWT.FILL, true, true);
		listData.heightHint = 260;
		listData.widthHint = 420;
		results.setLayoutData(listData);

		List<T> matches = new java.util.ArrayList<>();
		Runnable refresh = () -> {
			matches.clear();
			matches.addAll(ModelSearch.filter(items, search.getText(), label));
			results.setItems(matches.stream().map(label).toArray(String[]::new));
			if (!matches.isEmpty()) results.select(0);
		};
		Runnable commit = () -> {
			int i = results.getSelectionIndex();
			if (i >= 0) {
				T selected = matches.get(i);
				close();
				onSelect.accept(selected);
			}
		};

		search.addModifyListener(e -> refresh.run());
		search.addListener(SWT.DefaultSelection, e -> commit.run());
		search.addListener(SWT.KeyDown, e -> {
			int count = results.getItemCount();
			if (count == 0) return;
			int current = results.getSelectionIndex();
			if (e.keyCode == SWT.ARROW_DOWN) {
				results.select(Math.min(current + 1, count - 1));
				e.doit = false;
			} else if (e.keyCode == SWT.ARROW_UP) {
				results.select(Math.max(current - 1, 0));
				e.doit = false;
			} else if (e.character == SWT.CR || e.character == SWT.LF) {
				commit.run();
				e.doit = false;
			}
		});
		// Commit before GTK deactivates/disposes the popup.
		results.addListener(SWT.MouseDown, e -> commit.run());
		results.addListener(SWT.DefaultSelection, e -> commit.run());
		results.addListener(SWT.KeyDown, e -> {
			if (e.character != 0 && !Character.isISOControl(e.character)) {
				search.setFocus();
				search.append(String.valueOf(e.character));
			}
		});
		popup.addListener(SWT.Traverse, e -> {
			if (e.detail == SWT.TRAVERSE_ESCAPE) {
				close();
				e.doit = false;
			}
		});
		popup.addListener(SWT.Deactivate, e -> {
			// Keep it alive until the anchor's selection event toggles it closed.
			if (!cursorInside(anchor)) close();
		});
		popup.addDisposeListener(e -> removeOutsideClickFilter(anchor));

		outsideClickFilter = event -> {
			if (!isOpen() || insidePopup(event.widget) || event.widget == anchor) return;
			close();
		};
		anchor.getDisplay().addFilter(SWT.MouseDown, outsideClickFilter);

		refresh.run();
		popup.pack();
		Point below = anchor.toDisplay(0, anchor.getSize().y);
		popup.setLocation(below);
		popup.open();
		search.setFocus();
	}

	boolean isOpen() {
		return popup != null && !popup.isDisposed() && popup.isVisible();
	}

	void close() {
		if (popup != null && !popup.isDisposed()) popup.dispose();
		popup = null;
	}

	private boolean insidePopup(Widget widget) {
		if (!(widget instanceof Control control) || popup == null) return false;
		for (Control current = control; current != null; current = current.getParent()) {
			if (current == popup) return true;
		}
		return false;
	}

	private void removeOutsideClickFilter(Button anchor) {
		if (outsideClickFilter != null && !anchor.getDisplay().isDisposed()) {
			anchor.getDisplay().removeFilter(SWT.MouseDown, outsideClickFilter);
		}
		outsideClickFilter = null;
		popup = null;
	}

	private static boolean cursorInside(Control control) {
		Point cursor = control.getDisplay().getCursorLocation();
		Point topLeft = control.toDisplay(0, 0);
		Rectangle bounds = new Rectangle(topLeft.x, topLeft.y,
				control.getSize().x, control.getSize().y);
		return bounds.contains(cursor);
	}
}
