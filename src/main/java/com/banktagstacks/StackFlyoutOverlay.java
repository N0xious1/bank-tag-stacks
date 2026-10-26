package com.banktagstacks;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.tabs.TabSprites;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;

/**
 * Draws the fanned-out row of member tags next to an open stack tab, plus small markers
 * on every stack tab in the sidebar.
 */
class StackFlyoutOverlay extends Overlay
{
	// Same sizes Bank Tags uses for its tabs
	private static final int TAB_WIDTH = 39;
	private static final int TAB_HEIGHT = 40;
	private static final int TAB_GAP = 1;
	private static final int ICON_X = 2;
	private static final int ICON_Y = 4;

	private static final int PANEL_PAD = 3;
	private static final int ROW_OFFSET = 4;

	private static final Color PANEL_FILL = new Color(40, 34, 27, 235);
	private static final Color PANEL_OUTER = new Color(14, 12, 9);
	private static final Color PANEL_INNER = new Color(92, 81, 64);
	private static final Color HOVER = new Color(255, 255, 255, 40);
	private static final Color ACTIVE_OUTLINE = new Color(255, 200, 0);
	private static final Color MARKER = new Color(255, 200, 0, 220);
	private static final Color DROP_FILL = new Color(0, 255, 0, 45);
	private static final Color DROP_OUTLINE = new Color(0, 220, 0);

	@Value
	static class Slot
	{
		String tag;
		Rectangle bounds;
	}

	private final Client client;
	private final BankTagStacksPlugin plugin;
	private final BankTagStacksConfig config;
	private final BankTagsBridge bridge;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final TooltipManager tooltipManager;

	@Inject
	StackFlyoutOverlay(Client client, BankTagStacksPlugin plugin, BankTagStacksConfig config, BankTagsBridge bridge,
		ItemManager itemManager, SpriteManager spriteManager, TooltipManager tooltipManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.bridge = bridge;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!bridge.isBankOpen())
		{
			if (plugin.getOpenStack() != null)
			{
				plugin.closeFlyout();
			}
			return null;
		}

		Map<String, List<String>> stacks = plugin.stacksView();
		if (stacks.isEmpty())
		{
			return null;
		}

		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		Map<String, Rectangle> stackTabs = findVisibleStackTabs(container, stacks);

		BankTagsService service = bridge.service();
		String activeTag = service == null ? null : service.getActiveTag();

		g.setFont(FontManager.getRunescapeSmallFont());
		for (Map.Entry<String, Rectangle> e : stackTabs.entrySet())
		{
			List<String> members = stacks.get(e.getKey());
			drawStackMarker(g, e.getValue(), members.size(), members.contains(activeTag));
		}

		String dropTarget = plugin.dropTargetStack();
		if (dropTarget != null && stackTabs.containsKey(dropTarget))
		{
			drawDropHint(g, stackTabs.get(dropTarget), dropTarget);
		}

		String open = plugin.getOpenStack();
		if (open == null)
		{
			return null;
		}

		Rectangle stackTab = stackTabs.get(open);
		List<String> members = stacks.get(open);
		if (stackTab == null || members == null || members.isEmpty())
		{
			// Stack scrolled out of view or no longer exists
			plugin.closeFlyout();
			return null;
		}

