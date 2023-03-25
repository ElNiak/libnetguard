/*
 *  Copyright 2014 AT&T
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

package tech.httptoolkit.android.vpn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutput;
import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * write packet data back to VPN client stream. This class is thread safe.
 * @author Borey Sao
 * Date: May 22, 2014
 */
public class ClientPacketWriter implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(ClientPacketWriter.class);

	private final DataOutput clientWriter;

	private volatile boolean shutdown = false;
	private final BlockingDeque<byte[]> packetQueue = new LinkedBlockingDeque<>();

	public ClientPacketWriter(DataOutput clientWriter) {
		this.clientWriter = clientWriter;
	}

	public void write(byte[] data) {
		if (data.length > 30000) throw new Error("Packet too large");
		packetQueue.addLast(data);
	}

	public void shutdown() {
		this.shutdown = true;
	}

	@Override
	public void run() {
		while (!this.shutdown) {
			try {
				byte[] data = this.packetQueue.take();
				try {
					this.clientWriter.writeShort(data.length);
					this.clientWriter.write(data);
				} catch (IOException e) {
					log.error("Error writing {} bytes to the VPN", data.length);
					e.printStackTrace();

					this.packetQueue.addFirst(data); // Put the data back, so it's resent
					TimeUnit.MILLISECONDS.sleep(10); // Add an arbitrary tiny pause, in case that helps
				}
			} catch (InterruptedException ignored) { }
		}
	}
}
