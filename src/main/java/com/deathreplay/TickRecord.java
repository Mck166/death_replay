package com.deathreplay;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * What happened to the local player on one game tick (0.6 seconds).
 */
@Data
public class TickRecord
{
	private int tick;
	private long timeMillis;
	private int hp;
	private int maxHp;
	private int prayer;
	private int maxPrayer;
	private int specialAttack;
	/**
	 * The actor the player was attacking or talking to, if any.
	 */
	private String target;
	private List<HitRecord> hits = new ArrayList<>();
	private List<String> prayers = new ArrayList<>();
	private List<String> consumed = new ArrayList<>();

	public int damageTaken()
	{
		int total = 0;
		for (HitRecord hit : hits)
		{
			if (hit.isDamage())
			{
				total += hit.getAmount();
			}
		}
		return total;
	}
}
