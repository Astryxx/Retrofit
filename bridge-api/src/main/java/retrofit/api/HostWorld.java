package retrofit.api;

/**
 * One host (1.20.1) level, as seen by the legacy bridge. Implemented by the host; the legacy
 * {@code World} subclass forwards its block and tile entity methods here.
 *
 * Legacy blocks and tile entities are passed as opaque {@code Object}s: the host never needs
 * their types, only their identity.
 */
public interface HostWorld {
    /** The legacy Block at a position: a legacy mod block, or the legacy equivalent of a host block. */
    Object getLegacyBlock(int x, int y, int z);

    int getLegacyMeta(int x, int y, int z);

    /**
     * Places a legacy block. Returns false if the host has no equivalent for it.
     *
     * @param flags legacy update flags (1 = notify neighbours, 2 = send to clients)
     */
    boolean setLegacyBlock(int x, int y, int z, Object legacyBlock, int meta, int flags);

    /** The legacy TileEntity hosted at a position, or null. */
    Object getLegacyTileEntity(int x, int y, int z);

    void markBlockForUpdate(int x, int y, int z);

    void markTileEntityChanged(int x, int y, int z);

    long getTotalWorldTime();

    boolean isClientSide();
}
