package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayChunkCache;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces Flashback's eager decoded chunk cache with an indexed, disk-backed cache.
 *
 * <p>The upstream implementation decodes every packet in a cache group (up to 10,000 chunks) when
 * just one entry is requested. Large modpacks retain enormous block palettes and block-entity tags,
 * so that temporary list can consume the remainder of a 16 GiB heap before the first snapshot has
 * even finished. It also omits a list element when one packet fails to decode, shifting every later
 * cache id onto the wrong chunk. Keeping exact file offsets avoids both failure modes.</p>
 */
@Mixin(ReplayChunkCache.class)
public abstract class MixinReplayChunkCacheLazy {

    @Unique
    private static final Logger FLASHBACK_NEOFORGE_FIXED$LOGGER =
            LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    @Unique
    private static final int FLASHBACK_NEOFORGE_FIXED$GROUP_SIZE = 10_000;
    @Unique
    private static final int FLASHBACK_NEOFORGE_FIXED$MAX_PACKET_BYTES = 64 * 1024 * 1024;

    @Shadow
    @Final
    private FileSystem playbackFileSystem;

    @Unique
    private Map<Integer, FlashbackNeoForgeFixed$CacheIndex>
            flashbackNeoForgeFixed$cacheIndexes;
    @Unique
    private Set<Integer> flashbackNeoForgeFixed$missingCacheGroups;

