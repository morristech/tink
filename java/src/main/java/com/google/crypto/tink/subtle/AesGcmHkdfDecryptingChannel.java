// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.subtle;

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming.AesGcmHkdfStreamDecrypter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * An instance of a ReadableByteChannel that returns the plaintext for some ciphertext.
 */
class AesGcmHkdfDecryptingChannel implements ReadableByteChannel {
  /* The stream containing the ciphertext */
  private ReadableByteChannel ciphertextChannel;

  /**
   * A buffer containing ciphertext that has not yet been decrypted.
   * The limit of ciphertextSegment is set such that it can contain segment plus the first
   * character of the next segment. It is necessary to read a segment plus one more byte
   * to decrypt a segment, since the last segment of a ciphertext is encrypted differently.
   */
  private ByteBuffer ciphertextSegment;

  /**
   * A buffer containing a plaintext segment.
   * The bytes in the range plaintexSegment.position() .. plaintextSegment.limit() - 1
   * are plaintext that have been decrypted but not yet read out of AesGcmInputStream.
   */
  private ByteBuffer plaintextSegment;

  /* A buffer containg the header information from the ciphertext. */
  private ByteBuffer header;

  /* Determines whether the header has been completely read. */
  private boolean headerRead;

  /* Indicates whether the end of this InputStream has been reached. */
  private boolean endOfCiphertext;

  /* Indicates whether the end of the plaintext has been reached. */
  private boolean endOfPlaintext;

  /**
   * Indicates whether this stream is in a defined state.
   * Currently the state of this instance becomes undefined when
   * an authentication error has occured.
   */
  private boolean definedState;

  /**
   * The position in the plaintext. This is the same as the number of bytes
   * alread read this.
   */
  private long plaintextPosition;

  /**
   * The additional data that is authenticated with the ciphertext.
   */
  private byte[] aad;

  /**
   * The number of the current segment of ciphertext buffered in ciphertexSegment.
   */
  private int segmentNr;

  private final AesGcmHkdfStreamDecrypter decrypter;
  private final int plaintextSegmentSize;
  private final int ciphertextSegmentSize;
  private final int ciphertextOffset;

  public AesGcmHkdfDecryptingChannel(
      AesGcmHkdfStreamDecrypter decrypter,
      ReadableByteChannel ciphertextChannel,
      byte[] associatedData,
      int plaintextSegmentSize,
      int ciphertextSegmentSize,
      int ciphertextOffset,
      int headerLength)
      throws GeneralSecurityException, IOException {
    this.decrypter = decrypter;
    this.ciphertextChannel = ciphertextChannel;
    header = ByteBuffer.allocate(headerLength);
    aad = Arrays.copyOf(associatedData, associatedData.length);

    // ciphertextSegment is one byte longer than a ciphertext segment,
    // so that the code can decide if the current segment is the last segment in the
    // stream.
    this.ciphertextSegmentSize = ciphertextSegmentSize;
    ciphertextSegment = ByteBuffer.allocate(ciphertextSegmentSize + 1);
    ciphertextSegment.limit(0);
    this.plaintextSegmentSize = plaintextSegmentSize;
    plaintextSegment = ByteBuffer.allocate(plaintextSegmentSize);
    plaintextSegment.limit(0);
    this.ciphertextOffset = ciphertextOffset;
    plaintextPosition = 0;
    headerRead = false;
    endOfCiphertext = false;
    endOfPlaintext = false;
    segmentNr = 0;
    definedState = true;
  }

  /**
   * Reads some ciphertext.
   * @param buffer the destination for the ciphertext.
   * @throws IOException when an exception reading the ciphertext stream occurs.
   */
  private void readSomeCiphertext(ByteBuffer buffer) throws IOException {
    int read;
    do {
      read = ciphertextChannel.read(buffer);
    } while (read > 0 && buffer.remaining() > 0);
    if (read == -1) {
      endOfCiphertext = true;
    }
  }

  /**
   * Tries to read the header of the ciphertext.
   * @returns true if the header has been fully read and false if not enogh bytes were available
   *          from the ciphertext stream.
   * @throws IOException when an exception occurs while reading the ciphertextStream or when
   *         the header is too short.
   */
  private boolean tryReadHeader() throws IOException {
    if (endOfCiphertext) {
      throw new IOException("Ciphertext is too short");
    }
    readSomeCiphertext(header);
    if (header.remaining() > 0) {
      return false;
    } else {
      header.flip();
      try {
        decrypter.init(header, aad);
        headerRead = true;
      } catch (GeneralSecurityException ex) {
        // TODO(bleichen): Try to define the state of this.
        setUndefinedState();
        throw new IOException(ex);
      }
      return true;
    }
  }

