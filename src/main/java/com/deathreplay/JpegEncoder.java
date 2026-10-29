package com.deathreplay;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.w3c.dom.NodeList;

/**
 * Compresses frames to JPEG. Not thread safe; use one per thread.
 */
class JpegEncoder
{
	private static final float QUALITY = 0.9f;
	private static final String METADATA_FORMAT = "javax_imageio_jpeg_image_1.0";

	private final ImageWriter writer;
	private final ImageWriteParam param;

	JpegEncoder()
	{
		writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(QUALITY);
	}

	byte[] encode(BufferedImage image) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
		// memory-backed so ImageIO doesn't create temp files
		try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out))
		{
			writer.setOutput(stream);
			writer.write(null, new IIOImage(image, null, fullColourMetadata(image)), param);
		}
		finally
		{
			writer.reset();
		}
		return out.toByteArray();
	}

	void dispose()
	{
		writer.dispose();
	}

	/**
	 * By default Java stores colour at half resolution (4:2:0), which smears the
	 * game's bright red and yellow text and hitsplats. This keeps full colour (4:4:4).
	 */
	private IIOMetadata fullColourMetadata(BufferedImage image) throws IOException
	{
		IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);
		IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(METADATA_FORMAT);
		NodeList components = root.getElementsByTagName("componentSpec");
		for (int i = 0; i < components.getLength(); i++)
		{
			IIOMetadataNode component = (IIOMetadataNode) components.item(i);
			component.setAttribute("HsamplingFactor", "1");
			component.setAttribute("VsamplingFactor", "1");
		}
		metadata.setFromTree(METADATA_FORMAT, root);
		return metadata;
	}
}
