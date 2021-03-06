/*
 * Copyright (C) 2012 Jacek Marchwicki <jacek.marchwicki@gmail.com>
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
 * limitations under the License
 */

package com.appunite.websocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.StatusLine;
import org.apache.http.message.BasicLineParser;
import org.apache.http.util.EncodingUtils;
import static com.google.common.base.Preconditions.*;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.appunite.socketio.NotConnectedException;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;

/**
 * Allows user to connect to websocket
 * 
 * @author Jacek Marchwicki <jacek.marchwicki@gmail.com>
 * 
 */
public class WebSocket {
	
	// WebSocket states
	private enum State {
		DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
	}

	// Default ports
	private static final int DEFAULT_WSS_PORT = 443;
	private static final int DEFAULT_WS_PORT = 80;
	
	// Magic string for header verification
	private static final String MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	// Payload packet consts
	private static final int RESERVED = 0x03 << 4;
	private static final int FIN = 0x01 << 7;
	private static final int OPCODE = 0x0f;
	private static final int PAYLOAD_MASK = 0x01 << 7;

	// Opcodes
	private static final int OPCODE_CONTINUED_FRAME = 0x00;
	private static final int OPCODE_TEXT_FRAME = 0x01;
	private static final int OPCODE_BINARY_FRAME = 0x02;
	private static final int OPCODE_CONNECTION_CLOSE_FRAME = 0x08;
	private static final int OPCODE_PING_FRAME = 0x09;
	private static final int OPCODE_PONG_FRAME = 0x0A;

	// Not need to be locked
	private final WebSocketListener mListener;

	// Should be locked via self
	private final Optional<SecureRandom> mSecureRandom;

	private Object mLockObj = new Object(); // 1

	// Locked via mLockObj
	private State mState = State.DISCONNECTED;

	// Locked via mLockObj
	// only accessable while CONNECTING/CONNECTED
	private Socket mSocket;

	// Locked via mLockObj
	// only accessable while CONNECTED
	private WebSocketReader mInputStream;

	// Locked via mLockObj
	// only accessable while CONNECTED
	private WebSocketWriter mOutputStream;

	private Object mWriteLock = new Object(); // 2

	// Locked via mWriteLock
	private int mWriting = 0;

	/**
	 * Create instance of WebSocket
	 * 
	 * @param listener
	 *            where all calls will be thrown
	 */
	public WebSocket(WebSocketListener listener) {
		checkArgument(listener != null, "Lister cannot be null");
		this.mListener = listener;

		SecureRandom secureRandom;
		try {
			secureRandom = SecureRandom.getInstance("SHA1PRNG");
		} catch (NoSuchAlgorithmException e) {
			// if we do not have secure random we have to leave data unmasked
			secureRandom = null;
		}
		if (secureRandom == null) {
			mSecureRandom = Optional.absent();
		} else {
			mSecureRandom = Optional.of(secureRandom);
		}
	}

	/**
	 * This method will be alive until error occur or interrupt was executed.
	 * This method always throws some exception. (not thread safe)
	 * 
	 * @param uri
	 *            uri of websocket
	 * @throws UnknownHostException
	 *             when could not connect to selected host
	 * @throws IOException
	 *             thrown when I/O exception occur
	 * @throws WrongWebsocketResponse
	 *             thrown when wrong web socket response received
	 * @throws InterruptedException
	 *             thrown when interrupt method was invoked
	 */
	public void connect(Uri uri) throws UnknownHostException, IOException,
			WrongWebsocketResponse, InterruptedException {
		checkArgument(uri != null, "Uri cannot be null");
		try {
			synchronized (mLockObj) {
				checkState(State.DISCONNECTED.equals(mState),
						"connect could be called only if disconnected");
				SocketFactory factory;
				if (isSsl(uri)) {
					factory = SSLSocketFactory.getDefault();
				} else {
					factory = SocketFactory.getDefault();
				}
				mSocket = factory.createSocket();
				mState = State.CONNECTING;
				mLockObj.notifyAll();
			}

			mSocket.connect(new InetSocketAddress(uri.getHost(), getPort(uri)));

			mInputStream = new WebSocketReader(mSocket.getInputStream());
			mOutputStream = new WebSocketWriter(mSocket.getOutputStream());

			String secret = generateHandshakeSecret();
			writeHeaders(uri, secret);
			readHanshakeHeaders(secret);
		} catch (IOException e) {
			synchronized (mLockObj) {
				if (State.DISCONNECTING.equals(mState)) {
					throw new InterruptedException();
				} else {
					throw e;
				}
			}
		} finally {
			synchronized (mLockObj) {
				mState = State.DISCONNECTED;
				mLockObj.notifyAll();
			}
		}

		try {
			synchronized (mLockObj) {
				mState = State.CONNECTED;
				mLockObj.notifyAll();
			}
			mListener.onConnected();

			for (;;) {
				doRead();
			}
		} catch (NotConnectedException e) {
			synchronized (mLockObj) {
				if (State.DISCONNECTING.equals(mState)) {
					throw new InterruptedException();
				} else {
					throw new RuntimeException();
				}
			}
		} catch (IOException e) {
			synchronized (mLockObj) {
				if (State.DISCONNECTING.equals(mState)) {
					throw new InterruptedException();
				} else {
					throw e;
				}
			}
		} finally {
			synchronized (mLockObj) {
				while (mWriting != 0) {
					mLockObj.wait();
				}
				mState = State.DISCONNECTED;
				mLockObj.notifyAll();
			}
		}
	}

