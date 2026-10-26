package com.banktagstacks;

import com.google.inject.Provides;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.SoundEffectID;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetDrag;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.tabs.TabInterface;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Bank Tag Stacks",
	description = "Group bank tag tabs into stacks that fan out to the right when clicked",
	tags = {"bank", "tags", "tabs", "stack", "group", "folder"}
)
@PluginDependency(BankTagsPlugin.class)
public class BankTagStacksPlugin extends Plugin
{
	// Bank Tags' own menu option on a tag tab
	static final String VIEW_TAG_TAB = "View tag tab";

	private static final String ADD_TO_STACK = "Add to stack";
	private static final String UNSTACK = "Unstack";
	private static final String REMOVE_FROM_STACK = "Remove from stack";

	// PotionStorage.BANKTAB_POTIONSTORE is package-private in Bank Tags
	private static final int BANKTAB_POTIONSTORE = 15;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BankTagStacksConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private StackFlyoutOverlay overlay;

	@Inject
	private StackStore store;

	@Inject
	private BankTagsBridge bridge;

	/** The stack whose row is currently fanned out, or null. */
	@Getter
	private volatile String openStack;

	/** Written by the overlay each frame, read on the AWT thread for clicks. */
	private volatile List<StackFlyoutOverlay.Slot> slots = Collections.emptyList();
	private volatile Rectangle panelBounds;
	private volatile Rectangle openStackTabBounds;

	private boolean swallowRelease;

	private final MouseAdapter mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (openStack == null)
			{
				return e;
			}

