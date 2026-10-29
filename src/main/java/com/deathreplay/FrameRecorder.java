package com.deathreplay;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.DrawManager;

/**
 * Keeps the last few seconds of game frames in memory as JPEGs.
 *
 * Frames are copied on the client thread (a single unscaled draw), then
 * scaled and compressed on the plugin's own executor. The buffer is only
 * touched on that executor, so saving a clip queued behind pending frames
 * always sees them.
 */
@Slf4j
class FrameRecorder implements Runnable
{
	/**
	 * Frames waiting to be compressed. Past this, new frames are skipped
	 * rather than letting a slow PC fall further behind.
	 */
	private static final int MAX_PENDING = 4;
	private static final long MAX_BUFFER_BYTES = 256L * 1024 * 1024;
	/**
	 * Longest side of a saved frame, so very large clients stay manageable.
	 */
	private static final int MAX_DIMENSION = 1920;
	/**
	 * Kept beyond the configured window so a clip always covers it.
	 */
	private static final long EXTRA_MILLIS = 5_000;

	private final Client client;
	private final DrawManager drawManager;
	private final DeathReplayConfig config;
	private final ExecutorService executor;
	private final AtomicInteger pending = new AtomicInteger();
	private long lastCaptureMillis;

	// executor thread only
	private final ArrayDeque<CapturedFrame> buffer = new ArrayDeque<>();
	private long bufferBytes;
	private JpegEncoder jpegEncoder;

	FrameRecorder(Client client, DrawManager drawManager, DeathReplayConfig config, ExecutorService executor)
	{
		this.client = client;
		this.drawManager = drawManager;
		this.config = config;
		this.executor = executor;
	}

	void start()
	{
		drawManager.registerEveryFrameListener(this);
	}

	void stop()
	{
		drawManager.unregisterEveryFrameListener(this);
		submit(() ->
		{
			clearBuffer();
			if (jpegEncoder != null)
			{
				jpegEncoder.dispose();
				jpegEncoder = null;
			}
		});
	}

	/**
	 * Called every frame on the client thread; asks for a copy of the next
	 * frame when one is due.
	 */
	@Override
	public void run()
	{
		if (!config.recordVideo() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastCaptureMillis < 1000 / Math.max(1, config.videoFps()) || pending.get() >= MAX_PENDING)
		{
			return;
		}

		lastCaptureMillis = now;
		drawManager.requestNextFrameListener(this::onFrame);
	}

	/**
	 * Drops all buffered frames, e.g. after logging out.
	 */
	void clear()
	{
		submit(this::clearBuffer);
	}

	/**
	 * Must be called on the executor thread.
	 *
	 * @return buffered frames captured at or after {@code fromMillis}
	 */
	List<CapturedFrame> framesSince(long fromMillis)
	{
		List<CapturedFrame> frames = new ArrayList<>();
		for (CapturedFrame frame : buffer)
		{
			if (frame.getTimeMillis() >= fromMillis)
			{
				frames.add(frame);
			}
		}
		return frames;
	}

	private void onFrame(Image image)
	{
		int width = image.getWidth(null);
		int height = image.getHeight(null);
		if (width <= 0 || height <= 0)
		{
			return;
		}

		// The image may be a reused back buffer, so copy it before leaving the client thread
		BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();

		long time = System.currentTimeMillis();
		int tick = client.getTickCount();
		double scale = config.videoSize().getScale();

		pending.incrementAndGet();
		if (!submit(() ->
		{
			try
			{
				encode(copy, time, tick, scale);
			}
			finally
			{
				pending.decrementAndGet();
			}
		}))
		{
			pending.decrementAndGet();
		}
	}

	private void encode(BufferedImage source, long time, int tick, double scale)
	{
		scale = Math.min(scale, MAX_DIMENSION / (double) Math.max(source.getWidth(), source.getHeight()));
		// even dimensions keep video players happy
		int width = Math.max(2, (int) (source.getWidth() * scale) & ~1);
		int height = Math.max(2, (int) (source.getHeight() * scale) & ~1);

		BufferedImage scaled = source;
		if (width != source.getWidth() || height != source.getHeight())
		{
			scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(source, 0, 0, width, height, null);
			g.dispose();
		}

		byte[] jpeg;
		try
		{
			if (jpegEncoder == null)
			{
				jpegEncoder = new JpegEncoder();
			}
			jpeg = jpegEncoder.encode(scaled);
		}
		catch (IOException e)
		{
			log.debug("Unable to compress frame", e);
			return;
		}

		// A clip has one frame size, so start over when the client is resized
		CapturedFrame last = buffer.peekLast();
		if (last != null && (last.getWidth() != width || last.getHeight() != height))
		{
			clearBuffer();
		}

		buffer.addLast(new CapturedFrame(time, tick, width, height, jpeg));
		bufferBytes += jpeg.length;
		trim(time);
	}

	private void trim(long now)
	{
		long keepFrom = now - config.windowSeconds() * 1000L - EXTRA_MILLIS;
		Iterator<CapturedFrame> it = buffer.iterator();
		while (it.hasNext())
		{
			CapturedFrame frame = it.next();
			if (frame.getTimeMillis() >= keepFrom && bufferBytes <= MAX_BUFFER_BYTES)
			{
				break;
			}
			bufferBytes -= frame.getJpeg().length;
			it.remove();
		}
	}

	private void clearBuffer()
	{
		buffer.clear();
		bufferBytes = 0;
	}

	private boolean submit(Runnable task)
	{
		try
		{
			executor.execute(task);
			return true;
		}
		catch (RejectedExecutionException e)
		{
			// plugin is shutting down
			return false;
		}
	}
}