	/**
	 * Send binary message (thread safe). Can be called after onConnect and
	 * before onDisconnect by any thread. Thread will be blocked until send
	 * 
	 * @param buffer
	 *            buffer to send
	 * @throws IOException
	 *             when exception occur while sending
	 * @throws InterruptedException
	 *             when user call disconnect
	 * @throws NotConnectedException
	 *             when called before onConnect or after onDisconnect
	 */
	public void sendByteMessage(byte[] buffer) throws IOException,
			InterruptedException, NotConnectedException {
		sendMessage(OPCODE_BINARY_FRAME, generateMask(), buffer);
	}

	/**
	 * Send text message (thread safe). Can be called after onConnect and before
	 * onDisconnect by any thread. Thread will be blocked until send
	 * 
	 * @param message
	 *            message to send
	 * @throws IOException
	 *             when exception occur while sending
	 * @throws InterruptedException
	 *             when user call disconnect
	 * @throws NotConnectedException
	 *             when called before onConnect or after onDisconnect
	 */
	public void sendStringMessage(String message) throws IOException,
			InterruptedException, NotConnectedException {
		byte[] buffer = message.getBytes("UTF-8");
		sendMessage(OPCODE_TEXT_FRAME, generateMask(), buffer);
	}

	/**
	 * Return if given uri is ssl encrypted (thread safe)
	 * 
	 * @param uri
	 * @return true if uri is wss
	 * @throws IllegalArgumentException
	 *             if unkonwo schema
	 */
	private static boolean isSsl(Uri uri) {
		String scheme = uri.getScheme();
		if ("wss".equals(scheme)) {
			return true;
		} else if ("ws".equals(scheme)) {
			return false;
		} else {
			throw new IllegalArgumentException("Unknown schema");
		}
	}

	/**
	 * get websocket port from uri (thread safe)
	 * 
	 * @param uri
	 * @return port number
	 * @throws IllegalArgumentException
	 *             if unknwon schema
	 */
	private static int getPort(Uri uri) {
		int port = uri.getPort();
		if (port != -1)
			return port;

		String scheme = uri.getScheme();
		if ("wss".equals(scheme)) {
			return DEFAULT_WSS_PORT;
		} else if ("ws".equals(scheme)) {
			return DEFAULT_WS_PORT;
		} else {
			throw new IllegalArgumentException("Unknown schema");
		}
	}

	/**
	 * Write websocket headers to outputStream (not thread safe)
	 * 
	 * @param uri
	 * @param secret
	 * @throws IOException
	 */
	private void writeHeaders(Uri uri, String secret) throws IOException {
		mOutputStream.writeLine("GET " + uri.getPath() + " HTTP/1.1");
		mOutputStream.writeLine("Upgrade: websocket");
		mOutputStream.writeLine("Connection: Upgrade");
		mOutputStream.writeLine("Host: " + uri.getHost());
		mOutputStream.writeLine("Origin: " + uri);
		mOutputStream.writeLine("Sec-WebSocket-Key: " + secret);
		mOutputStream.writeLine("Sec-WebSocket-Protocol: chat");
		mOutputStream.writeLine("Sec-WebSocket-Version: 13");
		mOutputStream.writeNewLine();
		mOutputStream.flush();
	}

