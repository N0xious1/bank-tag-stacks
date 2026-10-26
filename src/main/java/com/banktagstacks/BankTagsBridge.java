package com.banktagstacks;

import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.plugins.banktags.tabs.TagTab;
import net.runelite.client.util.Text;

/**
 * Everything that talks to the core Bank Tags plugin lives here.
 *
 * The sidebar is driven by the "banktags.tagtabs" config list, which Bank Tags reloads every
 * time the bank opens. Stacking works by taking member tags out of that list and putting a
 * stack tab in their place. Member tags keep their items, icons and layouts untouched.
 *
 * Bank Tags runs in its own child injector, so its services are reached through the running
 * plugin instance rather than injected directly.
 */
@Singleton
class BankTagsBridge
{
	private final Client client;
	private final ConfigManager configManager;
	private final PluginManager pluginManager;

	@Inject
	BankTagsBridge(Client client, ConfigManager configManager, PluginManager pluginManager)
	{
		this.client = client;
		this.configManager = configManager;
		this.pluginManager = pluginManager;
	}

	private BankTagsPlugin plugin()
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			if (p instanceof BankTagsPlugin && pluginManager.isPluginEnabled(p))
			{
				return (BankTagsPlugin) p;
			}
		}
		return null;
	}

	BankTagsService service()
	{
		return plugin();
	}

	private TabManager tabManager()
	{
		BankTagsPlugin p = plugin();
		if (p == null || p.getInjector() == null)
		{
			return null;
		}
		return p.getInjector().getInstance(TabManager.class);
	}

	/** Tag tabs in sidebar order, as Bank Tags has them saved. */
	List<String> loadTabNames()
	{
		String csv = configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG);
		return new ArrayList<>(Text.fromCSV(MoreObjects.firstNonNull(csv, "")));
	}

	/** Save the sidebar order and keep Bank Tags' in-memory copy in step with it. */
	void saveTabNames(List<String> names)
	{
		if (names.isEmpty())
		{
			configManager.unsetConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG);
		}
		else
		{
			configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(names));
		}

		TabManager tm = tabManager();
		if (tm != null)
		{
			List<TagTab> tabs = tm.getTabs();
			tabs.clear();
			for (String name : names)
			{
				TagTab tab = new TagTab();
				tab.setTag(name);
				tab.setIconItemId(iconFor(name));
				tabs.add(tab);
			}
		}
	}

	int iconFor(String tag)
	{
		String value = configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag);
		Integer id = value == null ? null : Ints.tryParse(value);
		return id == null ? ItemID.SPADE : id;
	}

	void setIcon(String tag, int itemId)
	{
		configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag, itemId);
	}

	void unsetIcon(String tag)
	{
		configManager.unsetConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag);
	}

	boolean isBankOpen()
	{
		Widget w = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		return w != null && !w.isHidden();
	}

	/** Re-run the bank build script so Bank Tags redraws its sidebar. Client thread only. */
	void relayoutBank()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null)
		{
			return;
		}
		Object[] args = items.getOnInvTransmitListener();
		if (args != null)
		{
			client.runScript(args);
		}
	}
}
