package com.deathreplay;

import lombok.Value;

/**
 * One JPEG-compressed frame in the rolling video buffer.
 */
@Value
class CapturedFrame
{
	long timeMillis;
	int tick;
	int width;
	int height;
	byte[] jpeg;
}
