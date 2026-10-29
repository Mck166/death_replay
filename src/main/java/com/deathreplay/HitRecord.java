package com.deathreplay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HitRecord
{
	private int amount;
	private HitKind kind;

	public boolean isDamage()
	{
		return kind != null && kind.isDamage();
	}
}
