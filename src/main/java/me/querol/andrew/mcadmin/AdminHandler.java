package me.querol.andrew.mcadmin;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Created by winsock on 7/11/15.
 */

public abstract class AdminHandler implements Callable<Void> {
	public static final boolean STANDALONE = Boolean.parseBoolean(System.getProperty("standalone", "false"));
	public static final String SERVICE_PROVIDER = System.getProperty("service-provider", "localhost");
	public static final int PORT = Integer.parseInt(System.getProperty("port", "1337"));
	public static final boolean REQUIRE_UNKNOWN_CONFIRMATION = Boolean.parseBoolean(System.getProperty("require-confirmation", "true"));
	public static final boolean LOG_ROTATION = Boolean.parseBoolean(System.getProperty("log-rotation", "true"));


	private static final String PUBLIC_IP_SERVICE = System.getProperty("public-ip-service", "http://checkip.amazonaws.com");

	private final CustomOutputStream stdoutCapture;

	protected EventLoopGroup bossGroup = new NioEventLoopGroup(1);
	protected EventLoopGroup workerGroup = new NioEventLoopGroup();
	protected final ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1);
	protected final Callable<Void> startTask = () -> {
		getStart().apply(null);
		return null;
	};

	public AdminHandler() {
		stdoutCapture = new CustomOutputStream(getOutputConsumer());
		System.setOut(new PrintStream(stdoutCapture));
		System.setErr(new PrintStream(stdoutCapture));
	}

	protected abstract Consumer<String> getOutputConsumer();
	protected abstract Function<Void, Void> getStart();
	protected abstract AbstractBootstrap getBootstrap();
	protected abstract AdminChannelHandler getHandler();

	@Override
	public Void call() throws Exception {
		executorService.schedule(startTask, 0, TimeUnit.MICROSECONDS);
		return null;
	}

	@Override
	public void finalize() throws Throwable {
		super.finalize();

		bossGroup.shutdownGracefully();
		workerGroup.shutdownGracefully();
	}

	/**
	 * Submits HTTP GET request to the specified address in public-ip-service system property
	 * @return The public ip address. This assumes that all the server returns for the body of the response is the IP
	 */
	public static String getPublicIpAddress() {
		try {
			URL publicIp = new URL(PUBLIC_IP_SERVICE);
			BufferedReader in = new BufferedReader(new InputStreamReader(
				publicIp.openStream()));
			return in.readLine();
		} catch (Exception e) {
			return null;
		}
	}

	protected final static class Initalizer extends ChannelInitializer<SocketChannel> {
		private final SslContext sslContext;
		private final AdminChannelHandler logicHandler;
		private final boolean isServer;

		protected Initalizer(SslContext context, AdminChannelHandler logicHandler, boolean server) {
			this.sslContext = context;
			this.logicHandler = logicHandler;
			this.isServer = server;
		}

		@Override
		protected void initChannel(SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();

			if (isServer) {
				pipeline.addLast(sslContext.newHandler(ch.alloc()));
			} else {
				pipeline.addLast(sslContext.newHandler(ch.alloc(), AdminHandler.SERVICE_PROVIDER, AdminHandler.PORT));
			}

			pipeline.addLast(new DelimiterBasedFrameDecoder(8196, Delimiters.lineDelimiter()));
			pipeline.addLast(new StringDecoder(Charset.forName("UTF8")));
			pipeline.addLast(new StringEncoder(Charset.forName("UTF8")));
			pipeline.addLast(logicHandler.getChannelHandler());
		}
	}

	public enum ConnectionStatus {
		ALIVE,
		UNKNOWN,
		DEAD
	}

	public static class CustomOutputStream extends OutputStream {
		private final OutputStream systemOut = System.out;
		private final StringBuffer stringBuffer = new StringBuffer();
		private final Consumer<String> stringConsumer;

		public CustomOutputStream(Consumer<String> onLinePrinted) {
			stringConsumer = onLinePrinted;
		}

		@Override
		public final void write(int b) throws IOException {
			systemOut.write(b);

			if (b != '\n') {
				stringBuffer.append((char) b);
			} else {
				stringConsumer.accept(stringBuffer.toString());
				stringBuffer.setLength(0);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			systemOut.write(b, off, len);

			if ((off < 0) || (off > b.length) || (len < 0) ||
				((off + len) > b.length) || ((off + len) < 0)) {
				throw new IndexOutOfBoundsException();
			} else if (len == 0) {
				return;
			}

			byte[] pb = new byte[len];
			System.arraycopy(b, off, pb, 0, len);
			stringConsumer.accept(new String(pb));
		}
	}
}