			StackFlyoutOverlay.Slot slot = slotAt(e.getPoint());
			if (slot != null)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					// Keep the click away from the bank items underneath the row
					e.consume();
					swallowRelease = true;
					String tag = slot.getTag();
					clientThread.invokeLater(() -> openMember(tag));
				}
				// Right-click falls through so onMenuOpened can build the slot's menu
				return e;
			}

			Rectangle panel = panelBounds;
			Rectangle tab = openStackTabBounds;
			boolean onPanel = panel != null && panel.contains(e.getPoint());
			boolean onTab = tab != null && tab.contains(e.getPoint());
			if (!onPanel && !onTab && SwingUtilities.isLeftMouseButton(e))
			{
				// Clicking anywhere else closes the row
				openStack = null;
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (swallowRelease)
			{
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e)
		{
			if (swallowRelease)
			{
				e.consume();
				swallowRelease = false;
			}
			return e;
		}
	};

	@Provides
	BankTagStacksConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankTagStacksConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseManager.registerMouseListener(mouseListener);
		clientThread.invokeLater(() ->
		{
			store.load();
			reconcile();
			if (bridge.isBankOpen())
			{
				bridge.relayoutBank();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		mouseManager.unregisterMouseListener(mouseListener);
		closeFlyout();
		// Put every member back in the sidebar so nothing goes missing while the plugin is off.
		// The stack definitions are kept and re-applied on the next start.
		clientThread.invokeLater(() ->
		{
			restoreAllMembers();
			if (bridge.isBankOpen())
			{
				bridge.relayoutBank();
			}
		});
	}

	// ---- Events ---------------------------------------------------------------------------

	// Runs just before Bank Tags loads its tab list on bank open (higher priority goes first)
	@Subscribe(priority = 1)
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_INIT)
		{
			reconcile();
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// Bank Tags and our stacks both live in the active config profile
		clientThread.invokeLater(() ->
		{
			closeFlyout();
			store.load();
			reconcile();
		});
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload())
		{
			closeFlyout();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!VIEW_TAG_TAB.equals(event.getOption())
			|| event.getActionParam1() != InterfaceID.Bankmain.ITEMS_CONTAINER)
		{
			return;
		}

		String tag = Text.standardize(Text.removeTags(event.getTarget()));
		if (tag.isEmpty())
		{
			return;
		}

		if (store.isStack(tag))
		{
			client.getMenu().createMenuEntry(1)
				.setOption(UNSTACK)
				.setTarget(event.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> unstack(tag));
		}
		else
		{
			client.getMenu().createMenuEntry(1)
				.setOption(ADD_TO_STACK)
				.setTarget(event.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> promptAddToStack(tag));
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!VIEW_TAG_TAB.equals(event.getMenuOption())
			|| event.getMenuAction() != MenuAction.CC_OP
			|| event.getParam1() != InterfaceID.Bankmain.ITEMS_CONTAINER)
		{
			return;
		}

		String tag = Text.standardize(Text.removeTags(event.getMenuTarget()));
		if (!store.isStack(tag))
		{
			return;
		}

		// A stack tab doesn't open a tag of its own; it fans out its members instead
		event.consume();
		openStack = tag.equals(openStack) ? null : tag;
		client.playSoundEffect(SoundEffectID.UI_BOOP);
	}

	// Runs before Bank Tags' own handler, which would otherwise swap the two tabs
	@Subscribe(priority = 1)
	public void onWidgetDrag(WidgetDrag event)
	{
		// Bank Tags fires this every frame while dragging; button 0 means the mouse was just released
		if (client.getMouseCurrentButton() != 0)
		{
			return;
		}

		Widget dragged = client.getDraggedWidget();
		Widget draggedOn = client.getDraggedOnWidget();
		if (!canDropIntoStack(dragged, draggedOn))
		{
			return;
		}

		String member = tagOf(dragged);
		String stack = tagOf(draggedOn);

		// Nulling the drop target is how Bank Tags itself cancels a drag, so it won't reorder the tabs
		client.setDraggedOnWidget(null);
		addToStack(member, stack);
		client.playSoundEffect(SoundEffectID.UI_BOOP);
	}

	/** The stack a tag tab is currently being dragged over, or null. Used by the overlay. */
	String dropTargetStack()
	{
		if (!client.isDraggingWidget())
		{
			return null;
		}
		Widget draggedOn = client.getDraggedOnWidget();
		return canDropIntoStack(client.getDraggedWidget(), draggedOn) ? tagOf(draggedOn) : null;
	}

	private boolean canDropIntoStack(Widget dragged, Widget draggedOn)
	{
		if (!config.dragIntoStacks() || !isTagTabWidget(dragged) || !isTagTabWidget(draggedOn))
		{
			return false;
		}

		String member = tagOf(dragged);
		String stack = tagOf(draggedOn);
		return !member.equals(stack)
			&& store.isStack(stack)
			// Stacks can't go inside stacks; dragging one stack onto another just reorders as normal
			&& !store.isStack(member);
	}

	private static boolean isTagTabWidget(Widget w)
	{
		// Tag tabs are the named children Bank Tags adds to the bank's item container.
		// Its scroll/new-tab buttons there have no name.
		return w != null
			&& w.getParentId() == InterfaceID.Bankmain.ITEMS_CONTAINER
			&& !Text.removeTags(w.getName()).isEmpty();
	}

	private static String tagOf(Widget w)
	{
		return Text.standardize(Text.removeTags(w.getName()));
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		StackFlyoutOverlay.Slot slot = slotAt(new Point(mouse.getX(), mouse.getY()));
		if (slot == null)
		{
			return;
		}

		// Replace whatever bank item is under the row with the slot's own options
		MenuEntry cancel = null;
		for (MenuEntry entry : event.getMenuEntries())
		{
			if (entry.getType() == MenuAction.CANCEL)
			{
				cancel = entry;
				break;
			}
		}
		client.getMenu().setMenuEntries(cancel == null ? new MenuEntry[0] : new MenuEntry[]{cancel});

		String tag = slot.getTag();
		String target = ColorUtil.wrapWithColorTag(tag, JagexColors.MENU_TARGET);
		client.getMenu().createMenuEntry(-1)
			.setOption(REMOVE_FROM_STACK)
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> removeFromStack(tag));
		client.getMenu().createMenuEntry(-1)
			.setOption(VIEW_TAG_TAB)
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> openMember(tag));
	}

	// ---- Flyout state (shared with the overlay) -------------------------------------------

	void closeFlyout()
	{
		openStack = null;
		slots = Collections.emptyList();
		panelBounds = null;
		openStackTabBounds = null;
	}

	void setFlyoutGeometry(List<StackFlyoutOverlay.Slot> slots, Rectangle panel, Rectangle stackTab)
	{
		this.slots = slots;
		this.panelBounds = panel;
		this.openStackTabBounds = stackTab;
	}

	private StackFlyoutOverlay.Slot slotAt(Point p)
	{
		if (openStack == null)
		{
			return null;
		}
		for (StackFlyoutOverlay.Slot s : slots)
		{
			if (s.getBounds().contains(p))
			{
				return s;
			}
		}
		return null;
	}

	// ---- Opening a member tag -------------------------------------------------------------

	private void openMember(String tag)
	{
		BankTagsService service = bridge.service();
		if (service == null || !bridge.isBankOpen())
		{
			return;
		}

		if (tag.equals(service.getActiveTag()))
		{
			// Same as Bank Tags: clicking the open tag again closes it
			service.closeBankTag();
		}
		else
		{
			if (client.getVarbitValue(VarbitID.BANK_CURRENTTAB) == BANKTAB_POTIONSTORE)
			{
				// Opening a tag over the potion store leaves it open underneath and deposits stop working.
				// Plugins can't close it for the player, so ask them to.
				chat("Close the potion store before opening a stacked tag.");
				return;
			}
			client.setVarbit(VarbitID.BANK_CURRENTTAB, 0);
			service.openBankTag(tag, BankTagsService.OPTION_ALLOW_MODIFICATIONS);
		}

		client.playSoundEffect(SoundEffectID.UI_BOOP);
		if (config.closeOnSelect())
		{
			closeFlyout();
		}
	}

	// ---- Stack editing (client thread) ----------------------------------------------------

	private void promptAddToStack(String member)
	{
		String existing = store.names().isEmpty() ? "" : " (existing: " + String.join(", ", store.names()) + ")";
		chatboxPanelManager.openTextInput("Add \"" + member + "\" to stack" + existing + ":")
			.addCharValidator(TabInterface.FILTERED_CHARS)
			.onDone((java.util.function.Consumer<String>) name -> clientThread.invoke(() -> addToStack(member, name)))
			.build();
	}

	private void addToStack(String member, String stackName)
	{
		String stack = Text.standardize(stackName == null ? "" : stackName);
		if (stack.isEmpty())
		{
			return;
		}
		if (stack.equals(member))
		{
			chat("A tag can't be stacked into itself.");
			return;
		}
		if (store.isStack(member))
		{
			chat("Stacks can't go inside other stacks.");
			return;
		}

		List<String> tabs = bridge.loadTabNames();
		int idx = tabs.indexOf(member);
		if (idx < 0)
		{
			return;
		}

		if (!store.isStack(stack))
		{
			if (tabs.contains(stack))
			{
				chat("There's already a tag tab called \"" + stack + "\". Pick a different stack name.");
				return;
			}
			// The new stack tab takes the member's place and borrows its icon.
			// Change it any time with Bank Tags' own "Change icon" option.
			tabs.add(idx, stack);
			bridge.setIcon(stack, bridge.iconFor(member));
			store.create(stack);
		}

		tabs.remove(member);
		store.addMember(stack, member);
		store.save();
		bridge.saveTabNames(tabs);
		bridge.relayoutBank();
	}

	private void removeFromStack(String member)
	{
		String stack = store.stackOf(member);
		if (stack == null)
		{
			return;
		}

		List<String> tabs = bridge.loadTabNames();
		int idx = tabs.indexOf(stack);
		store.removeMember(stack, member);

		if (!tabs.contains(member))
		{
			if (idx < 0)
			{
				tabs.add(member);
			}
			else
			{
				tabs.add(idx + 1, member);
			}
		}

		if (store.members(stack).isEmpty())
		{
			tabs.remove(stack);
			bridge.unsetIcon(stack);
			store.remove(stack);
			closeFlyout();
		}

		store.save();
		bridge.saveTabNames(tabs);
		bridge.relayoutBank();
	}

	private void unstack(String stack)
	{
		List<String> tabs = bridge.loadTabNames();
		putMembersBack(tabs, stack);
		bridge.unsetIcon(stack);
		store.remove(stack);
		if (stack.equals(openStack))
		{
			closeFlyout();
		}

		store.save();
		bridge.saveTabNames(tabs);
		bridge.relayoutBank();
	}

	/** Swap a stack tab for its members, in order, in the given tab list. */
	private void putMembersBack(List<String> tabs, String stack)
	{
		List<String> toAdd = new ArrayList<>();
		for (String m : store.members(stack))
		{
			if (!tabs.contains(m))
			{
				toAdd.add(m);
			}
		}

		int idx = tabs.indexOf(stack);
		if (idx < 0)
		{
			tabs.addAll(toAdd);
		}
		else
		{
			tabs.remove(idx);
			tabs.addAll(idx, toAdd);
		}
	}

	private void restoreAllMembers()
	{
		List<String> tabs = bridge.loadTabNames();
		for (String stack : new ArrayList<>(store.names()))
		{
			putMembersBack(tabs, stack);
		}
		bridge.saveTabNames(tabs);
	}

	/**
	 * Make the saved sidebar match the stack definitions. Handles:
	 * - first start / restart after shutDown put members back: re-collapse them into the stack
	 * - the stack tab was deleted or renamed in Bank Tags: give the members their tabs back
	 */
	private void reconcile()
	{
		List<String> tabs = bridge.loadTabNames();
		boolean tabsChanged = false;
		boolean storeChanged = false;

		for (String stack : new ArrayList<>(store.names()))
		{
			List<String> members = store.members(stack);
			if (members.isEmpty())
			{
				store.remove(stack);
				tabsChanged |= tabs.remove(stack);
				storeChanged = true;
				continue;
			}

			if (tabs.contains(stack))
			{
				tabsChanged |= tabs.removeAll(members);
				continue;
			}

			int first = -1;
			for (String m : members)
			{
				int i = tabs.indexOf(m);
				if (i >= 0 && (first < 0 || i < first))
				{
					first = i;
				}
			}

			if (first >= 0)
			{
				tabs.add(first, stack);
				tabs.removeAll(members);
			}
			else
			{
				// Stack tab is gone from Bank Tags: don't lose the member tabs
				tabs.addAll(members);
				store.remove(stack);
				storeChanged = true;
			}
			tabsChanged = true;
		}

		if (tabsChanged)
		{
			bridge.saveTabNames(tabs);
		}
		if (storeChanged)
		{
			store.save();
		}
	}

	Map<String, List<String>> stacksView()
	{
		Map<String, List<String>> m = new java.util.LinkedHashMap<>();
		for (String s : store.names())
		{
			m.put(s, store.members(s));
		}
		return m;
	}

	private void chat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(new ChatMessageBuilder().append(message).build())
			.build());
	}
}
