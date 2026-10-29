package com.deathreplay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Totals worked out from a death's ticks. Purely descriptive: it reports what
 * happened and never suggests what the player should have done.
 */
@Data
public class DeathSummary
{
	/**
	 * Ticks counted as "the last few seconds" (5 ticks = 3 seconds).
	 */
	static final int BURST_TICKS = 5;

	private int windowTicks;
	private int totalDamage;
	private int burstDamage;
	private int biggestHit;
	private int hitsTaken;
	private int zeroHits;
	private int healed;
	private int itemsConsumed;
	private int startHp;
	private int maxHp;
	/**
	 * Ticks between the last time HP was above half and the death, or -1 if it
	 * never was during the window.
	 */
	private int ticksFromHalfHp = -1;
	private Map<HitKind, Integer> damageByKind = new LinkedHashMap<>();

	static DeathSummary compute(List<TickRecord> ticks, int deathTick)
	{
		DeathSummary summary = new DeathSummary();
		summary.windowTicks = ticks.size();
		if (ticks.isEmpty())
		{
			return summary;
		}

		TickRecord first = ticks.get(0);
		summary.startHp = first.getHp();
		summary.maxHp = first.getMaxHp();

		Integer lastAboveHalf = null;
		for (TickRecord tick : ticks)
		{
			if (tick.getTick() <= deathTick && tick.getMaxHp() > 0 && tick.getHp() * 2 > tick.getMaxHp())
			{
				lastAboveHalf = tick.getTick();
			}

			summary.itemsConsumed += tick.getConsumed().size();

			for (HitRecord hit : tick.getHits())
			{
				if (hit.getKind() == HitKind.HEAL)
				{
					summary.healed += hit.getAmount();
					continue;
				}

				if (hit.getKind() == HitKind.BLOCK || (hit.isDamage() && hit.getAmount() == 0))
				{
					summary.hitsTaken++;
					summary.zeroHits++;
					continue;
				}

				if (!hit.isDamage())
				{
					continue;
				}

				summary.hitsTaken++;
				summary.totalDamage += hit.getAmount();
				summary.biggestHit = Math.max(summary.biggestHit, hit.getAmount());
				summary.damageByKind.merge(hit.getKind(), hit.getAmount(), Integer::sum);
				if (tick.getTick() > deathTick - BURST_TICKS)
				{
					summary.burstDamage += hit.getAmount();
				}
			}
		}

		if (lastAboveHalf != null)
		{
			summary.ticksFromHalfHp = deathTick - lastAboveHalf;
		}

		return summary;
	}

	/**
	 * One-line recap used in chat and on the death list.
	 */
	String headline()
	{
		if (totalDamage == 0)
		{
			return "No damage recorded before this death";
		}

		return String.format("Took %d damage in the last %s (biggest hit %d)",
			burstDamage, Names.seconds(BURST_TICKS), biggestHit);
	}
}
