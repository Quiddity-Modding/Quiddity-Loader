package mods.quiddity;

import io.netty.channel.*;
import org.apache.commons.lang3.RandomUtils;
import org.apache.logging.log4j.LogManager;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.time.Instant;
import java.util.*;

/**
 * Created by winsock on 7/12/15.
 */
public class RemoteAdminHandler {
	private static final long keepAlivePeriod = 15 /* Minutes */ * 60 /* Seconds */ * 1000 /* Milliseconds */; // Interval time to make sure the connection is alive
	private static final long authPeriod = 10 /* Minutes */ * 60 /* Seconds */ * 1000 /* Milliseconds */; // Interval time to make sure the connection is alive

	private volatile AdminHandler.ConnectionStatus connectionStatus = AdminHandler.ConnectionStatus.UNKNOWN;
	private volatile long lastSeenStamp;
	private Timer keepAliveTimer;
	private Channel connection;
	private String adminKey = null;
	private File allowedRemoteIdsFile = new File("allowed_ids.txt");
	private File idConnectionLogFile = new File("logged_ids.txt");
	private boolean logCreationFailed = false;
	private Map<String, Long> authedCache = new HashMap<>();
	private static final PrintStream realOut = System.out;
	private final AdminHandler.CustomOutputStream stdoutCapture = new AdminHandler.CustomOutputStream(string -> {
		if (string.trim().isEmpty())
			return;
		if (connectionStatus != AdminHandler.ConnectionStatus.DEAD && connection.isWritable()) {
			for (String s : string.split("\n")) {
				sendMessage("CONSOLE:" + s);
			}
		}
	});

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
		System.setOut(new PrintStream(stdoutCapture));

		lastSeenStamp = System.currentTimeMillis();
		keepAliveTimer = new Timer("KeepAliveTimer", true);
		connectionStatus = AdminHandler.ConnectionStatus.DEAD;

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

	private void init(Channel channel) {
		this.connection = channel;
		this.connectionStatus = AdminHandler.ConnectionStatus.ALIVE;

		channel.closeFuture().addListener(future -> {
			authedCache.clear();

			if (connectionStatus != AdminHandler.ConnectionStatus.DEAD) {
				stop();
			}
		});
	}

	public AdminHandler.ConnectionStatus getStatus() {
		return connectionStatus;
	}

	public ChannelHandler getChannelHandler() {
		return readingHandler;
	}

	public void sendMessage(String message) {
		connection.writeAndFlush(message + "\n");
	}

	public void stop() {
		connectionStatus = AdminHandler.ConnectionStatus.DEAD;
		keepAliveTimer.cancel();
		keepAliveTask.cancel();
		if (connection.isOpen()) {
			connection.close();
		}
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

	private boolean handleAuthCacheCheck(String clientId, String attemptedCommand) {
		if (authedCache.containsKey(clientId)) {
			if ((System.currentTimeMillis() - authedCache.get(clientId)) < authPeriod) {
				return true;
			} else {
				authedCache.remove(clientId);
			}
		}

		/*
		 * Most likely the auth period timed out, ask the server if the client is still authorized.
		 * If needed the server will ask for the client to send it's saved or cached admin key again.
		 * If valid the server will then resend the ALLOW command asking if the client should be allowed.
		 * At that point the client will be reauthed for the timeout period and the server will resend the command
		 */
		sendMessage("REAUTH:" + clientId + "\r\n" + attemptedCommand);
		return false;
	}

	private SimpleChannelInboundHandler<String> readingHandler = new SimpleChannelInboundHandler<String>() {

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			RemoteAdminHandler.this.init(ctx.channel());
			keepAliveTimer.scheduleAtFixedRate(keepAliveTask, keepAlivePeriod, keepAlivePeriod);
			connection.writeAndFlush("HELO:" + "127.0.0.1" + "\n");
			ctx.fireChannelActive();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			realOut.println("Disconnected from remote server!\nThe server may be down for maintenance");
			ctx.fireChannelInactive();
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
			String[] split = msg.split(":");
			if (split.length < 1)
				return;
			String command = msg.indexOf(':') > 0 ? split[0] : msg;
			switch (command.trim()) {
				case "PING": {
					sendMessage("ACK");
				} break;
				case "ADMINKEY?": {
					if (adminKey == null) {
						MessageDigest digest = MessageDigest.getInstance("SHA-256");
						digest.update(RandomUtils.nextBytes(20));
						adminKey = new BigInteger(1, digest.digest()).toString(16);
						digest.reset();

						realOut.println("ADMIN_KEY = " + adminKey);
					}
					sendMessage(adminKey);
				} break;
				case "ERR": {
					connectionStatus = AdminHandler.ConnectionStatus.DEAD;
					stop();
				} break;
				case "ALLOW": {
					if (split.length < 2)
						break;
					String remoteId = split[1].trim();
					boolean isAllowed = checkRemoteId(remoteId);
					logConnection(remoteId, isAllowed);
					if (!isAllowed) {
						sendMessage("DENY:" + remoteId);
					} else {
						authedCache.put(remoteId, System.currentTimeMillis());
						sendMessage("ALLOW:" + remoteId);
					}
				} break;
				case "CMD": {
					if (split.length < 2)
						break;
					String[] commandSplit = split[1].split("\r");
					if (commandSplit.length < 2)
						break;

					// Command requires auth, check if we have ensured authorization recently
					if (RemoteAdminHandler.this.handleAuthCacheCheck(commandSplit[0], msg.trim())) {
						System.out.println("[" + commandSplit[0] + "] is about to issue a command.\nCommand: " + commandSplit[1]);
						// TODO: Clean up this ugly call
						Loader.getInstance().getServerHandlers().keySet().iterator().next().issueCommand(commandSplit[1], commandSplit[0]);
					}
				} break;
			}
			// The remote relay has contacted us, reset the last seen timestamp
			lastSeenStamp = System.currentTimeMillis();
		}
	};
}
