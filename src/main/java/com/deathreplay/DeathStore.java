package com.deathreplay;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Reads and writes death records. Does disk IO, so never call it from the
 * client thread.
 */
@Slf4j
class DeathStore
{
	private static final String EXTENSION = ".json";
	private static final String CLIP_EXTENSION = ".avi";

	private final Filepath dir;
	private final Gson gson;

	DeathStore(Filepath pluginDirectory, Gson gson) throws IOException
	{
		this.dir = pluginDirectory.joinSegment("deaths");
		this.dir.createDirectories();
		this.gson = gson;
	}

	/**
	 * @return saved deaths, newest first
	 */
	List<DeathRecord> loadAll()
	{
		List<DeathRecord> records = new ArrayList<>();
		for (Filepath file : files())
		{
			try (Reader reader = file.openBufferedReader())
			{
				DeathRecord record = gson.fromJson(reader, DeathRecord.class);
				if (record != null && record.getId() != null)
				{
					records.add(record);
				}
			}
			catch (IOException | JsonParseException e)
			{
				log.debug("Skipping unreadable death file {}", file.getFileName(), e);
			}
		}
		records.sort(Comparator.comparingLong(DeathRecord::getTimeMillis).reversed());
		return records;
	}

	void save(DeathRecord record) throws IOException
	{
		dir.joinSegment(record.getId() + EXTENSION).write(gson.toJson(record));
	}

	ClipInfo writeClip(String id, List<CapturedFrame> frames) throws IOException
	{
		String fileName = id + CLIP_EXTENSION;
		List<Long> offsets;
		try (OutputStream out = new BufferedOutputStream(dir.joinSegment(fileName).openOutputStream(), 1 << 16))
		{
			offsets = ClipWriter.write(out, frames);
		}

		ClipInfo clip = new ClipInfo();
		clip.setFile(fileName);
		clip.setWidth(frames.get(0).getWidth());
		clip.setHeight(frames.get(0).getHeight());
		for (int i = 0; i < frames.size(); i++)
		{
			CapturedFrame frame = frames.get(i);
			clip.getFrames().add(new ClipFrame(frame.getTimeMillis(), frame.getTick(), offsets.get(i), frame.getJpeg().length));
		}
		return clip;
	}

	/**
	 * @return each frame's JPEG bytes, or null if the clip file is gone
	 */
	List<byte[]> readClip(ClipInfo clip) throws IOException
	{
		Filepath file = dir.joinSegment(clip.getFile());
		if (!file.exists())
		{
			return null;
		}

		List<byte[]> frames = new ArrayList<>(clip.getFrames().size());
		try (FileChannel channel = file.openFileChannel(StandardOpenOption.READ))
		{
			for (ClipFrame frame : clip.getFrames())
			{
				ByteBuffer buffer = ByteBuffer.allocate(frame.getLength());
				long position = frame.getOffset();
				while (buffer.hasRemaining())
				{
					int read = channel.read(buffer, position + buffer.position());
					if (read < 0)
					{
						throw new IOException("clip file is truncated");
					}
				}
				frames.add(buffer.array());
			}
		}
		return frames;
	}

	boolean clipExists(ClipInfo clip)
	{
		return clip != null && dir.joinSegment(clip.getFile()).exists();
	}

	void delete(String id) throws IOException
	{
		dir.joinSegment(id + EXTENSION).deleteIfExists();
		dir.joinSegment(id + CLIP_EXTENSION).deleteIfExists();
	}

	/**
	 * Deletes the oldest deaths so at most {@code keep} remain.
	 *
	 * @return ids of the deleted deaths
	 */
	List<String> prune(int keep) throws IOException
	{
		List<String> deleted = new ArrayList<>();
		for (String id : oldestFirstBeyond(files(EXTENSION), EXTENSION, keep))
		{
			delete(id);
			deleted.add(id);
		}
		return deleted;
	}

	/**
	 * Deletes the oldest videos so at most {@code keep} remain. The deaths'
	 * data is kept.
	 */
	void pruneClips(int keep) throws IOException
	{
		for (String id : oldestFirstBeyond(files(CLIP_EXTENSION), CLIP_EXTENSION, keep))
		{
			dir.joinSegment(id + CLIP_EXTENSION).deleteIfExists();
		}
	}

	/**
	 * Copies a death's video to a file the user picked.
	 */
	void exportClip(ClipInfo clip, Filepath target) throws IOException
	{
		dir.joinSegment(clip.getFile()).copyTo(target, StandardCopyOption.REPLACE_EXISTING);
	}

	private static List<String> oldestFirstBeyond(List<Filepath> files, String extension, int keep)
	{
		// ids are timestamps, so name order is time order
		files.sort(Comparator.comparing(Filepath::getFileName).reversed());
		List<String> ids = new ArrayList<>();
		for (int i = Math.max(0, keep); i < files.size(); i++)
		{
			String name = files.get(i).getFileName();
			ids.add(name.substring(0, name.length() - extension.length()));
		}
		return ids;
	}

	private List<Filepath> files()
	{
		return files(EXTENSION);
	}

	private List<Filepath> files(String extension)
	{
		try (Stream<Filepath> stream = dir.walk(1))
		{
			return stream
				.filter(f -> f.isFile() && f.getFileName().endsWith(extension))
				.collect(Collectors.toCollection(ArrayList::new));
		}
		catch (IOException e)
		{
			log.warn("Unable to list saved deaths", e);
			return new ArrayList<>();
		}
	}
}