	/**
	 * Read a single message from websocket (not thread safe)
	 * 
	 * @throws IOException
	 * @throws WrongWebsocketResponse
	 * @throws InterruptedException
	 * @throws NotConnectedException
	 */
	private void doRead() throws IOException, WrongWebsocketResponse,
			InterruptedException, NotConnectedException {
		int first = mInputStream.readByteOrThrow();
		int reserved = first & RESERVED;
		if (reserved != 0) {
			throw new WrongWebsocketResponse(
					"Server expected some negotiation that is not supported");
		}
		boolean fin = (first & FIN) != 0;
		int opcode = first & OPCODE;
		int second = mInputStream.readByteOrThrow();
		boolean payload_mask = (second & PAYLOAD_MASK) != 0;

		long payload_len = (second & (~PAYLOAD_MASK));
		if (payload_len == 127) {
			payload_len = mInputStream.read64Long();
		} else if (payload_len == 126) {
			payload_len = mInputStream.read16Int();
		} else {

		}
		Optional<byte[]> masking_key;
		if (payload_mask) {
			byte[] mask_key = new byte[4];
			mInputStream.readBytesOrThrow(mask_key);
			masking_key = Optional.of(mask_key);
		} else {
			masking_key = Optional.absent();
		}
		readPayload(fin, opcode, masking_key, payload_len);
	}

	/**
	 * Read payload from websocket (not thread safe)
	 * 
	 * Method currently does not support
	 * 
	 * @param fin
	 * @param opcode
	 * @param masking_key
	 * @param payload_len
	 * @throws WrongWebsocketResponse
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws NotConnectedException
	 */
	private void readPayload(boolean fin, int opcode,
			Optional<byte[]> masking_key, long payload_len)
			throws WrongWebsocketResponse, IOException, InterruptedException,
			NotConnectedException {
		if (payload_len > 1024 * 1024 || payload_len < 0) {
			throw new WrongWebsocketResponse("too large payload");
		}
		if (!fin) {
			// TODO
			throw new WrongWebsocketResponse(
					"We do not support not continued frames");
		}
		byte[] payload = new byte[(int) payload_len];
		mInputStream.readBytesOrThrow(payload);

		if (opcode == OPCODE_CONTINUED_FRAME) {
			// TODO
			throw new WrongWebsocketResponse(
					"We do not support not continued frames");
		} else if (opcode == OPCODE_TEXT_FRAME) {
			String message = new String(payload, "UTF-8");
			mListener.onStringMessage(message);
		} else if (opcode == OPCODE_BINARY_FRAME) {
			mListener.onBinaryMessage(payload);
		} else if (opcode == OPCODE_CONNECTION_CLOSE_FRAME) {
			mListener.onServerRequestedClose(payload);
		} else if (opcode == OPCODE_PONG_FRAME) {
			mListener.onPing(payload);
			sendMessage(OPCODE_PONG_FRAME, generateMask(), payload);
		} else if (opcode == OPCODE_PING_FRAME) {
			mListener.onPong(payload);
		} else {
			mListener.onUnknownMessage(payload);
		}
	}

	/**
	 * Generate 4 bit random mask to send message (thread safe)
	 * 
	 * @return 4 bit random mask
	 */
	private Optional<byte[]> generateMask() {
		synchronized (mSecureRandom) {
			if (!mSecureRandom.isPresent())
				return Optional.absent();

			byte[] bytes = new byte[4];
			mSecureRandom.get().nextBytes(bytes);
			return Optional.of(bytes);
		}
	}

	/**
	 * This method will apply mask to given buffer (thread safe)
	 * 
	 * @param buffer
	 *            buffer to apply mask
	 * @param mask
	 *            4 byte length mask to apply
	 */
	private void maskBuffer(byte[] buffer, byte[] mask) {
		checkArgument(mask != null);
		checkArgument(buffer != null);
		checkArgument(mask.length == 4);

		for (int i = 0; i < buffer.length; i++) {
			int j = i % 4;
			buffer[i] = (byte) (buffer[i] ^ mask[j]);
		}
	}

