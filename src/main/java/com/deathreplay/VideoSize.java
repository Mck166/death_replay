package com.deathreplay;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum VideoSize
{
	SMALL("Small (50%)", 0.5),
	MEDIUM("Medium (75%)", 0.75),
	FULL("Full size", 1.0);

	private final String name;
	private final double scale;

	@Override
	public String toString()
	{
		return name;
	}
}
