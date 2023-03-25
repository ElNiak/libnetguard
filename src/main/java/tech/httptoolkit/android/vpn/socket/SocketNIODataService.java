package tech.httptoolkit.android.vpn.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.httptoolkit.android.vpn.ClientPacketWriter;
import tech.httptoolkit.android.vpn.Session;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A service that single-threadedly processes the events around our session connections,
 * entirely via non-blocking NIO.
 * <br/>
 * It uses a Selector that fires on outgoing socket events (connected, readable, writable),
 * handles the resulting operations, and keeps those subscriptions up to date.
 */
public class SocketNIODataService implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(SocketNIODataService.class);

	private final ReentrantLock nioSelectionLock = new ReentrantLock();
	private final ReentrantLock nioHandlingLock = new ReentrantLock();
	private final Selector selector = Selector.open();

	private final SocketChannelReader reader;
	private final SocketChannelWriter writer;

	private volatile boolean shutdown = false;

	
	public SocketNIODataService(ClientPacketWriter clientPacketWriter) throws IOException {
		reader = new SocketChannelReader(clientPacketWriter);
		writer = new SocketChannelWriter(clientPacketWriter);
	}

	@Override
	public void run() {
		log.debug("SocketNIODataService starting in background...");
		runTask();
	}

	public void registerSession(Session session) throws ClosedChannelException {
		AbstractSelectableChannel channel = session.getChannel();

		boolean isConnected = channel instanceof DatagramChannel
				? ((DatagramChannel) channel).isConnected()
				: ((SocketChannel) channel).isConnected();

		log.debug("Registering new session: {}", session);

		Lock selectorLock = lockSelector(selector);
		try {
			SelectionKey selectionKey = channel.register(selector,
					isConnected
							? SelectionKey.OP_READ
							: SelectionKey.OP_CONNECT
			);
			session.setSelectionKey(selectionKey);
			selectionKey.attach(session);
			log.debug("Registered selector successfully");
		} finally {
			selectorLock.unlock();
		}
	}

	private Lock lockSelector(Selector selector) {
		boolean gotSelectionLock = nioSelectionLock.tryLock();
		if (gotSelectionLock) return nioSelectionLock;

		nioHandlingLock.lock(); // Ensure the NIO thread can't do anything on wakeup
		selector.wakeup();

		nioSelectionLock.lock(); // Actually get the lock we want
		nioHandlingLock.unlock(); // Release the handling lock, which we no longer care about

		return nioSelectionLock;
	}

	/**
	 * If the selector is currently select()ing, wake it up (e.g. to register changes to
	 * interestOps). If it's not (and so it probably will select() very soon anyway) do nothing.
	 * This is designed to be run after changing readyOps, to ensure the new ops get monitored
	 * immediately (and fire immediately, if already ready). Without this, that blocks.
	 */
	public void refreshSelect(Session session) {
		boolean gotLock = nioSelectionLock.tryLock();

		if (!gotLock) {
			session.getSelectionKey().selector().wakeup();
		} else {
			nioSelectionLock.unlock();
		}
	}

	/**
	 * Shut down the NIO thread
	 */
	public void shutdown(){
		this.shutdown = true;
		selector.wakeup();
	}

	private void runTask(){
		log.debug("NIO selector is running...");
		
		while(!shutdown){
			try {
				nioSelectionLock.lockInterruptibly();
				selector.select();
			} catch (IOException e) {
				log.error("Error in Selector.select()", e);
				try {
					TimeUnit.MILLISECONDS.sleep(100);
				} catch (InterruptedException ignored) {
				}
				continue;
			} catch (InterruptedException ex) {
				log.info("Select() interrupted");
			} finally {
				if (nioSelectionLock.isHeldByCurrentThread()) {
					nioSelectionLock.unlock();
				}
			}

			if (shutdown) {
				break;
			}

			// A lock here makes it possible to reliably grab the selection lock above
			nioHandlingLock.lock();
			try {
				Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

				while (iterator.hasNext()) {
					SelectionKey key = iterator.next();
					Session session = ((Session) key.attachment());
					synchronized (session) { // Sessions are locked during processing (no VPN data races)
						try {
							processSelectionKey(key);
						} catch (IOException e) {
							synchronized (key) {
								key.cancel();
							}
						}
					}

					iterator.remove();
					if (shutdown) {
						break;
					}
				}
			} finally {
				nioHandlingLock.unlock();
			}
		}
		log.debug("NIO selector shutdown");
	}

	private void processSelectionKey(SelectionKey key) throws IOException {
		if (!key.isValid()) {
			log.debug("Invalid SelectionKey");
			return;
		}

		SelectableChannel channel = key.channel();

		Session session = ((Session) key.attachment());
		if (session == null) {
			log.warn("Key fired with no session attached");
			return;
		}
		
		if (channel instanceof SocketChannel && !session.isConnected() && key.isConnectable()) {
			SocketChannel socketChannel = (SocketChannel) channel;

			if (socketChannel.isConnectionPending()) {
				boolean connected = socketChannel.finishConnect();
				session.setConnected(connected);
			} else {
				throw new IllegalStateException("TCP channels must either be connected or pending connection");
			}
		}

		if (isConnected(channel)) {
			processConnectedSelection(key, session);
		}
	}

	private boolean isConnected(SelectableChannel channel) {
		if (channel instanceof DatagramChannel) {
			return ((DatagramChannel) channel).isConnected();
		} else if (channel instanceof SocketChannel) {
			return ((SocketChannel) channel).isConnected();
		} else {
			throw new IllegalArgumentException("isConnected on unexpected channel type: " + channel);
		}
	}

	private void processConnectedSelection(SelectionKey key, Session session) {
		// Whilst connected, we always want READ and not CONNECT events
		session.unsubscribeKey(SelectionKey.OP_CONNECT);
		session.subscribeKey(SelectionKey.OP_READ);
		processSelectorRead(key, session);
		processPendingWrite(key, session);
	}

	private void processSelectorRead(SelectionKey selectionKey, Session session) {
		boolean canRead;
		synchronized (selectionKey) {
			// There's a race here that requires a lock, as isReadable requires isValid
			canRead = selectionKey.isValid() && selectionKey.isReadable();
		}

		if (canRead) reader.read(session);
	}

	private void processPendingWrite(SelectionKey selectionKey, Session session) {
		// Nothing to write? Skip this entirely, and make sure we're not subscribed
		if (!session.hasDataToSend() || !session.isDataForSendingReady()) {
			session.unsubscribeKey(SelectionKey.OP_WRITE);
			return;
		}

		boolean canWrite;
		synchronized (selectionKey) {
			// There's a race here that requires a lock, as isReadable requires isValid
			canWrite = selectionKey.isValid() && selectionKey.isWritable();
		}

		if (canWrite) {
			session.unsubscribeKey(SelectionKey.OP_WRITE);
			writer.write(session); // This will resubscribe to OP_WRITE if it can't complete
		}
	}
}
