package com.deathreplay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolling window of the most recent ticks. Only touched on the client thread.
 */
class CombatLog
{
	private final ArrayDeque<TickRecord> ticks = new ArrayDeque<>();

	void add(TickRecord record, int capacity)
	{
		ticks.addLast(record);
		while (ticks.size() > Math.max(1, capacity))
		{
			ticks.removeFirst();
		}
	}

	List<TickRecord> snapshot()
	{
		return new ArrayList<>(ticks);
	}

	int size()
	{
		return ticks.size();
	}

	void clear()
	{
		ticks.clear();
	}
}