	/**
	 * Send a message to socket
	 * 
	 * (thread safe)
	 * 
	 * @param opcode
	 *            - type of message (0x00-0x0f) <a
	 *            href="http://tools.ietf.org/html/rfc6455#section-11.8">rfc6455
	 *            opcode</a>
	 * @param mask
	 *            - message mask key (4 byte length) or Optional.absent() if
	 *            message should not be masked
	 * @param buffer
	 *            buffer that will be sent to user. This buffer will be changed
	 *            if mask will be set
	 * @throws IOException
	 *             - when write error occur
	 * @throws InterruptedException
	 *             - when socket was interrupted
	 * @throws NotConnectedException
	 *             - when socket was not connected
	 */
	private void sendMessage(int opcode, Optional<byte[]> mask, byte[] buffer)
			throws IOException, InterruptedException, NotConnectedException {
		checkArgument(buffer != null, "buffer should not be null");
		checkArgument(mask != null, "mask should not be null");
		synchronized (mLockObj) {
			if (!State.CONNECTED.equals(mState)) {
				throw new NotConnectedException();
			}
			mWriting += 1;
		}
		try {
			synchronized (mWriteLock) {
				sendHeader(true, opcode, mask, buffer.length);
				if (mask.isPresent()) {
					maskBuffer(buffer, mask.get());
				}
				mOutputStream.writeBytes(buffer);
				mOutputStream.flush();

			}
		} catch (IOException e) {
			synchronized (mLockObj) {
				if (State.DISCONNECTING.equals(mState)) {
					throw new InterruptedException();
				} else {
					throw e;
				}
			}
		} finally {
			synchronized (mLockObj) {
				mWriting -= 1;
				mLockObj.notifyAll();
			}
		}
	}

	/**
	 * Send message header to output stream. Should be safed with mWriteLock and
	 * should append mWrite lock. Look at
	 * {@link #sendMessage(int, Optional, byte[])}.
	 * 
	 * (not thread safe)
	 * 
	 * @param fin
	 *            if message is last from sequence
	 * @param opcode
	 *            - type of message (0x00-0x0f)<a
	 *            href="http://tools.ietf.org/html/rfc6455#section-11.8">rfc6455
	 *            opcode</a>
	 * @param mask
	 *            - message mask key (4 byte length) or Optional.absent() if
	 *            message should not be masked
	 * @param length
	 *            - length of message that will be sent after header
	 * @throws IOException
	 *             - thrown if connection was broken
	 * 
	 * @see <a href="http://tools.ietf.org/html/rfc6455">rfc6455</a>
	 * @see <a href="http://tools.ietf.org/html/rfc6455#section-5.2">rfc6455
	 *      frame</a>
	 */
	private void sendHeader(boolean fin, int opcode, Optional<byte[]> mask,
			long length) throws IOException {
		checkArgument(opcode >= 0x00 && opcode <= 0x0f,
				"opcode value should be between 0x00 and 0x0f");
		checkArgument(mask != null, "mask should not be null");
		checkArgument(length >= 0, "length should not be negative");
		if (mask.isPresent()) {
			checkArgument(mask.get().length == 4,
					"Mask have to contain 4 bytes");
		}
		int first = opcode | (fin ? FIN : 0);
		mOutputStream.writeByte(first);

		int payloadMask = mask.isPresent() ? PAYLOAD_MASK : 0;

		if (length > 0xffff) {
			mOutputStream.writeByte(127 | payloadMask);
			mOutputStream.writeLong64(length);
		} else if (length >= 126) {
			mOutputStream.writeByte(126 | payloadMask);
			mOutputStream.writeInt16((int) length);
		} else {
			mOutputStream.writeByte((int) length | payloadMask);
		}

		if (mask.isPresent()) {
			mOutputStream.writeBytes(mask.get());
		}
	}

	/**
	 * Read headers from connection input stream, parse them and ensure that
	 * everything is correct. (not thread safe)
	 * 
	 * @param key
	 *            - string sent by client to server
	 * @throws IOException
	 *             - throw when connection was broken
	 * @throws WrongWebsocketResponse
	 *             - throw when wrong response was given from server (hanshake
	 *             error)
	 */
	private void readHanshakeHeaders(String key) throws IOException,
			WrongWebsocketResponse {
		// schould get response:
		// HTTP/1.1 101 Switching Protocols
		// Upgrade: websocket
		// Connection: Upgrade
		// Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
		StatusLine statusLine;
		List<Header> headers = Lists.newArrayList();
		try {
			String statusLineStr = mInputStream.readLine();
			if (TextUtils.isEmpty(statusLineStr)) {
				throw new WrongWebsocketResponse(
						"Wrong HTTP response status line");
			}
			statusLine = BasicLineParser.parseStatusLine(statusLineStr, null);
			for (;;) {
				String headerLineStr = mInputStream.readLine();
				if (TextUtils.isEmpty(headerLineStr))
					break;
				Header header = BasicLineParser
						.parseHeader(headerLineStr, null);
				headers.add(header);
			}
		} catch (ParseException e) {
			throw new WrongWebsocketResponse("Wrong HTTP response", e);
		}

		verifyHanshakeStatusLine(statusLine);
		verifyHanshakeHeaders(key, headers);
	}

