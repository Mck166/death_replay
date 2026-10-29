package com.deathreplay;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.HitsplatID;

/**
 * The kinds of hitsplat that can land on the local player, grouped so the
 * recap can say what took the HP away.
 */
@AllArgsConstructor
@Getter
public enum HitKind
{
	DAMAGE("Damage", true),
	BLOCK("Blocked", false),
	POISON("Poison", true),
	VENOM("Venom", true),
	BLEED("Bleed", true),
	BURN("Burn", true),
	DOOM("Doom", true),
	CORRUPTION("Corruption", true),
	HEAL("Heal", false),
	OTHER("Other", false);

	private final String label;
	/**
	 * Whether the hitsplat's amount was taken from hitpoints.
	 */
	private final boolean damage;

	public static HitKind fromHitsplatType(int type)
	{
		switch (type)
		{
			case HitsplatID.BLOCK_ME:
			case HitsplatID.BLOCK_OTHER:
				return BLOCK;
			case HitsplatID.DAMAGE_ME:
			case HitsplatID.DAMAGE_OTHER:
			case HitsplatID.DAMAGE_ME_CYAN:
			case HitsplatID.DAMAGE_OTHER_CYAN:
			case HitsplatID.DAMAGE_ME_ORANGE:
			case HitsplatID.DAMAGE_OTHER_ORANGE:
			case HitsplatID.DAMAGE_ME_YELLOW:
			case HitsplatID.DAMAGE_OTHER_YELLOW:
			case HitsplatID.DAMAGE_ME_WHITE:
			case HitsplatID.DAMAGE_OTHER_WHITE:
			case HitsplatID.DAMAGE_MAX_ME:
			case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
			case HitsplatID.DAMAGE_ME_POISE:
			case HitsplatID.DAMAGE_OTHER_POISE:
			case HitsplatID.DAMAGE_MAX_ME_POISE:
				return DAMAGE;
			case HitsplatID.POISON:
				return POISON;
			case HitsplatID.VENOM:
				return VENOM;
			case HitsplatID.BLEED:
				return BLEED;
			case HitsplatID.BURN:
				return BURN;
			case HitsplatID.DOOM:
				return DOOM;
			case HitsplatID.CORRUPTION:
				return CORRUPTION;
			case HitsplatID.HEAL:
				return HEAL;
			default:
				return OTHER;
		}
	}
}
