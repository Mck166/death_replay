package com.deathreplay;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel listing saved deaths, with a detail view for each one.
 * Only touched on the Swing event thread.
 */
class DeathReplayPanel extends PluginPanel
{
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, h:mm a");
	private static final Color HP_COLOR = new Color(74, 186, 89);
	private static final Color DAMAGE_COLOR = new Color(214, 64, 52);
	private static final Color DEATH_COLOR = new Color(255, 255, 255, 120);
	private static final int TEXT_WIDTH = 190;

	private final Consumer<DeathRecord> onDelete;
	private final Consumer<DeathRecord> onOpen;
	private final Runnable onOpenFolder;
	private final List<DeathRecord> deaths = new ArrayList<>();
	private final JPanel content = new JPanel(new DynamicGridLayout(0, 1, 0, 6));

	DeathReplayPanel(Consumer<DeathRecord> onDelete, Consumer<DeathRecord> onOpen, Runnable onOpenFolder)
	{
		this.onDelete = onDelete;
		this.onOpen = onOpen;
		this.onOpenFolder = onOpenFolder;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));
		add(content, BorderLayout.NORTH);
		showList();
	}

	void setDeaths(List<DeathRecord> records)
	{
		deaths.clear();
		deaths.addAll(records);
		showList();
	}

	void addDeath(DeathRecord record)
	{
		deaths.add(0, record);
		showList();
	}

	void removeDeath(String id)
	{
		if (deaths.removeIf(d -> d.getId().equals(id)))
		{
			showList();
		}
	}

	private void showList()
	{
		content.removeAll();
		content.add(title("Death Replay"));
		content.add(text("The last seconds before each death, saved automatically.", ColorScheme.LIGHT_GRAY_COLOR));

		if (deaths.isEmpty())
		{
			content.add(text("No deaths recorded yet.", ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else
		{
			JButton folder = new JButton("Open saved deaths folder");
			folder.addActionListener(e -> onOpenFolder.run());
			content.add(folder);
		}

		for (DeathRecord death : deaths)
		{
			content.add(deathCard(death));
		}

		refresh();
	}

	private void showDetail(DeathRecord death)
	{
		content.removeAll();

		JButton back = new JButton("< All deaths");
		back.addActionListener(e -> showList());
		content.add(back);

		content.add(title(heading(death)));
		content.add(text(formatDate(death.getTimeMillis()) + " - World " + death.getWorld(), ColorScheme.LIGHT_GRAY_COLOR));

		content.add(new HpChart(death));

		JButton watch = new JButton(death.getClip() != null ? "Watch replay" : "Step through ticks");
		watch.setToolTipText("Opens the video with what happened on every tick");
		watch.addActionListener(e -> onOpen.accept(death));
		content.add(watch);

		DeathSummary s = death.getSummary();
		if (s != null)
		{
			content.add(section("Summary"));
			content.add(text(s.headline() + ".", Color.WHITE));
			content.add(text(String.format("%d damage over %s from %d hits (%d were 0s).",
				s.getTotalDamage(), Names.seconds(s.getWindowTicks()), s.getHitsTaken(), s.getZeroHits()), Color.WHITE));
			if (s.getTicksFromHalfHp() >= 0)
			{
				content.add(text("Went from over half HP to dead in " + Names.seconds(s.getTicksFromHalfHp()) + ".", Color.WHITE));
			}
			if (!s.getDamageByKind().isEmpty())
			{
				content.add(text("By type: " + damageByKindText(s.getDamageByKind()), Color.WHITE));
			}
			content.add(text(String.format("Healed %d. Ate/drank %d time%s.",
				s.getHealed(), s.getItemsConsumed(), s.getItemsConsumed() == 1 ? "" : "s"), Color.WHITE));
		}

		if (!death.getGear().isEmpty())
		{
			content.add(section("Gear at death"));
			String gear = death.getGear().stream()
				.map(GearItem::getName)
				.collect(Collectors.joining(", "));
			content.add(text(gear, ColorScheme.LIGHT_GRAY_COLOR));
		}

		content.add(section("Timeline"));
		addTimeline(death);

		JButton delete = new JButton("Delete this death");
		delete.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this, "Delete this death?", "Death Replay",
				JOptionPane.YES_NO_OPTION);
			if (answer == JOptionPane.YES_OPTION)
			{
				deaths.remove(death);
				onDelete.accept(death);
				showList();
			}
		});
		content.add(delete);

		refresh();
	}

	/**
	 * Newest tick first, skipping ticks where nothing changed.
	 */
	private void addTimeline(DeathRecord death)
	{
		List<TickRecord> ticks = death.getTicks();
		boolean any = false;
		for (int i = ticks.size() - 1; i >= 0; i--)
		{
			TickRecord tick = ticks.get(i);
			TickRecord previous = i > 0 ? ticks.get(i - 1) : null;
			boolean prayersChanged = previous == null || !previous.getPrayers().equals(tick.getPrayers());
			if (tick.getHits().isEmpty() && tick.getConsumed().isEmpty() && !prayersChanged)
			{
				continue;
			}

			any = true;
			content.add(timelineRow(death, tick, prayersChanged));
		}

		if (!any)
		{
			content.add(text("Nothing happened in the saved window.", ColorScheme.MEDIUM_GRAY_COLOR));
		}
	}

	private JComponent timelineRow(DeathRecord death, TickRecord tick, boolean showPrayers)
	{
		int offset = tick.getTick() - death.getDeathTick();
		String when = offset == 0 ? "Death" : (offset > 0 ? "+" : "-") + Names.seconds(Math.abs(offset));

		StringBuilder html = new StringBuilder();
		html.append("<b>").append(when).append("</b> &nbsp;HP ")
			.append(tick.getHp()).append('/').append(tick.getMaxHp());

		if (!tick.getHits().isEmpty())
		{
			String hits = tick.getHits().stream()
				.map(h -> h.getKind() == HitKind.DAMAGE || h.getKind() == HitKind.BLOCK
					? String.valueOf(h.getAmount())
					: h.getAmount() + " " + h.getKind().getLabel().toLowerCase())
				.collect(Collectors.joining(", "));
			html.append("<br><font color='#e0736a'>Hit: ").append(hits).append("</font>");
		}
		if (!tick.getConsumed().isEmpty())
		{
			html.append("<br><font color='#8fd18f'>").append(escape(String.join(", ", tick.getConsumed()))).append("</font>");
		}
		if (showPrayers)
		{
			String prayers = tick.getPrayers().isEmpty() ? "No prayers" : String.join(", ", tick.getPrayers());
			html.append("<br><font color='#9a9a9a'>").append(escape(prayers)).append("</font>");
		}

		JLabel label = new JLabel(wrap(html.toString()));
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);

		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.add(label, BorderLayout.CENTER);
		return row;
	}

	private JComponent deathCard(DeathRecord death)
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(6, 8, 6, 8));
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel name = new JLabel(wrap("<b>" + escape(heading(death)) + "</b>"));
		name.setForeground(Color.WHITE);
		JLabel date = new JLabel(formatDate(death.getTimeMillis()));
		date.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		date.setFont(FontManager.getRunescapeSmallFont());

		String headline = death.getSummary() == null ? "" : death.getSummary().headline();
		if (death.getClip() != null)
		{
			headline += " - video saved";
		}
		JLabel recap = new JLabel(wrap(escape(headline)));
		recap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		recap.setFont(FontManager.getRunescapeSmallFont());

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(name, BorderLayout.NORTH);
		top.add(date, BorderLayout.SOUTH);
		card.add(top, BorderLayout.NORTH);
		card.add(recap, BorderLayout.SOUTH);

		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				showDetail(death);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return card;
	}

	private void refresh()
	{
		revalidate();
		repaint();
	}

	private static String heading(DeathRecord death)
	{
		return death.getLastTarget() != null ? death.getLastTarget() : "Unknown";
	}

	private static String formatDate(long millis)
	{
		return DATE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
	}

	private static JLabel title(String value)
	{
		JLabel label = new JLabel(wrap("<b>" + escape(value) + "</b>"));
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		return label;
	}

	private static JLabel section(String value)
	{
		JLabel label = new JLabel(value);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		return label;
	}

	private static JLabel text(String value, Color color)
	{
		JLabel label = new JLabel(wrap(escape(value)));
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}

	private static String wrap(String html)
	{
		return "<html><body style='width:" + TEXT_WIDTH + "px'>" + html + "</body></html>";
	}

	private static String escape(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * HP line with a red bar for each tick's damage. The dotted line marks the death.
	 */
	private static class HpChart extends JComponent
	{
		private final List<TickRecord> ticks;
		private final int deathTick;

		HpChart(DeathRecord death)
		{
			this.ticks = death.getTicks();
			this.deathTick = death.getDeathTick();
			setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 90));
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g.fillRect(0, 0, w, h);

			int n = ticks.size();
			int maxHp = ticks.stream().mapToInt(TickRecord::getMaxHp).max().orElse(0);
			if (n < 2 || maxHp <= 0)
			{
				g.dispose();
				return;
			}

			int pad = 4;
			double step = (w - pad * 2) / (double) (n - 1);
			double scale = (h - pad * 2) / (double) maxHp;
			int barWidth = Math.max(2, (int) step - 1);

			g.setColor(DAMAGE_COLOR);
			for (int i = 0; i < n; i++)
			{
				int damage = Math.min(ticks.get(i).damageTaken(), maxHp);
				if (damage > 0)
				{
					int barHeight = (int) Math.round(damage * scale);
					int x = (int) Math.round(pad + i * step - barWidth / 2.0);
					g.fillRect(x, h - pad - barHeight, barWidth, barHeight);
				}
			}

			g.setColor(HP_COLOR);
			g.setStroke(new BasicStroke(2f));
			for (int i = 1; i < n; i++)
			{
				int x1 = (int) Math.round(pad + (i - 1) * step);
				int x2 = (int) Math.round(pad + i * step);
				int y1 = (int) Math.round(h - pad - ticks.get(i - 1).getHp() * scale);
				int y2 = (int) Math.round(h - pad - ticks.get(i).getHp() * scale);
				g.drawLine(x1, y1, x2, y2);
			}

			for (int i = 0; i < n; i++)
			{
				if (ticks.get(i).getTick() == deathTick)
				{
					int x = (int) Math.round(pad + i * step);
					g.setColor(DEATH_COLOR);
					g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
					g.drawLine(x, 0, x, h);
				}
			}
			g.dispose();
		}
	}

	/**
	 * A small skull, drawn in code so the plugin ships no image files.
	 */
	static BufferedImage createIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(230, 225, 210));
		g.fillOval(2, 1, 12, 11);
		g.fillRect(5, 10, 6, 4);
		g.setColor(new Color(40, 30, 30));
		g.fillOval(4, 5, 3, 3);
		g.fillOval(9, 5, 3, 3);
		g.fillRect(7, 12, 1, 2);
		g.fillRect(9, 12, 1, 2);
		g.dispose();
		return image;
	}

	private static String damageByKindText(Map<HitKind, Integer> byKind)
	{
		return byKind.entrySet().stream()
			.map(e -> e.getKey().getLabel() + " " + e.getValue())
			.collect(Collectors.joining(", "));
	}
}