  private void setUndefinedState() {
    definedState = false;
    plaintextSegment.limit(0);
  }

  /**
   * Tries to load the next plaintext segment.
   */
  private boolean tryLoadSegment() throws IOException {
    // Try filling the ciphertextSegment
    if (!endOfCiphertext) {
      readSomeCiphertext(ciphertextSegment);
    }
    if (ciphertextSegment.remaining() > 0 && !endOfCiphertext) {
      // we have not enough ciphertext for the next segment
      return false;
    }
    byte lastByte = 0;
    if (!endOfCiphertext) {
      lastByte = ciphertextSegment.get(ciphertextSegment.position() - 1);
      ciphertextSegment.position(ciphertextSegment.position() - 1);
    }
    ciphertextSegment.flip();
    plaintextSegment.clear();
    try {
      decrypter.decryptSegment(
          ciphertextSegment, segmentNr, endOfCiphertext, plaintextSegment);
    } catch (GeneralSecurityException ex) {
      // The current segment did not validate.
      // Currently this means that decryption cannot resume.
      setUndefinedState();
      throw new IOException(ex);
    }
    segmentNr += 1;
    plaintextSegment.flip();
    ciphertextSegment.clear();
    if (!endOfCiphertext) {
      ciphertextSegment.clear();
      ciphertextSegment.limit(ciphertextSegmentSize + 1);
      ciphertextSegment.put(lastByte);
    }
    return true;
  }

  @Override
  public synchronized int read(ByteBuffer dst) throws IOException {
    if (!definedState) {
      throw new IOException("This AesGcmHkdfDecryptingChannel is in an undefined state");
    }
    if (!headerRead) {
      if (!tryReadHeader()) {
        return 0;
      }
      int firstSegmentLength = ciphertextSegmentSize - ciphertextOffset;
      ciphertextSegment.clear();
      ciphertextSegment.limit(firstSegmentLength + 1);
      plaintextPosition = 0;
    }
    if (endOfPlaintext) {
      return -1;
    }
    int startPosition = dst.position();
    while (dst.remaining() > 0) {
      if (plaintextSegment.remaining() == 0) {
        if (endOfCiphertext) {
          endOfPlaintext = true;
          break;
        }
        if (!tryLoadSegment()) {
          break;
        }
      }
      if (plaintextSegment.remaining() <= dst.remaining()) {
        int sliceSize = plaintextSegment.remaining();
        dst.put(plaintextSegment);
        plaintextPosition += sliceSize;
      } else {
        int sliceSize = dst.remaining();
        ByteBuffer slice = plaintextSegment.duplicate();
        slice.limit(slice.position() + sliceSize);
        dst.put(slice);
        plaintextSegment.position(plaintextSegment.position() + sliceSize);
        plaintextPosition += sliceSize;
      }
    }
    return dst.position() - startPosition;
  }

  @Override
  public void close() throws IOException {
    ciphertextChannel.close();
  }

  @Override
  public boolean isOpen() {
    return ciphertextChannel.isOpen();
  }


  /* Returns the state of the channel. */
  @Override
  public String toString() {
    StringBuilder res =
      new StringBuilder();
    res.append("AesGcmHkdfDecryptingChannel")
       .append("\nplaintextPosition:").append(plaintextPosition)
       .append("\nsegmentNr:").append(segmentNr)
       .append("\nciphertextSegmentSize:").append(ciphertextSegmentSize)
       .append("\nheaderRead:").append(headerRead)
       .append("\nendOfCiphertext:").append(endOfCiphertext)
       .append("\nendOfPlaintext:").append(endOfPlaintext)
       .append("\ndefinedState:").append(definedState)
       .append("\nHeader")
       .append(" position:").append(header.position())
       .append(" limit:").append(header.position())
       .append("\nciphertextSgement")
       .append(" postion:").append(ciphertextSegment.position())
       .append(" limit:").append(ciphertextSegment.limit())
       .append("\nplaintextSegment")
       .append(" position:").append(plaintextSegment.position())
       .append(" limit:").append(plaintextSegment.limit());
    return res.toString();
  }
}