package com.banktagstacks;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(BankTagStacksConfig.GROUP)
public interface BankTagStacksConfig extends Config
{
	String GROUP = "banktagstacks";
	String STACKS_KEY = "stacks";

	@ConfigItem(
		keyName = "closeOnSelect",
		name = "Close row on select",
		description = "Close the stack's row once you pick a tag from it",
		position = 1
	)
	default boolean closeOnSelect()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCount",
		name = "Show stack count",
		description = "Show how many tags are inside each stack on its tab",
		position = 2
	)
	default boolean showCount()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dragIntoStacks",
		name = "Drag tabs into stacks",
		description = "Drop a tag tab onto a stack tab to add it to that stack. When off, dragging just reorders tabs like normal",
		position = 0
	)
	default boolean dragIntoStacks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightActive",
		name = "Highlight open stack",
		description = "Outline a stack's tab when one of its tags is the one being viewed",
		position = 3
	)
	default boolean highlightActive()
	{
		return true;
	}
}
