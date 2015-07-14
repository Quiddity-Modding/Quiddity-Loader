package mods.quiddity;

import io.netty.channel.*;
import org.apache.commons.lang3.RandomUtils;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

/**
 * Created by winsock on 7/12/15.
 */
public class RemoteAdminHandler {
	private static final long keepAlivePeriod = 30 /* Minutes */ * 60 /* Seconds */ * 1000 /* Milliseconds */; // Interval time to make sure the connection is alive
	private volatile AdminHandler.ConnectionStatus connectionStatus = AdminHandler.ConnectionStatus.UNKNOWN;
	private volatile long lastSeenStamp;
	private Timer keepAliveTimer;
	private Channel connection;
	private final BlockingDeque<Consumer<Channel>> writeQueue = new LinkedBlockingDeque<>();
	private String adminKey = null;
	private File allowedRemoteIdsFile = new File("allowed_ids.txt");
	private File idConnectionLogFile = new File("logged_ids.txt");
	private boolean logCreationFailed = false;

	private final TimerTask keepAliveTask = new TimerTask() {
		@Override
		public void run() {
			if (connectionStatus != AdminHandler.ConnectionStatus.ALIVE) {
				connectionStatus = AdminHandler.ConnectionStatus.DEAD;
				this.cancel(); // Stop Timer
				return;
			}

			if ((System.currentTimeMillis() - lastSeenStamp) >= keepAlivePeriod) {
				connectionStatus = AdminHandler.ConnectionStatus.UNKNOWN; // We haven't heard from the server in 30 minutes
				connection.writeAndFlush("PING\n");
			}
		}
	};

	public RemoteAdminHandler() {
		lastSeenStamp = System.currentTimeMillis();
		keepAliveTimer = new Timer("KeepAliveTimer", true);

		if (!allowedRemoteIdsFile.exists()) {
			try {
				allowedRemoteIdsFile.createNewFile();
			} catch (IOException e) {
				System.err.println("Error while making new known_ids.txt file after not detecting one present.\n"
					+ "No one will be able to connect if you have required-confirmation set to true");
					e.printStackTrace();
			}
		}
	}

	public void init(Channel channel) {
		this.connection = channel;
		this.connectionStatus = AdminHandler.ConnectionStatus.ALIVE;
		keepAliveTimer.scheduleAtFixedRate(keepAliveTask, keepAlivePeriod, keepAlivePeriod);
		connection.writeAndFlush("HELO:" + "127.0.0.1" + "\n");

		channel.closeFuture().addListener(future -> {
			if (connectionStatus != AdminHandler.ConnectionStatus.DEAD) {
				connectionStatus = AdminHandler.ConnectionStatus.DEAD;
				keepAliveTask.cancel();
			}
		});
	}

	public ChannelHandler getChannelHandler() {
		return readingHandler;
	}

	public void sendMessage(String message) {
		connection.writeAndFlush(message + "\n");
	}

	public boolean checkRemoteId(String remoteId) {
		if (!AdminHandler.REQUIRE_UNKNOWN_CONFIRMATION) {
			return true;
		}
		try {
			Scanner allowedIdsScanner = new Scanner(allowedRemoteIdsFile);
			while (allowedIdsScanner.hasNext()) {
				String line = allowedIdsScanner.nextLine();
				if (line.equalsIgnoreCase(remoteId)) {
					return true;
				}
			}
		} catch (FileNotFoundException e) {
			return false;
		}
		return false;
	}

	private void logConnection(String remoteId, boolean allowed) {
		// First log to the console
		System.out.println("[" + DateFormat.getDateTimeInstance().format(Date.from(Instant.now())) + "] Remote connection " + (allowed ? "allowed" : "denied") + " for id: " + remoteId);

		// Don't try to log if we already failed once
		if (logCreationFailed) {
			return;
		}

		try {
			if (!idConnectionLogFile.exists()) {
				idConnectionLogFile.createNewFile();
			} else if ((idConnectionLogFile.length() / 1048576) >= 10) { // 1048576 is the number of bytes in a MB
				if (AdminHandler.LOG_ROTATION) {
					idConnectionLogFile.renameTo(new File("logged_ids.txt.old"));
					idConnectionLogFile.createNewFile();
				}
			}
		} catch (IOException e) {
			System.err.println("Error while making new logged_ids.txt file after not detecting one present or rotating logs.\n"
				+ "No connection attemps will be logged to file, only console.");
			e.printStackTrace();
			logCreationFailed = true;
			return;
		}

		FileWriter logWriter = null;
		try {
			logWriter = new FileWriter(idConnectionLogFile);
			logWriter.write("[" + DateFormat.getDateTimeInstance().format(Date.from(Instant.now())) + "] Remote connection " + (allowed ? "allowed" : "denied") + " for id: " + remoteId);
			logWriter.close();
		} catch (IOException e) {
			System.err.println("Error while trying to log a connection attempt, logging will be disabled until server restart\n"
				+ "No connection attemps will be logged to file, only console.");
			e.printStackTrace();
		}
	}

	private SimpleChannelInboundHandler<String> readingHandler = new SimpleChannelInboundHandler<String>() {
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
			String[] split = msg.split(":");
			if (split.length < 1)
				return;
			String command = msg.indexOf(':') > 0 ? split[0] : msg;
			switch (command.trim()) {
				case "PING":
					lastSeenStamp = System.currentTimeMillis();
					sendMessage("ACK");
					break;
				case "ADMINKEY?":
					if (adminKey == null) {
						MessageDigest digest = MessageDigest.getInstance("SHA-256");
						digest.update(RandomUtils.nextBytes(20));
						adminKey = new BigInteger(1, digest.digest()).toString(16);
						digest.reset();

						System.out.println("ADMIN_KEY = " + adminKey);
					}
					sendMessage(adminKey);
					break;
				case "ACK":
					lastSeenStamp = System.currentTimeMillis();
					break;
				case "ERR":
					connectionStatus = AdminHandler.ConnectionStatus.DEAD;
					ctx.close();
					break;
				case "ALLOW": {
					if (split.length < 2)
						break;
					String remoteId = split[1].trim();
					boolean isAllowed = checkRemoteId(remoteId);
					logConnection(remoteId, isAllowed);
					if (!isAllowed) {
						sendMessage("DENY:" + remoteId);
					} else {
						sendMessage("ALLOW:" + remoteId);
					}
				} break;
			}
		}
	};
}
