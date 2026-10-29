package com.deathreplay;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(DeathReplayConfig.GROUP)
public interface DeathReplayConfig extends Config
{
	String GROUP = "death-replay";

	@ConfigItem(
		keyName = "windowSeconds",
		name = "Seconds to keep",
		description = "How many seconds before each death are saved",
		position = 0
	)
	@Range(min = 5, max = 60)
	@Units(Units.SECONDS)
	default int windowSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "chatRecap",
		name = "Chat recap",
		description = "Post a one-line damage recap in the chatbox when you die",
		position = 1
	)
	default boolean chatRecap()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxSaved",
		name = "Deaths to keep",
		description = "Older deaths are deleted once this many are saved",
		position = 2
	)
	@Range(min = 1, max = 1000)
	default int maxSaved()
	{
		return 100;
	}

	@ConfigSection(
		name = "Video",
		description = "Video clips saved with each death",
		position = 10
	)
	String videoSection = "video";

	@ConfigItem(
		keyName = "recordVideo",
		name = "Record video",
		description = "Keep the last few seconds of gameplay in memory and save them as a clip when you die."
			+ " Uses some extra memory and CPU while playing.",
		section = videoSection,
		position = 11
	)
	default boolean recordVideo()
	{
		return true;
	}

	@ConfigItem(
		keyName = "videoFps",
		name = "Frames per second",
		description = "Higher is smoother but uses more memory and CPU",
		section = videoSection,
		position = 12
	)
	@Range(min = 5, max = 30)
	default int videoFps()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "videoSize",
		name = "Video size",
		description = "Size of saved video compared to the game window. Smaller uses less memory and disk but is blurrier.",
		section = videoSection,
		position = 13
	)
	default VideoSize videoSize()
	{
		return VideoSize.FULL;
	}

	@ConfigItem(
		keyName = "videosToKeep",
		name = "Videos to keep",
		description = "Older videos are deleted to save disk space. The damage breakdown is kept. Each video is roughly 30-80 MB.",
		section = videoSection,
		position = 14
	)
	@Range(min = 0, max = 200)
	default int videosToKeep()
	{
		return 10;
	}
}
