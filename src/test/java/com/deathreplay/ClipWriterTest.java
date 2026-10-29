package com.deathreplay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ClipWriterTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static byte[] jpeg(Color color, int width, int height) throws IOException
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", out);
		return out.toByteArray();
	}

	private static List<CapturedFrame> frames() throws IOException
	{
		List<CapturedFrame> frames = new ArrayList<>();
		Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE};
		for (int i = 0; i < colors.length; i++)
		{
			// 100ms apart = 10 fps
			frames.add(new CapturedFrame(1_000 + i * 100L, 50 + i, 64, 36, jpeg(colors[i], 64, 36)));
		}
		return frames;
	}

	private static String fourcc(ByteBuffer buffer, int position)
	{
		byte[] bytes = new byte[4];
		for (int i = 0; i < 4; i++)
		{
			bytes[i] = buffer.get(position + i);
		}
		return new String(bytes, StandardCharsets.US_ASCII);
	}

	@Test
	public void writesValidAvi() throws IOException
	{
		List<CapturedFrame> frames = frames();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Long> offsets = ClipWriter.write(out, frames);
		ByteBuffer avi = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);

		assertEquals("RIFF", fourcc(avi, 0));
		assertEquals(avi.capacity() - 8, avi.getInt(4));
		assertEquals("AVI ", fourcc(avi, 8));
		assertEquals("hdrl", fourcc(avi, 20));
		assertEquals("avih", fourcc(avi, 24));
		assertEquals(100_000, avi.getInt(32)); // microseconds per frame at 10 fps
		assertEquals(4, avi.getInt(48)); // total frames
		assertEquals(64, avi.getInt(64)); // width
		assertEquals(36, avi.getInt(68)); // height

		for (int i = 0; i < frames.size(); i++)
		{
			int offset = offsets.get(i).intValue();
			assertEquals("00dc", fourcc(avi, offset - 8));
			assertEquals(frames.get(i).getJpeg().length, avi.getInt(offset - 4));
			// JPEG start-of-image marker
			assertEquals((byte) 0xFF, avi.get(offset));
			assertEquals((byte) 0xD8, avi.get(offset + 1));
		}

		int idx = avi.capacity() - 8 - 16 * frames.size();
		assertEquals("idx1", fourcc(avi, idx));
		// first index entry points 4 bytes past the "movi" fourcc
		assertEquals(4, avi.getInt(idx + 8 + 8));
	}

	@Test
	public void storeRoundTrip() throws IOException
	{
		Path root = folder.getRoot().toPath();
		DeathStore store = new DeathStore(Filepath.Unchecked.getRooted(root), new Gson());
		List<CapturedFrame> frames = frames();

		ClipInfo clip = store.writeClip("2026-10-26_12-00-00-000", frames);
		assertEquals(4, clip.getFrames().size());
		assertEquals(53, clip.getFrames().get(3).getTick());

		List<byte[]> read = store.readClip(clip);
		for (int i = 0; i < frames.size(); i++)
		{
			assertArrayEquals(frames.get(i).getJpeg(), read.get(i));
		}

		// what the replay window shows
		BufferedImage decoded = ReplayViewer.decode(read.get(0));
		assertNotNull(decoded);
		assertEquals(64, decoded.getWidth());
		assertEquals(36, decoded.getHeight());

		// "Save video..." copies the whole .avi elsewhere
		Path export = folder.newFolder("export").toPath();
		store.exportClip(clip, Filepath.Unchecked.getRooted(export).joinSegment("saved.avi"));
		assertArrayEquals(Files.readAllBytes(root.resolve("deaths").resolve(clip.getFile())), Files.readAllBytes(export.resolve("saved.avi")));

		store.pruneClips(0);
		assertNull(store.readClip(clip));
		assertEquals(0, Files.list(root.resolve("deaths")).count());
	}
}