		drawFlyout(g, container, stackTab, open, members, activeTag);
		return null;
	}

	private Map<String, Rectangle> findVisibleStackTabs(Widget container, Map<String, List<String>> stacks)
	{
		if (container == null)
		{
			return Collections.emptyMap();
		}

		Widget[] children = container.getDynamicChildren();
		if (children == null)
		{
			return Collections.emptyMap();
		}

		int bg = TabSprites.TAB_BACKGROUND.getSpriteId();
		int bgActive = TabSprites.TAB_BACKGROUND_ACTIVE.getSpriteId();

		Map<String, Rectangle> found = new LinkedHashMap<>();
		for (Widget w : children)
		{
			if (w == null || w.isHidden() || (w.getSpriteId() != bg && w.getSpriteId() != bgActive))
			{
				continue;
			}
			String tag = Text.standardize(Text.removeTags(w.getName()));
			if (stacks.containsKey(tag))
			{
				found.put(tag, w.getBounds());
			}
		}
		return found;
	}

	private void drawStackMarker(Graphics2D g, Rectangle tab, int count, boolean holdsActive)
	{
		// Small arrow on the right edge so stack tabs read differently from normal tabs
		int ax = tab.x + tab.width - 5;
		int ay = tab.y + tab.height / 2;
		Polygon arrow = new Polygon(new int[]{ax, ax + 4, ax}, new int[]{ay - 4, ay, ay + 4}, 3);
		g.setColor(Color.BLACK);
		g.translate(1, 1);
		g.fillPolygon(arrow);
		g.translate(-1, -1);
		g.setColor(MARKER);
		g.fillPolygon(arrow);

		if (config.showCount())
		{
			String text = String.valueOf(count);
			FontMetrics fm = g.getFontMetrics();
			int tx = tab.x + tab.width - fm.stringWidth(text) - 3;
			int ty = tab.y + tab.height - 3;
			g.setColor(Color.BLACK);
			g.drawString(text, tx + 1, ty + 1);
			g.setColor(Color.WHITE);
			g.drawString(text, tx, ty);
		}

		if (holdsActive && config.highlightActive())
		{
			g.setColor(ACTIVE_OUTLINE);
			g.setStroke(new BasicStroke(1));
			g.drawRect(tab.x, tab.y, tab.width - 1, tab.height - 1);
		}
	}

	/** Shown while a tag tab is held over a stack tab, so it's clear letting go will stack it. */
	private void drawDropHint(Graphics2D g, Rectangle tab, String stack)
	{
		g.setColor(DROP_FILL);
		g.fillRect(tab.x, tab.y, tab.width, tab.height);
		g.setColor(DROP_OUTLINE);
		g.setStroke(new BasicStroke(2));
		g.drawRect(tab.x + 1, tab.y + 1, tab.width - 2, tab.height - 2);
		g.setStroke(new BasicStroke(1));

		String text = "Add to " + stack;
		FontMetrics fm = g.getFontMetrics();
		int pad = 3;
		int w = fm.stringWidth(text) + pad * 2;
		int h = fm.getHeight() + pad;
		int x = tab.x + tab.width + ROW_OFFSET;
		int y = tab.y + (tab.height - h) / 2;

		g.setColor(PANEL_FILL);
		g.fillRect(x, y, w, h);
		g.setColor(DROP_OUTLINE);
		g.drawRect(x, y, w - 1, h - 1);
		g.setColor(Color.BLACK);
		g.drawString(text, x + pad + 1, y + fm.getAscent() + 2);
		g.setColor(Color.WHITE);
		g.drawString(text, x + pad, y + fm.getAscent() + 1);
	}

	private void drawFlyout(Graphics2D g, Widget container, Rectangle stackTab, String stack,
		List<String> members, String activeTag)
	{
		Rectangle bank = container.getBounds();
		int startX = stackTab.x + stackTab.width + ROW_OFFSET + PANEL_PAD;
		int startY = stackTab.y;

		// Wrap onto extra rows if the bank isn't wide enough for every member
		int usable = bank.x + bank.width - startX - PANEL_PAD;
		int perRow = Math.max(1, (usable + TAB_GAP) / (TAB_WIDTH + TAB_GAP));
		int cols = Math.min(perRow, members.size());
		int rows = (members.size() + perRow - 1) / perRow;

		int rowsHeight = rows * TAB_HEIGHT + (rows - 1) * TAB_GAP;
		// Keep the row inside the bank vertically
		int maxY = bank.y + bank.height - rowsHeight - PANEL_PAD;
		startY = Math.max(bank.y + PANEL_PAD, Math.min(startY, maxY));

		Rectangle panel = new Rectangle(
			startX - PANEL_PAD,
			startY - PANEL_PAD,
			cols * TAB_WIDTH + (cols - 1) * TAB_GAP + PANEL_PAD * 2,
			rowsHeight + PANEL_PAD * 2);

		g.setColor(PANEL_FILL);
		g.fillRect(panel.x, panel.y, panel.width, panel.height);
		g.setColor(PANEL_OUTER);
		g.drawRect(panel.x, panel.y, panel.width - 1, panel.height - 1);
		g.setColor(PANEL_INNER);
		g.drawRect(panel.x + 1, panel.y + 1, panel.width - 3, panel.height - 3);

		BufferedImage bg = spriteManager.getSprite(TabSprites.TAB_BACKGROUND.getSpriteId(), 0);
		BufferedImage bgActive = spriteManager.getSprite(TabSprites.TAB_BACKGROUND_ACTIVE.getSpriteId(), 0);

		Point mouse = client.getMouseCanvasPosition();
		List<Slot> slots = new ArrayList<>(members.size());

		for (int i = 0; i < members.size(); i++)
		{
			String tag = members.get(i);
			int x = startX + (i % perRow) * (TAB_WIDTH + TAB_GAP);
			int y = startY + (i / perRow) * (TAB_HEIGHT + TAB_GAP);
			Rectangle r = new Rectangle(x, y, TAB_WIDTH, TAB_HEIGHT);
			slots.add(new Slot(tag, r));

			boolean active = tag.equals(activeTag);
			BufferedImage back = active ? bgActive : bg;
			if (back != null)
			{
				g.drawImage(back, x, y, TAB_WIDTH, TAB_HEIGHT, null);
			}
			else
			{
				g.setColor(active ? new Color(110, 90, 50) : new Color(70, 60, 45));
				g.fillRect(x, y, TAB_WIDTH, TAB_HEIGHT);
			}

			BufferedImage icon = itemManager.getImage(bridge.iconFor(tag));
			if (icon != null)
			{
				g.drawImage(icon, x + ICON_X, y + ICON_Y, null);
			}

			if (mouse != null && r.contains(mouse.getX(), mouse.getY()))
			{
				g.setColor(HOVER);
				g.fillRect(x, y, TAB_WIDTH, TAB_HEIGHT);
				tooltipManager.add(new Tooltip(tag));
			}
		}

		plugin.setFlyoutGeometry(slots, panel, stackTab);
	}
}
