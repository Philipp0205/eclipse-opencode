package com.opencode.eclipse.ui;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Label;

/** Eight-frame spinning-circle animation, matching Copilot's spinner lifecycle. */
final class SpinnerAnimator {
	private static final int FRAMES = 8;
	private final Label target;
	private Image image;
	private Runnable animation;

	SpinnerAnimator(Label target) {
		this.target = target;
		target.addDisposeListener(e -> stop());
	}

	void start() {
		stop();
		animation = new Runnable() {
			private int frame = 1;

			@Override
			public void run() {
				if (animation != this || target.isDisposed()) {
					return;
				}
				if (image != null && !image.isDisposed()) {
					image.dispose();
				}
				var stream = SpinnerAnimator.class.getClassLoader()
						.getResourceAsStream("icons/spinner/" + frame + ".png");
				if (stream != null) {
					try (stream) {
						image = new Image(target.getDisplay(), stream);
						target.setImage(image);
					} catch (java.io.IOException ignored) {
						// Missing frame: next timer tick retries.
					}
				}
				frame = frame % FRAMES + 1;
				target.getDisplay().timerExec(100, this);
			}
		};
		target.getDisplay().timerExec(0, animation);
	}

	void stop() {
		if (animation != null && !target.isDisposed()) {
			target.getDisplay().timerExec(-1, animation);
		}
		animation = null;
		if (!target.isDisposed()) {
			target.setImage(null);
		}
		if (image != null && !image.isDisposed()) {
			image.dispose();
		}
		image = null;
	}
}
