/*
 * Copyright 2014 shevek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client.io;

import com.turn.ttorrent.common.Torrent;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import javax.annotation.Nonnull;
import org.apache.commons.io.Charsets;

/**
 *
 * @author shevek
 */
public class HandshakeMessage extends PeerMessage {

    public static final int BASE_HANDSHAKE_LENGTH = 49;
    public static final byte[] BITTORRENT_PROTOCOL_IDENTIFIER = "BitTorrent protocol".getBytes(Torrent.BYTE_ENCODING);
    private byte[] protocolName;
    private final byte[] reserved = new byte[8];
    private byte[] infoHash; // 20
    private byte[] peerId; // 20

    public HandshakeMessage() {
    }

    public HandshakeMessage(@Nonnull byte[] infoHash, @Nonnull byte[] peerId) {
        this.protocolName = BITTORRENT_PROTOCOL_IDENTIFIER;
        this.infoHash = infoHash;
        this.peerId = peerId;
    }

    @Override
    public Type getType() {
        return Type.HANDSHAKE;
    }

    public byte[] getInfoHash() {
        return infoHash;
    }

    public byte[] getPeerId() {
        return peerId;
    }

    @Override
    public void fromWire(ByteBuf in) {
        int pstrlen = in.readUnsignedByte();
        if (pstrlen < 0 || in.readableBytes() != BASE_HANDSHAKE_LENGTH + pstrlen - 1)
            throw new IllegalArgumentException("Incorrect handshake message length (pstrlen=" + pstrlen + ") !");

        // Check the protocol identification string
        protocolName = new byte[pstrlen];
        in.readBytes(protocolName);
        if (!Arrays.equals(protocolName, HandshakeMessage.BITTORRENT_PROTOCOL_IDENTIFIER))
            throw new IllegalArgumentException("Unknown protocol " + new String(protocolName, Charsets.ISO_8859_1));

        // Ignore reserved bytes
        in.readBytes(reserved);

        infoHash = new byte[20];
        in.readBytes(infoHash);
        peerId = new byte[20];
        in.readBytes(peerId);
    }

    @Override
    public void toWire(ByteBuf out) {
        out.writeByte(protocolName.length);
        out.writeBytes(protocolName);
        out.writeBytes(reserved);
        out.writeBytes(infoHash);
        out.writeBytes(peerId);
    }

    private String toHex(byte[] data) {
        if (data == null)
            return null;
        return Torrent.byteArrayToHexString(data);
    }

    @Override
    public String toString() {
        return super.toString() + " P=" + toHex(getPeerId()) + " T=" + toHex(getInfoHash());
    }
}