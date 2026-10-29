package com.deathreplay;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Plays back a death: the saved video next to what happened on each tick.
 * Without a video it still works as a tick-by-tick stepper.
 */
@Slf4j
class ReplayViewer extends JFrame
{
	private static final String[] SPEED_LABELS = {"0.25x", "0.5x", "1x", "2x"};
	private static final double[] SPEEDS = {0.25, 0.5, 1, 2};
	private static final Color HP_COLOR = new Color(74, 186, 89);
	private static final Color DAMAGE_COLOR = new Color(214, 64, 52);
	private static final Color DEATH_ROW_COLOR = new Color(90, 30, 30);

	private final DeathRecord death;
	private final List<TickRecord> ticks;
	private final List<ClipFrame> frames;
	private final List<byte[]> frameData;
	private final String noVideoMessage;

	private final long startMillis;
	private final long endMillis;
	private long playhead;
	private int frameIndex = -1;
	private int decodedIndex = -1;
	private BufferedImage image;
	private boolean playing;
	private double speed = 1;
	private long lastNanos;
	private int shownTick = Integer.MIN_VALUE;
	private boolean updatingList;

	private final Timer timer;
	private final VideoCanvas canvas = new VideoCanvas();
	private final TickStrip strip = new TickStrip();
	private final JLabel details = new JLabel();
	private final DefaultListModel<TickRecord> tickModel = new DefaultListModel<>();
	private final JList<TickRecord> tickList = new JList<>(tickModel);
	private final JButton playButton = new JButton("Play");
	private final JLabel timeLabel = new JLabel();

