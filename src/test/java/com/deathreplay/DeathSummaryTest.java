package com.deathreplay;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.HitsplatID;
import net.runelite.api.Prayer;
import org.junit.Test;

public class DeathSummaryTest
{
	private static TickRecord tick(int tick, int hp, HitRecord... hits)
	{
		TickRecord record = new TickRecord();
		record.setTick(tick);
		record.setHp(hp);
		record.setMaxHp(99);
		record.setHits(new ArrayList<>(Arrays.asList(hits)));
		return record;
	}

	private static HitRecord hit(int amount, HitKind kind)
	{
		return new HitRecord(amount, kind);
	}

	@Test
	public void totalsDamageAndBurst()
	{
		List<TickRecord> ticks = Arrays.asList(
			tick(100, 99),
			tick(101, 80, hit(19, HitKind.DAMAGE)),
			tick(102, 80, hit(0, HitKind.BLOCK)),
			tick(103, 60, hit(15, HitKind.DAMAGE), hit(5, HitKind.VENOM)),
			tick(104, 70, hit(10, HitKind.HEAL)),
			tick(105, 40, hit(30, HitKind.DAMAGE)),
			tick(106, 0, hit(40, HitKind.DAMAGE)),
			tick(107, 0)
		);

		DeathSummary s = DeathSummary.compute(ticks, 106);

		assertEquals(8, s.getWindowTicks());
		assertEquals(109, s.getTotalDamage());
		// ticks 102..106 are the last 5 ticks before death
		assertEquals(90, s.getBurstDamage());
		assertEquals(40, s.getBiggestHit());
		assertEquals(6, s.getHitsTaken());
		assertEquals(1, s.getZeroHits());
		assertEquals(10, s.getHealed());
		assertEquals(99, s.getStartHp());
		assertEquals(Integer.valueOf(104), s.getDamageByKind().get(HitKind.DAMAGE));
		assertEquals(Integer.valueOf(5), s.getDamageByKind().get(HitKind.VENOM));
		// last above half HP (49.5) was tick 104 with 70
		assertEquals(2, s.getTicksFromHalfHp());
		assertEquals("Took 90 damage in the last 3s (biggest hit 40)", s.headline());
	}

	@Test
	public void neverAboveHalf()
	{
		DeathSummary s = DeathSummary.compute(Arrays.asList(tick(1, 20), tick(2, 0, hit(20, HitKind.DAMAGE))), 2);
		assertEquals(-1, s.getTicksFromHalfHp());
	}

	@Test
	public void emptyWindow()
	{
		DeathSummary s = DeathSummary.compute(new ArrayList<>(), 5);
		assertEquals(0, s.getTotalDamage());
		assertEquals("No damage recorded before this death", s.headline());
	}

	@Test
	public void hitsplatTypes()
	{
		assertEquals(HitKind.DAMAGE, HitKind.fromHitsplatType(HitsplatID.DAMAGE_ME));
		assertEquals(HitKind.DAMAGE, HitKind.fromHitsplatType(HitsplatID.DAMAGE_MAX_ME));
		assertEquals(HitKind.BLOCK, HitKind.fromHitsplatType(HitsplatID.BLOCK_ME));
		assertEquals(HitKind.VENOM, HitKind.fromHitsplatType(HitsplatID.VENOM));
		assertEquals(HitKind.HEAL, HitKind.fromHitsplatType(HitsplatID.HEAL));
		assertEquals(HitKind.OTHER, HitKind.fromHitsplatType(HitsplatID.PRAYER_DRAIN));
	}

	@Test
	public void names()
	{
		assertEquals("Protect from Magic", Names.prayer(Prayer.PROTECT_FROM_MAGIC));
		assertEquals("Ancient Strength", Names.prayer(Prayer.RP_ANCIENT_STRENGTH));
		assertEquals("Saradomin brew", Names.stripDose("Saradomin brew(3)"));
		assertEquals("3s", Names.seconds(5));
		assertEquals("1.8s", Names.seconds(3));
	}

	@Test
	public void combatLogKeepsNewest()
	{
		CombatLog log = new CombatLog();
		for (int i = 0; i < 10; i++)
		{
			log.add(tick(i, 99), 4);
		}
		List<TickRecord> snapshot = log.snapshot();
		assertEquals(4, snapshot.size());
		assertEquals(6, snapshot.get(0).getTick());
		assertEquals(9, snapshot.get(3).getTick());
	}
}
