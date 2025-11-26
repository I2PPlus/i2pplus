package com.mpatric.mp3agic;

/**
 * Abstract base class for ID3v2 frame data.
 */
public abstract class AbstractID3v2FrameData {

	/** Unsynchronization flag. */
	boolean unsynchronisation;

	/**
	 * Construct frame data.
	 */
	public AbstractID3v2FrameData(boolean unsynchronisation) {
		this.unsynchronisation = unsynchronisation;
	}

	/**
	 * Synchronize and unpack frame data.
	 */
	protected final void synchroniseAndUnpackFrameData(byte[] bytes) throws InvalidDataException {
		if (unsynchronisation && BufferTools.sizeSynchronisationWouldSubtract(bytes) > 0) {
			byte[] synchronisedBytes = BufferTools.synchroniseBuffer(bytes);
			unpackFrameData(synchronisedBytes);
		} else {
			unpackFrameData(bytes);
		}
	}

	/**
	 * Pack and unsynchronize frame data.
	 */
	protected byte[] packAndUnsynchroniseFrameData() {
		byte[] bytes = packFrameData();
		if (unsynchronisation && BufferTools.sizeUnsynchronisationWouldAdd(bytes) > 0) {
			return BufferTools.unsynchroniseBuffer(bytes);
		}
		return bytes;
	}

	/**
	 * Convert frame data to bytes.
	 */
	protected byte[] toBytes() {
		return packAndUnsynchroniseFrameData();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (unsynchronisation ? 1231 : 1237);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AbstractID3v2FrameData other = (AbstractID3v2FrameData) obj;
		if (unsynchronisation != other.unsynchronisation)
			return false;
		return true;
	}

	/**
	 * Unpack frame data from bytes.
	 */
	protected abstract void unpackFrameData(byte[] bytes) throws InvalidDataException;

	/**
	 * Pack frame data to bytes.
	 */
	protected abstract byte[] packFrameData();

	/**
	 * Get length of frame data.
	 */
	protected abstract int getLength();
}
