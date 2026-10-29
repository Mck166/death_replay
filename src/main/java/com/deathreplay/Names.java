package com.deathreplay;

import java.util.Locale;
import java.util.Set;
import net.runelite.api.Prayer;

final class Names
{
	private static final Set<String> SMALL_WORDS = Set.of("from", "of", "the");

	private Names()
	{
	}

	/**
	 * Turns an enum name like PROTECT_FROM_MAGIC into "Protect from Magic".
	 */
	static String prayer(Prayer prayer)
	{
		String name = prayer.name();
		if (name.startsWith("RP_"))
		{
			name = name.substring(3);
		}
		return titleCase(name);
	}

	static String titleCase(String enumName)
	{
		String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < words.length; i++)
		{
			String word = words[i];
			if (word.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			if (i > 0 && SMALL_WORDS.contains(word))
			{
				sb.append(word);
			}
			else
			{
				sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return sb.toString();
	}

	/**
	 * Drops the dose suffix from potion names, e.g. "Saradomin brew(3)".
	 */
	static String stripDose(String itemName)
	{
		return itemName.replaceAll("\\(\\d\\)$", "").trim();
	}

	/**
	 * Formats a tick count as seconds, e.g. 5 ticks -> "3s", 3 ticks -> "1.8s".
	 */
	static String seconds(int ticks)
	{
		int tenths = ticks * 6;
		if (tenths % 10 == 0)
		{
			return (tenths / 10) + "s";
		}
		return String.format(Locale.ROOT, "%.1fs", tenths / 10.0);
	}
}
