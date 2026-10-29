package com.deathreplay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GearItem
{
	private String slot;
	private int itemId;
	private String name;
}