    @Inject(method = "getOrLoad", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$loadOnlyRequestedChunk(
            int cacheId,
            RegistryAccess registryAccess,
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> packetCodec,
            CallbackInfoReturnable<ClientboundLevelChunkWithLightPacket> cir) {
        cir.setReturnValue(this.flashbackNeoForgeFixed$getOrLoad(
                cacheId, registryAccess, packetCodec));
    }

    @Unique
    private synchronized ClientboundLevelChunkWithLightPacket flashbackNeoForgeFixed$getOrLoad(
            int cacheId,
            RegistryAccess registryAccess,
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> packetCodec) {
        if (cacheId < 0) {
            FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                    "Replay requested negative chunk cache id {}", cacheId);
            return null;
        }

        int group = cacheId / FLASHBACK_NEOFORGE_FIXED$GROUP_SIZE;
        int entry = cacheId - group * FLASHBACK_NEOFORGE_FIXED$GROUP_SIZE;
        Map<Integer, FlashbackNeoForgeFixed$CacheIndex> indexes =
                this.flashbackNeoForgeFixed$indexes();
        FlashbackNeoForgeFixed$CacheIndex index = indexes.get(group);
        if (index == null) {
            if (this.flashbackNeoForgeFixed$missingGroups().contains(group)) {
                return null;
            }
            Path source = this.playbackFileSystem.getPath("/level_chunk_caches/" + group);
            if (!Files.exists(source)) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                        "Chunk cache id {} references missing file {}", cacheId, source);
                this.flashbackNeoForgeFixed$missingGroups().add(group);
                return null;
            }
            try {
                index = FlashbackNeoForgeFixed$CacheIndex.open(source, group);
                indexes.put(group, index);
            } catch (IOException | RuntimeException exception) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                        "Failed to index replay chunk cache {}", source, exception);
                return null;
            }
        }

        if (entry >= index.size()) {
            FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                    "Chunk cache id {} is outside {} ({} entries)",
                    cacheId, index.source(), index.size());
            return null;
        }

        try {
            byte[] encoded = index.read(entry);
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                    Unpooled.wrappedBuffer(encoded), registryAccess);
            try {
                Packet<? super ClientGamePacketListener> packet = packetCodec.decode(buffer);
                if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                    return chunkPacket;
                }
                FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                        "Chunk cache id {} decoded as {} instead of a level chunk packet",
                        cacheId, packet == null ? "null" : packet.getClass().getName());
            } finally {
                buffer.release();
            }
        } catch (IOException | RuntimeException exception) {
            // Do not remove an entry from the logical index. The upstream list loader did that and
            // consequently shifted every later cache id onto an unrelated chunk.
            FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                    "Failed to decode replay chunk cache id {} from {}",
                    cacheId, index.source(), exception);
        }
        return null;
    }

    @Unique
    private Map<Integer, FlashbackNeoForgeFixed$CacheIndex> flashbackNeoForgeFixed$indexes() {
        if (this.flashbackNeoForgeFixed$cacheIndexes == null) {
            this.flashbackNeoForgeFixed$cacheIndexes = new HashMap<>();
        }
        return this.flashbackNeoForgeFixed$cacheIndexes;
    }

    @Unique
    private Set<Integer> flashbackNeoForgeFixed$missingGroups() {
        if (this.flashbackNeoForgeFixed$missingCacheGroups == null) {
            this.flashbackNeoForgeFixed$missingCacheGroups = new HashSet<>();
        }
        return this.flashbackNeoForgeFixed$missingCacheGroups;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$closeDiskIndexes(CallbackInfo ci) {
        if (this.flashbackNeoForgeFixed$cacheIndexes != null) {
            for (FlashbackNeoForgeFixed$CacheIndex index
                    : this.flashbackNeoForgeFixed$cacheIndexes.values()) {
                index.close();
            }
            this.flashbackNeoForgeFixed$cacheIndexes.clear();
        }
        if (this.flashbackNeoForgeFixed$missingCacheGroups != null) {
            this.flashbackNeoForgeFixed$missingCacheGroups.clear();
        }
    }

    @Unique
    private static final class FlashbackNeoForgeFixed$CacheIndex {

        private final String source;
        private final Path temporaryFile;
        private final FileChannel channel;
        private final long[] offsets;
        private final int[] lengths;

        private FlashbackNeoForgeFixed$CacheIndex(
                String source,
                Path temporaryFile,
                FileChannel channel,
                long[] offsets,
                int[] lengths) {
            this.source = source;
            this.temporaryFile = temporaryFile;
            this.channel = channel;
            this.offsets = offsets;
            this.lengths = lengths;
        }

        private static FlashbackNeoForgeFixed$CacheIndex open(Path source, int group)
                throws IOException {
            Path temporary = Files.createTempFile(
                    "flashback-neoforge-fixed-chunks-" + group + "-", ".cache");
            boolean complete = false;
            try {
                // Files.copy on ZipFS implementations is allowed to buffer the entire compressed
                // entry. An explicit stream transfer keeps memory bounded while materializing a
                // seekable file once for the lifetime of this replay.
                try (InputStream input = Files.newInputStream(source);
                        OutputStream output = Files.newOutputStream(
                                temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                    input.transferTo(output);
                }

                FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ);
                try {
                    long fileBytes = channel.size();
                    List<Long> offsetList = new ArrayList<>();
                    List<Integer> lengthList = new ArrayList<>();
                    ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES);
                    long cursor = 0;
                    while (cursor < fileBytes) {
                        if (fileBytes - cursor < Integer.BYTES) {
                            throw new IOException("Truncated chunk cache length at byte " + cursor);
                        }
                        lengthBuffer.clear();
                        readFully(channel, lengthBuffer, cursor);
                        lengthBuffer.flip();
                        int length = lengthBuffer.getInt();
                        long dataOffset = cursor + Integer.BYTES;
                        if (length < 0 || length > FLASHBACK_NEOFORGE_FIXED$MAX_PACKET_BYTES) {
                            throw new IOException("Invalid chunk packet length " + length
                                    + " at byte " + cursor);
                        }
                        if (dataOffset + length > fileBytes) {
                            throw new IOException("Truncated chunk packet at byte " + cursor
                                    + ": needed " + length + " byte(s), only "
                                    + (fileBytes - dataOffset) + " remain");
                        }
                        offsetList.add(dataOffset);
                        lengthList.add(length);
                        cursor = dataOffset + length;
                    }

                    long[] offsets = new long[offsetList.size()];
                    int[] lengths = new int[lengthList.size()];
                    for (int i = 0; i < offsets.length; i++) {
                        offsets[i] = offsetList.get(i);
                        lengths[i] = lengthList.get(i);
                    }
                    complete = true;
                    return new FlashbackNeoForgeFixed$CacheIndex(
                            source.toString(), temporary, channel, offsets, lengths);
                } finally {
                    if (!complete) {
                        channel.close();
                    }
                }
            } finally {
                if (!complete) {
                    Files.deleteIfExists(temporary);
                }
            }
        }

        private byte[] read(int entry) throws IOException {
            int length = this.lengths[entry];
            byte[] bytes = new byte[length];
            readFully(this.channel, ByteBuffer.wrap(bytes), this.offsets[entry]);
            return bytes;
        }

        private static void readFully(FileChannel channel, ByteBuffer buffer, long position)
                throws IOException {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    throw new IOException("Unexpected end of replay chunk cache");
                }
                if (read == 0) {
                    throw new IOException("Could not make progress reading replay chunk cache");
                }
                position += read;
            }
        }

        private int size() {
            return this.offsets.length;
        }

        private String source() {
            return this.source;
        }

        private void close() {
            try {
                this.channel.close();
            } catch (IOException exception) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.warn(
                        "Failed to close temporary replay chunk cache {}",
                        this.temporaryFile, exception);
            }
            try {
                Files.deleteIfExists(this.temporaryFile);
            } catch (IOException exception) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.warn(
                        "Failed to delete temporary replay chunk cache {}",
                        this.temporaryFile, exception);
            }
        }
    }
}
