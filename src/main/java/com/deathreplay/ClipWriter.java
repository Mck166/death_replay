package com.deathreplay;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes frames as an MJPEG .avi, which plays in VLC, Windows Media Player
 * and most editors without any extra software.
 */
final class ClipWriter
{
	private static final int AVIF_HASINDEX = 0x10;
	private static final int AVIIF_KEYFRAME = 0x10;
	private static final int AVIH_SIZE = 56;
	private static final int STRH_SIZE = 56;
	private static final int STRF_SIZE = 40;
	private static final int STRL_SIZE = 4 + (8 + STRH_SIZE) + (8 + STRF_SIZE);
	private static final int HDRL_SIZE = 4 + (8 + AVIH_SIZE) + (8 + STRL_SIZE);
	/**
	 * Bytes before the first frame chunk: RIFF header, hdrl list, movi list header.
	 */
	private static final int MOVI_START = 12 + (8 + HDRL_SIZE) + 12;

	private ClipWriter()
	{
	}

	/**
	 * @return where each frame's JPEG data starts in the file, in frame order
	 */
	static List<Long> write(OutputStream out, List<CapturedFrame> frames) throws IOException
	{
		if (frames.isEmpty())
		{
			throw new IllegalArgumentException("no frames");
		}

		int width = frames.get(0).getWidth();
		int height = frames.get(0).getHeight();
		int count = frames.size();

		long moviSize = 4;
		int largest = 0;
		for (CapturedFrame frame : frames)
		{
			int length = frame.getJpeg().length;
			moviSize += 8 + length + (length & 1);
			largest = Math.max(largest, length);
		}
		long idxSize = 16L * count;
		long riffSize = 4 + (8 + HDRL_SIZE) + (8 + moviSize) + (8 + idxSize);
		if (riffSize > 0xFFFFFFFFL)
		{
			throw new IOException("clip too large for AVI");
		}

		// average rate over the clip; capture timing varies a little frame to frame
		long duration = frames.get(count - 1).getTimeMillis() - frames.get(0).getTimeMillis();
		double fps = count > 1 && duration > 0 ? (count - 1) * 1000.0 / duration : 15;
		int rate = (int) Math.round(fps * 1000);

		ByteBuffer header = ByteBuffer.allocate(MOVI_START).order(ByteOrder.LITTLE_ENDIAN);
		fourcc(header, "RIFF");
		header.putInt((int) riffSize);
		fourcc(header, "AVI ");

		fourcc(header, "LIST");
		header.putInt(HDRL_SIZE);
		fourcc(header, "hdrl");

		fourcc(header, "avih");
		header.putInt(AVIH_SIZE);
		header.putInt((int) Math.round(1_000_000 / fps)); // microseconds per frame
		header.putInt((int) Math.round(largest * fps)); // max bytes per second
		header.putInt(0); // padding granularity
		header.putInt(AVIF_HASINDEX);
		header.putInt(count);
		header.putInt(0); // initial frames
		header.putInt(1); // streams
		header.putInt(largest); // suggested buffer size
		header.putInt(width);
		header.putInt(height);
		header.putInt(0).putInt(0).putInt(0).putInt(0); // reserved

		fourcc(header, "LIST");
		header.putInt(STRL_SIZE);
		fourcc(header, "strl");

		fourcc(header, "strh");
		header.putInt(STRH_SIZE);
		fourcc(header, "vids");
		fourcc(header, "MJPG");
		header.putInt(0); // flags
		header.putShort((short) 0); // priority
		header.putShort((short) 0); // language
		header.putInt(0); // initial frames
		header.putInt(1000); // scale
		header.putInt(rate); // rate; rate / scale = fps
		header.putInt(0); // start
		header.putInt(count); // length
		header.putInt(largest); // suggested buffer size
		header.putInt(-1); // quality
		header.putInt(0); // sample size
		header.putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);

		fourcc(header, "strf");
		header.putInt(STRF_SIZE);
		header.putInt(STRF_SIZE); // BITMAPINFOHEADER size
		header.putInt(width);
		header.putInt(height);
		header.putShort((short) 1); // planes
		header.putShort((short) 24); // bit count
		fourcc(header, "MJPG");
		header.putInt(width * height * 3); // image size
		header.putInt(0).putInt(0).putInt(0).putInt(0); // pels per meter x/y, colours used/important

		fourcc(header, "LIST");
		header.putInt((int) moviSize);
		fourcc(header, "movi");
		out.write(header.array());

		List<Long> offsets = new ArrayList<>(count);
		ByteBuffer index = ByteBuffer.allocate((int) (8 + idxSize)).order(ByteOrder.LITTLE_ENDIAN);
		fourcc(index, "idx1");
		index.putInt((int) idxSize);

		ByteBuffer chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		long position = MOVI_START;
		for (CapturedFrame frame : frames)
		{
			byte[] jpeg = frame.getJpeg();
			chunk.clear();
			fourcc(chunk, "00dc");
			chunk.putInt(jpeg.length);
			out.write(chunk.array());
			out.write(jpeg);
			if ((jpeg.length & 1) != 0)
			{
				out.write(0);
			}

			// idx1 offsets are relative to the "movi" fourcc
			fourcc(index, "00dc");
			index.putInt(AVIIF_KEYFRAME);
			index.putInt((int) (position - (MOVI_START - 4)));
			index.putInt(jpeg.length);

			offsets.add(position + 8);
			position += 8 + jpeg.length + (jpeg.length & 1);
		}

		out.write(index.array());
		return offsets;
	}

	private static void fourcc(ByteBuffer buffer, String code)
	{
		buffer.put(code.getBytes(StandardCharsets.US_ASCII));
	}
}
