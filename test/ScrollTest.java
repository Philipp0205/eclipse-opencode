import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Headless-ish SWT check: many bubbles must make the ScrolledComposite scrollable. */
public class ScrollTest {
	public static void main(String[] a) {
		Display d = new Display();
		Shell sh = new Shell(d);
		sh.setLayout(new GridLayout(1,false));
		sh.setSize(400, 300);

		ScrolledComposite scroll = new ScrolledComposite(sh, SWT.V_SCROLL | SWT.BORDER);
		scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		scroll.setExpandHorizontal(true);
		scroll.setExpandVertical(true);
		Composite messages = new Composite(scroll, SWT.NONE);
		messages.setLayout(new GridLayout(1,false));
		scroll.setContent(messages);

		StyledText last = null;
		for (int i=0;i<40;i++){
			StyledText t = new StyledText(messages, SWT.WRAP|SWT.READ_ONLY|SWT.MULTI);
			t.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
			t.setText(("Message number "+i+" — some long text that wraps across the width of the panel. ").repeat(12));
			last = t;
		}

		// exact resizeContent logic under test:
		int w = scroll.getClientArea().width;
		messages.layout(true, true);
		Point size = messages.computeSize(Math.max(1, w), SWT.DEFAULT, true);
		messages.setSize(size);
		scroll.setMinSize(size);

		sh.open();
		// pump events briefly so layout settles, then assert scrollable
		long end = System.currentTimeMillis()+800;
		while (System.currentTimeMillis()<end) { if(!d.readAndDispatch()) d.sleep(); }
		messages.layout(true, true);
		size = messages.computeSize(Math.max(1, scroll.getClientArea().width), SWT.DEFAULT, true);
		messages.setSize(size);
		scroll.setMinSize(size);

		int contentH = messages.getSize().y;
		int lastBottom = last.getBounds().y + last.getBounds().height;
		int viewH = scroll.getClientArea().height;
		var vbar = scroll.getVerticalBar();
		boolean scrollable = vbar!=null && vbar.getMaximum() > vbar.getThumb();
		System.out.println("contentH="+contentH+" lastBottom="+lastBottom+" viewH="+viewH+" vbarMax="+(vbar!=null?vbar.getMaximum():-1)+" thumb="+(vbar!=null?vbar.getThumb():-1));
		System.out.println("scrollable="+scrollable);
		// can we actually move?
		scroll.setOrigin(0, contentH);
		int origin = scroll.getOrigin().y;
		System.out.println("originAfterScroll="+origin);
		sh.dispose(); d.dispose();
		if (!(contentH>viewH && lastBottom<=contentH && scrollable && origin>0))
			throw new AssertionError("NOT scrollable");
		System.out.println("SCROLL OK");
	}
}