	/**
	 * @param frameData JPEG bytes for each clip frame, or null when there is no video
	 * @param noVideoMessage shown in place of the video when frameData is null
	 * @param onSaveVideo asks where to save a copy of the .avi, given this window as the dialog parent, or null
	 */
	ReplayViewer(DeathRecord death, List<byte[]> frameData, String noVideoMessage, Consumer<Component> onSaveVideo)
	{
		super("Death Replay - " + (death.getLastTarget() != null ? death.getLastTarget() : "Unknown"));
		this.death = death;
		this.ticks = death.getTicks();
		boolean hasVideo = frameData != null && death.getClip() != null && !frameData.isEmpty();
		this.frames = hasVideo ? death.getClip().getFrames() : Collections.emptyList();
		this.frameData = hasVideo ? frameData : null;
		this.noVideoMessage = noVideoMessage;

		if (hasVideo)
		{
			startMillis = frames.get(0).getTimeMillis();
			endMillis = frames.get(frames.size() - 1).getTimeMillis();
		}
		else if (!ticks.isEmpty())
		{
			startMillis = ticks.get(0).getTimeMillis();
			endMillis = ticks.get(ticks.size() - 1).getTimeMillis();
		}
		else
		{
			startMillis = endMillis = death.getTimeMillis();
		}
		playhead = startMillis;

		setIconImage(DeathReplayPanel.createIcon());
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		add(canvas, BorderLayout.CENTER);
		add(buildSide(), BorderLayout.EAST);
		add(buildBottom(onSaveVideo), BorderLayout.SOUTH);
		bindKeys();

		timer = new Timer(15, e -> onTimer());
		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosed(WindowEvent e)
			{
				timer.stop();
			}
		});

		setSize(1280, 780);
		setMinimumSize(new Dimension(900, 560));
		setLocationRelativeTo(null);
		update();
	}

	private JComponent buildSide()
	{
		details.setVerticalAlignment(JLabel.TOP);
		details.setForeground(Color.WHITE);
		details.setFont(FontManager.getRunescapeFont());
		details.setBorder(new EmptyBorder(10, 10, 10, 10));
		details.setOpaque(true);
		details.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		details.setPreferredSize(new Dimension(300, 230));

		for (TickRecord tick : ticks)
		{
			tickModel.addElement(tick);
		}
		tickList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		tickList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tickList.setCellRenderer(new TickRenderer());
		tickList.addListSelectionListener(e ->
		{
			if (!updatingList && !e.getValueIsAdjusting() && tickList.getSelectedValue() != null)
			{
				pause();
				seekTick(tickList.getSelectedValue().getTick());
			}
		});

		JLabel listTitle = new JLabel("Every tick (0.6s)");
		listTitle.setFont(FontManager.getRunescapeBoldFont());
		listTitle.setForeground(ColorScheme.BRAND_ORANGE);
		listTitle.setBorder(new EmptyBorder(8, 10, 4, 10));

		JScrollPane scroll = new JScrollPane(tickList);
		scroll.setBorder(BorderFactory.createEmptyBorder());

		JPanel list = new JPanel(new BorderLayout());
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.add(listTitle, BorderLayout.NORTH);
		list.add(scroll, BorderLayout.CENTER);

		JPanel side = new JPanel(new BorderLayout());
		side.setPreferredSize(new Dimension(300, 0));
		side.setBackground(ColorScheme.DARK_GRAY_COLOR);
		side.add(details, BorderLayout.NORTH);
		side.add(list, BorderLayout.CENTER);
		return side;
	}

	private JComponent buildBottom(Consumer<Component> onSaveVideo)
	{
		JButton back = new JButton("< Tick");
		back.setToolTipText("Previous tick (Left arrow)");
		back.addActionListener(e ->
		{
			pause();
			stepTick(-1);
		});
		JButton forward = new JButton("Tick >");
		forward.setToolTipText("Next tick (Right arrow)");
		forward.addActionListener(e ->
		{
			pause();
			stepTick(1);
		});
		playButton.setToolTipText("Play / pause (Space)");
		playButton.addActionListener(e -> togglePlay());

		JComboBox<String> speedBox = new JComboBox<>(SPEED_LABELS);
		speedBox.setSelectedIndex(2);
		speedBox.addActionListener(e -> speed = SPEEDS[speedBox.getSelectedIndex()]);

		timeLabel.setForeground(Color.WHITE);
		timeLabel.setFont(FontManager.getRunescapeFont());

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
		controls.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		controls.add(back);
		controls.add(playButton);
		controls.add(forward);
		controls.add(new JLabel("Speed"));
		controls.add(speedBox);
		controls.add(timeLabel);
		if (onSaveVideo != null)
		{
			JButton save = new JButton("Save video...");
			save.addActionListener(e -> onSaveVideo.accept(this));
			controls.add(save);
		}

		strip.setPreferredSize(new Dimension(0, 70));

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(strip, BorderLayout.CENTER);
		bottom.add(controls, BorderLayout.SOUTH);
		return bottom;
	}

	private void bindKeys()
	{
		JComponent root = getRootPane();
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "play");
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "back");
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "forward");
		root.getActionMap().put("play", action(this::togglePlay));
		root.getActionMap().put("back", action(() ->
		{
			pause();
			stepTick(-1);
		}));
		root.getActionMap().put("forward", action(() ->
		{
			pause();
			stepTick(1);
		}));
	}

	private static AbstractAction action(Runnable runnable)
	{
		return new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				runnable.run();
			}
		};
	}

	private void togglePlay()
	{
		if (playing)
		{
			pause();
			return;
		}

		if (playhead >= endMillis)
		{
			playhead = startMillis;
		}
		playing = true;
		lastNanos = System.nanoTime();
		playButton.setText("Pause");
		timer.start();
	}

	private void pause()
	{
		playing = false;
		playButton.setText("Play");
		timer.stop();
	}

	private void onTimer()
	{
		long now = System.nanoTime();
		playhead += (long) ((now - lastNanos) / 1_000_000.0 * speed);
		lastNanos = now;
		if (playhead >= endMillis)
		{
			playhead = endMillis;
			pause();
		}
		update();
	}

	private void stepTick(int direction)
	{
		int index = tickIndex(currentTick());
		int next = Math.max(0, Math.min(ticks.size() - 1, index + direction));
		if (next >= 0 && next < ticks.size())
		{
			seekTick(ticks.get(next).getTick());
		}
	}

	private void seekTick(int tick)
	{
		if (frameData != null)
		{
			playhead = endMillis;
			for (ClipFrame frame : frames)
			{
				if (frame.getTick() >= tick)
				{
					playhead = frame.getTimeMillis();
					break;
				}
			}
		}
		else
		{
			int index = tickIndex(tick);
			if (index >= 0)
			{
				playhead = ticks.get(index).getTimeMillis();
			}
		}
		update();
	}

	private void update()
	{
		if (frameData != null)
		{
			frameIndex = frameAt(playhead);
			if (frameIndex != decodedIndex)
			{
				image = decode(frameData.get(frameIndex));
				decodedIndex = frameIndex;
				canvas.repaint();
			}
		}

		int tick = currentTick();
		timeLabel.setText(String.format("%.1fs / %.1fs", (playhead - startMillis) / 1000.0, (endMillis - startMillis) / 1000.0));
		strip.repaint();

		if (tick == shownTick)
		{
			return;
		}
		shownTick = tick;

		int index = tickIndex(tick);
		TickRecord record = index >= 0 ? ticks.get(index) : null;
		details.setText(record == null ? "" : detailsHtml(record));

		updatingList = true;
		if (index >= 0)
		{
			tickList.setSelectedIndex(index);
			tickList.ensureIndexIsVisible(index);
		}
		else
		{
			tickList.clearSelection();
		}
		updatingList = false;
	}

	private int currentTick()
	{
		if (frameData != null)
		{
			return frames.get(Math.max(0, frameIndex)).getTick();
		}

		int tick = ticks.isEmpty() ? death.getDeathTick() : ticks.get(0).getTick();
		for (TickRecord record : ticks)
		{
			if (record.getTimeMillis() > playhead)
			{
				break;
			}
			tick = record.getTick();
		}
		return tick;
	}

	/**
	 * Index of the last tick at or before {@code tick}, or -1.
	 */
	private int tickIndex(int tick)
	{
		int found = -1;
		for (int i = 0; i < ticks.size(); i++)
		{
			if (ticks.get(i).getTick() > tick)
			{
				break;
			}
			found = i;
		}
		return found;
	}

	/**
	 * Index of the last frame captured at or before {@code millis}.
	 */
	private int frameAt(long millis)
	{
		int low = 0;
		int high = frames.size() - 1;
		while (low < high)
		{
			int mid = (low + high + 1) >>> 1;
			if (frames.get(mid).getTimeMillis() <= millis)
			{
				low = mid;
			}
			else
			{
				high = mid - 1;
			}
		}
		return low;
	}

	static BufferedImage decode(byte[] jpeg)
	{
		try
		{
			// memory-backed so ImageIO doesn't create temp files; ImageIO.read closes it
			return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg)));
		}
		catch (IOException e)
		{
			log.debug("Unable to decode frame", e);
			return null;
		}
	}

	private String when(int tick)
	{
		int offset = tick - death.getDeathTick();
		if (offset == 0)
		{
			return "Death";
		}
		return (offset > 0 ? "+" : "-") + Names.seconds(Math.abs(offset));
	}

	private String detailsHtml(TickRecord tick)
	{
		int offset = tick.getTick() - death.getDeathTick();
		String heading;
		if (offset == 0)
		{
			heading = "Death tick";
		}
		else if (offset < 0)
		{
			heading = Names.seconds(-offset) + " before death (" + (-offset) + " tick" + (offset == -1 ? "" : "s") + ")";
		}
		else
		{
			heading = Names.seconds(offset) + " after death";
		}

		StringBuilder html = new StringBuilder("<html>");
		html.append("<b>").append(heading).append("</b><br><br>");
		html.append("HP <b>").append(tick.getHp()).append('/').append(tick.getMaxHp()).append("</b>")
			.append(" &nbsp; Prayer ").append(tick.getPrayer()).append('/').append(tick.getMaxPrayer())
			.append(" &nbsp; Spec ").append(tick.getSpecialAttack()).append("%<br><br>");

		html.append("<font color='#e0736a'>");
		if (tick.getHits().isEmpty())
		{
			html.append("No hits this tick");
		}
		else
		{
			html.append("Hits: ").append(tick.getHits().stream()
				.map(h -> h.getAmount() + " " + h.getKind().getLabel().toLowerCase())
				.collect(Collectors.joining(", ")));
		}
		html.append("</font><br>");

		html.append("<font color='#bbbbbb'>Prayers: ")
			.append(escape(tick.getPrayers().isEmpty() ? "none" : String.join(", ", tick.getPrayers())))
			.append("</font><br>");

		if (!tick.getConsumed().isEmpty())
		{
			html.append("<font color='#8fd18f'>Ate/drank: ").append(escape(String.join(", ", tick.getConsumed()))).append("</font><br>");
		}
		if (tick.getTarget() != null)
		{
			html.append("<font color='#bbbbbb'>Your target: ").append(escape(tick.getTarget())).append("</font>");
		}
		return html.append("</html>").toString();
	}

	private static String escape(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private class TickRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused)
		{
			super.getListCellRendererComponent(list, value, index, selected, focused);
			TickRecord tick = (TickRecord) value;

			StringBuilder text = new StringBuilder("<html><b>").append(when(tick.getTick())).append("</b> &nbsp;HP ")
				.append(tick.getHp());
			if (!tick.getHits().isEmpty())
			{
				text.append(" &nbsp;<font color='#e0736a'>").append(tick.getHits().stream()
					.map(h -> h.getKind() == HitKind.DAMAGE || h.getKind() == HitKind.BLOCK
						? String.valueOf(h.getAmount())
						: h.getAmount() + " " + h.getKind().getLabel().toLowerCase())
					.collect(Collectors.joining(", "))).append("</font>");
			}
			if (!tick.getConsumed().isEmpty())
			{
				text.append(" &nbsp;<font color='#8fd18f'>").append(escape(String.join(", ", tick.getConsumed()))).append("</font>");
			}
			setText(text.append("</html>").toString());
			setFont(FontManager.getRunescapeSmallFont());
			setBorder(new EmptyBorder(3, 10, 3, 10));
			setForeground(Color.WHITE);
			if (selected)
			{
				setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
			}
			else if (tick.getTick() == death.getDeathTick())
			{
				setBackground(DEATH_ROW_COLOR);
			}
			else
			{
				setBackground(index % 2 == 0 ? ColorScheme.DARK_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
			}
			return this;
		}
	}

	private class VideoCanvas extends JComponent
	{
		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, getWidth(), getHeight());

			if (image == null)
			{
				g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g.setFont(FontManager.getRunescapeFont());
				String message = frameData == null ? noVideoMessage : "Unable to show this frame";
				int width = g.getFontMetrics().stringWidth(message);
				g.drawString(message, (getWidth() - width) / 2, getHeight() / 2);
				g.dispose();
				return;
			}

			double scale = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
			if (scale >= 1)
			{
				// whole-pixel enlargement keeps the game's pixel art sharp
				scale = Math.floor(scale);
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			}
			else
			{
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			}
			int width = (int) (image.getWidth() * scale);
			int height = (int) (image.getHeight() * scale);
			g.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
			g.dispose();
		}
	}

	/**
	 * One column per tick: HP line, red damage bars, the death marked, and the
	 * current position highlighted. Click or drag to jump.
	 */
	private class TickStrip extends JComponent
	{
		private static final int PAD = 6;

		TickStrip()
		{
			MouseAdapter seek = new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					pause();
					seekToX(e.getX());
				}

				@Override
				public void mouseDragged(MouseEvent e)
				{
					seekToX(e.getX());
				}
			};
			addMouseListener(seek);
			addMouseMotionListener(seek);
			setToolTipText("Click or drag to jump to a tick");
		}

		private double columnWidth()
		{
			return (getWidth() - PAD * 2) / (double) Math.max(1, ticks.size());
		}

		private void seekToX(int x)
		{
			if (ticks.isEmpty())
			{
				return;
			}
			int index = (int) ((x - PAD) / columnWidth());
			index = Math.max(0, Math.min(ticks.size() - 1, index));
			seekTick(ticks.get(index).getTick());
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, w, h);

			int n = ticks.size();
			int maxHp = ticks.stream().mapToInt(TickRecord::getMaxHp).max().orElse(0);
			if (n == 0 || maxHp <= 0)
			{
				g.dispose();
				return;
			}

			double column = columnWidth();
			double scale = (h - PAD * 2) / (double) maxHp;
			int current = tickIndex(currentTick());

			for (int i = 0; i < n; i++)
			{
				TickRecord tick = ticks.get(i);
				int x = (int) Math.round(PAD + i * column);
				int cw = Math.max(1, (int) Math.round(column) - 1);

				if (i == current)
				{
					g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g.fillRect(x, 0, cw, h);
				}
				if (tick.getTick() == death.getDeathTick())
				{
					g.setColor(DEATH_ROW_COLOR);
					g.fillRect(x, 0, cw, h);
				}

				int damage = Math.min(tick.damageTaken(), maxHp);
				if (damage > 0)
				{
					int barHeight = (int) Math.round(damage * scale);
					g.setColor(DAMAGE_COLOR);
					g.fillRect(x + cw / 4, h - PAD - barHeight, Math.max(1, cw / 2), barHeight);
				}
			}

			g.setColor(HP_COLOR);
			g.setStroke(new BasicStroke(2f));
			for (int i = 1; i < n; i++)
			{
				int x1 = (int) Math.round(PAD + (i - 0.5) * column);
				int x2 = (int) Math.round(PAD + (i + 0.5) * column);
				int y1 = (int) Math.round(h - PAD - ticks.get(i - 1).getHp() * scale);
				int y2 = (int) Math.round(h - PAD - ticks.get(i).getHp() * scale);
				g.drawLine(x1, y1, x2, y2);
			}
			g.dispose();
		}
	}
}
