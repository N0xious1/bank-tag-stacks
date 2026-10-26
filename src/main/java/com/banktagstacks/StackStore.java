package com.banktagstacks;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Stack definitions: stack tag -> ordered list of member tags.
 * Saved as JSON in this plugin's own config group, so Bank Tags' data is never
 * touched beyond which tabs are shown in its sidebar.
 */
@Slf4j
@Singleton
class StackStore
{
	private final ConfigManager configManager;
	private final Gson gson;
	private final Map<String, List<String>> stacks = new LinkedHashMap<>();

	@Inject
	StackStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	void load()
	{
		stacks.clear();
		String json = configManager.getConfiguration(BankTagStacksConfig.GROUP, BankTagStacksConfig.STACKS_KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
		{
			Map<String, List<String>> loaded = gson.fromJson(json, new TypeToken<LinkedHashMap<String, List<String>>>()
			{
			}.getType());
			if (loaded != null)
			{
				loaded.forEach((stack, members) -> stacks.put(stack, members == null ? new ArrayList<>() : new ArrayList<>(members)));
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Unable to read saved stacks", e);
		}
	}

	void save()
	{
		if (stacks.isEmpty())
		{
			configManager.unsetConfiguration(BankTagStacksConfig.GROUP, BankTagStacksConfig.STACKS_KEY);
		}
		else
		{
			configManager.setConfiguration(BankTagStacksConfig.GROUP, BankTagStacksConfig.STACKS_KEY, gson.toJson(stacks));
		}
	}

	boolean isStack(String tag)
	{
		return tag != null && stacks.containsKey(tag);
	}

	List<String> members(String stack)
	{
		List<String> m = stacks.get(stack);
		return m == null ? Collections.emptyList() : Collections.unmodifiableList(m);
	}

	String stackOf(String member)
	{
		if (member == null)
		{
			return null;
		}
		for (Map.Entry<String, List<String>> e : stacks.entrySet())
		{
			if (e.getValue().contains(member))
			{
				return e.getKey();
			}
		}
		return null;
	}

	Set<String> names()
	{
		return Collections.unmodifiableSet(stacks.keySet());
	}

	void create(String stack)
	{
		stacks.putIfAbsent(stack, new ArrayList<>());
	}

	void addMember(String stack, String member)
	{
		List<String> m = stacks.computeIfAbsent(stack, k -> new ArrayList<>());
		if (!m.contains(member))
		{
			m.add(member);
		}
	}

	void removeMember(String stack, String member)
	{
		List<String> m = stacks.get(stack);
		if (m != null)
		{
			m.remove(member);
		}
	}

	void remove(String stack)
	{
		stacks.remove(stack);
	}
}
