package com.deathreplay;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Everything saved for one death. Stored as JSON in the plugin's data folder.
 */
@Data
public class DeathRecord
{
	static final int VERSION = 2;

	private int version = VERSION;
	/**
	 * Timestamp-based id, also the file name.
	 */
	private String id;
	private long timeMillis;
	private int world;
	private int x;
	private int y;
	private int plane;
	private int regionId;
	private int deathTick;
	/**
	 * The last thing the player was attacking during the window.
	 */
	private String lastTarget;
	private List<GearItem> gear = new ArrayList<>();
	private List<TickRecord> ticks = new ArrayList<>();
	private DeathSummary summary;
	/**
	 * The saved video, or null if video recording was off. Added in version 2.
	 */
	private ClipInfo clip;
}
