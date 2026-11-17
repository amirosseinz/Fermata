package me.aap.fermata.mirror;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated single-direction video transport for paired devices on a local network. */
final class ScreenLink {
	static final String SERVICE = "_fermata-screen._tcp.";
	private static final int MAGIC = 0x46534331; // FSC1
	private static final int NONCE_LEN = 16;
	private static final int PROOF_LEN = 32;
	private static final int TAG_LEN = 16;
	private static final int MAX_PACKET = 2 * 1024 * 1024;
	private static final SecureRandom RANDOM = new SecureRandom();
	private final DataInputStream in;
	private final DataOutputStream out;
	private final SecretKeySpec key;
	private final Cipher cipher;
	private final int mode;
	private long sequence;

	private ScreenLink(DataInputStream in, DataOutputStream out, byte[] secret, int mode)
			throws GeneralSecurityException {
		this.in = in;
		this.out = out;
		key = new SecretKeySpec(secret, "AES");
		cipher = Cipher.getInstance("AES/GCM/NoPadding");
		this.mode = mode;
	}

	static String newCode() {
		var code = new StringBuilder(NONCE_LEN * 2);
		for (byte b : randomBytes()) {
			code.append(Character.forDigit((b >>> 4) & 15, 16));
			code.append(Character.forDigit(b & 15, 16));
		}
		return code.toString();
	}

	static byte[] parseCode(String code) {
		var normalized = code.replaceAll("[\\s-]", "");
		if (!normalized.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("Invalid pairing code");
		var secret = new byte[NONCE_LEN];
		for (int i = 0; i < secret.length; i++) {
			int hi = Character.digit(normalized.charAt(i * 2), 16);
			int lo = Character.digit(normalized.charAt(i * 2 + 1), 16);
			secret[i] = (byte) ((hi << 4) | lo);
		}
		return secret;
	}

	static ScreenLink send(Socket socket, String code) throws IOException {
		return connect(socket, parseCode(code), true);
	}

	static ScreenLink receive(Socket socket, String code) throws IOException {
		return connect(socket, parseCode(code), false);
	}

	private static ScreenLink connect(Socket socket, byte[] secret, boolean sender) throws IOException {
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(5_000);
		var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
		var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
		var client = new byte[NONCE_LEN];
		var server = new byte[NONCE_LEN];
		try {
			if (sender) {
				RANDOM.nextBytes(client);
				out.writeInt(MAGIC);
				out.write(client);
				out.flush();
				in.readFully(server);
				checkProof(in, derive(secret, "receiver", client, server));
				out.write(derive(secret, "sender", client, server));
				out.flush();
			} else {
				if (in.readInt() != MAGIC) throw new IOException("Unsupported screen sharing protocol");
				in.readFully(client);
				RANDOM.nextBytes(server);
				out.write(server);
				out.write(derive(secret, "receiver", client, server));
				out.flush();
				checkProof(in, derive(secret, "sender", client, server));
			}
			socket.setSoTimeout(30_000);
			return new ScreenLink(in, out, derive(secret, "video", client, server),
					sender ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE);
		} catch (GeneralSecurityException e) {
			throw new IOException("Screen pairing failed", e);
		}
	}

	void write(byte[] plain) throws IOException {
		if (mode != Cipher.ENCRYPT_MODE || plain.length > MAX_PACKET) throw new IOException("Invalid screen packet");
		var encrypted = crypt(plain);
		out.writeInt(encrypted.length);
		out.write(encrypted);
		out.flush();
	}

	byte[] read() throws IOException {
		if (mode != Cipher.DECRYPT_MODE) throw new IOException("Invalid screen link direction");
		int size = in.readInt();
		if (size < TAG_LEN || size > MAX_PACKET + TAG_LEN) throw new IOException("Invalid screen packet length");
		var encrypted = new byte[size];
		in.readFully(encrypted);
		return crypt(encrypted);
	}

	private byte[] crypt(byte[] packet) throws IOException {
		if (sequence == Long.MAX_VALUE) throw new IOException("Screen sharing session expired");
		try {
			var nonce = ByteBuffer.allocate(12).putInt(MAGIC).putLong(sequence++).array();
			cipher.init(mode, key, new GCMParameterSpec(TAG_LEN * 8, nonce));
			return cipher.doFinal(packet);
		} catch (GeneralSecurityException e) {
			throw new IOException("Screen packet authentication failed", e);
		}
	}

	private static byte[] derive(byte[] secret, String purpose, byte[] client, byte[] server)
			throws GeneralSecurityException {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret, "HmacSHA256"));
		mac.update((byte) 1);
		mac.update(purpose.getBytes(StandardCharsets.US_ASCII));
		mac.update((byte) 0);
		mac.update(client);
		return mac.doFinal(server);
	}

	private static void checkProof(DataInputStream in, byte[] expected) throws IOException {
		var actual = new byte[PROOF_LEN];
		in.readFully(actual);
		if (!MessageDigest.isEqual(expected, actual)) throw new IOException("Incorrect pairing code");
	}

	private static byte[] randomBytes() {
		var bytes = new byte[NONCE_LEN];
		RANDOM.nextBytes(bytes);
		return bytes;
	}
}
