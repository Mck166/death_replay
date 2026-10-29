package com.deathreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.Test;

public class JpegEncoderTest
{
	private static BufferedImage hitsplat(int width, int height)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(60, 60, 60));
		g.fillRect(0, 0, width, height);
		// one-pixel red lines, like hitsplat and HP bar edges
		g.setColor(Color.RED);
		for (int x = 0; x < width; x += 4)
		{
			g.drawLine(x, 0, x, height);
		}
		g.dispose();
		return image;
	}

	/**
	 * Reads each component's sampling factors from the JPEG start-of-frame header.
	 */
	private static int[] samplingFactors(byte[] jpeg)
	{
		for (int i = 2; i + 1 < jpeg.length; i++)
		{
			int marker = jpeg[i + 1] & 0xFF;
			if ((jpeg[i] & 0xFF) == 0xFF && (marker == 0xC0 || marker == 0xC1 || marker == 0xC2))
			{
				int components = jpeg[i + 9] & 0xFF;
				int[] factors = new int[components];
				for (int c = 0; c < components; c++)
				{
					factors[c] = jpeg[i + 11 + c * 3] & 0xFF;
				}
				return factors;
			}
		}
		throw new AssertionError("no start-of-frame marker");
	}

	@Test
	public void keepsFullColourResolution() throws IOException
	{
		JpegEncoder encoder = new JpegEncoder();
		try
		{
			// same encoder across sizes, as when the client is resized
			for (int[] size : new int[][]{{957, 667}, {64, 36}})
			{
				byte[] jpeg = encoder.encode(hitsplat(size[0], size[1]));
				int[] factors = samplingFactors(jpeg);
				assertEquals(3, factors.length);
				for (int factor : factors)
				{
					// 0x11 = 1x1 sampling, i.e. 4:4:4
					assertEquals(0x11, factor);
				}

				BufferedImage decoded = ReplayViewer.decode(jpeg);
				assertNotNull(decoded);
				assertEquals(size[0], decoded.getWidth());
				assertEquals(size[1], decoded.getHeight());

				// a red line pixel should stay clearly red, not smeared into the grey
				Color pixel = new Color(decoded.getRGB(4, 10));
				assertTrue("red was " + pixel, pixel.getRed() > 200 && pixel.getGreen() < 90);
			}
		}
		finally
		{
			encoder.dispose();
		}
	}
}
