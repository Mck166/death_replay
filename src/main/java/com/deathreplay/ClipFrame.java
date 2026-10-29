package com.deathreplay;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where one frame lives inside a saved clip, and when it was captured.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClipFrame
{
	private long timeMillis;
	private int tick;
	/**
	 * Byte offset of the JPEG data in the .avi file.
	 */
	private long offset;
	private int length;
}
