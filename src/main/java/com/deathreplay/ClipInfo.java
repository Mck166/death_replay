package com.deathreplay;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ClipInfo
{
	private String file;
	private int width;
	private int height;
	private List<ClipFrame> frames = new ArrayList<>();
}