	/**
	 * Verify is status line code is correct (thread safe)
	 * 
	 * @param statusLine
	 *            status line from server response
	 * @throws WrongWebsocketResponse
	 *             thrown when status line is incorrect
	 */
	private static void verifyHanshakeStatusLine(StatusLine statusLine)
			throws WrongWebsocketResponse {
		if (statusLine.getStatusCode() != HttpStatus.SC_SWITCHING_PROTOCOLS) {
			throw new WrongWebsocketResponse("Wrong http response status");
		}
	}

	/**
	 * Verify if headers are correct with WebSocket handshake protocol. If
	 * headers are not correct it will throw {@link WrongWebsocketResponse}
	 * (thread safe)
	 * 
	 * @param key
	 *            - key sent to user
	 * @param headers
	 *            - headers received from server
	 * @throws WrongWebsocketResponse
	 *             - will be throw if headers are not correct
	 */
	private static void verifyHanshakeHeaders(String key, List<Header> headers)
			throws WrongWebsocketResponse {
		String webSocketAccept = null;
		String webSocketProtocol = null;
		for (Header header : headers) {
			String headerName = header.getName();
			if ("Sec-WebSocket-Accept".equalsIgnoreCase(headerName)) {
				if (webSocketAccept != null) {
					throw new WrongWebsocketResponse(
							"Sec-WebSocket-Accept should appear once");
				}
				webSocketAccept = header.getValue();
			} else if ("Sec-WebSocket-Protocol".equalsIgnoreCase(headerName)) {
				webSocketProtocol = header.getValue();
			}
		}
		if (webSocketAccept == null) {
			throw new WrongWebsocketResponse(
					"Sec-WebSocket-Accept did not appear");
		}
		if (!verifyHanshakeAcceptValue(key, webSocketAccept)) {
			throw new WrongWebsocketResponse("Sec-WebSocket-Accept is wrong");
		}
		if (webSocketProtocol != null) {
			if ("chat".equals(webSocketProtocol)) {
				throw new WrongWebsocketResponse(
						"Only supported is chat protocol");
			}
		}
	}

	/**
	 * Verify if header Sec-WebSocket-Accept value is correct with sent key
	 * value (thread safe)
	 * 
	 * @param key
	 * @param acceptValue
	 * @return
	 */
	private static boolean verifyHanshakeAcceptValue(String key,
			String acceptValue) {
		String base = key + MAGIC_STRING;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] baseBytes = EncodingUtils.getAsciiBytes(base);
			md.update(baseBytes);
			byte[] sha1hash = md.digest();
			String expectedValue = Base64.encodeToString(sha1hash,
					Base64.NO_WRAP);
			return expectedValue.equals(acceptValue);
		} catch (NoSuchAlgorithmException e) {
			// if not found sha1 assume that response
			// value is OK
			return true;
		}
	}

	/**
	 * This method will create 16 bity random key encoded to base64 for header.
	 * If mSecureRanomd generator is not accessible it will generate empty array
	 * encoded with base64 (thread safe)
	 * 
	 * @return
	 */
	private String generateHandshakeSecret() {
		synchronized (mSecureRandom) {
			byte[] nonce = new byte[16];
			if (mSecureRandom.isPresent()) {
				mSecureRandom.get().nextBytes(nonce);
			} else {
				Arrays.fill(nonce, (byte) 0);
			}
			return Base64.encodeToString(nonce, Base64.NO_WRAP);
		}
	}

	/**
	 * Interrupt connect method. After interruption connect should return
	 * InterruptedException (thread safe)
	 */
	public void interrupt() {
		synchronized (mLockObj) {
			while (State.DISCONNECTED.equals(mState)) {
				try {
					mLockObj.wait();
				} catch (InterruptedException ignore) {
				}
			}
			if (State.CONNECTING.equals(mState)
					|| State.CONNECTED.equals(mState)) {
				try {
					mSocket.close();
				} catch (IOException ignore) {
				}
				mState = State.DISCONNECTING;
				mLockObj.notifyAll();
			}
			while (!State.DISCONNECTED.equals(mState)) {
				try {
					mLockObj.wait();
				} catch (InterruptedException ignore) {
				}
			}
		}
	}
}
