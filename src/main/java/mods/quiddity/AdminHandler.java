package mods.quiddity;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

/**
 * Created by winsock on 7/11/15.
 */

public final class AdminHandler implements Callable<Void> {
	public static final String SERVICE_PROVIDER = System.getProperty("service-provider", "localhost");
	public static final int PORT = Integer.parseInt(System.getProperty("port", "1337"));
	public static final boolean STANDALONE = Boolean.parseBoolean(System.getProperty("standalone", "false"));
	public static final boolean REQUIRE_UNKNOWN_CONFIRMATION = Boolean.parseBoolean(System.getProperty("require-confirmation", "true"));
	public static final boolean LOG_ROTATION = Boolean.parseBoolean(System.getProperty("log-rotation", "true"));

	private static final String STANDALONE_SSL_CERT = System.getProperty("standalone-ssl-cert", "ssl.cert");
	private static final String STANDALONE_SSL_KEY = System.getProperty("standalone-ssl-key", "ssl.key");
	private static final String PUBLIC_IP_SERVICE = System.getProperty("public-ip-service", "http://checkip.amazonaws.com");

	private final StandaloneAdminHandler handler;
	private RemoteAdminHandler remoteHandler;

	private EventLoopGroup bossGroup = new NioEventLoopGroup(1);
	private EventLoopGroup workerGroup = new NioEventLoopGroup();

	public AdminHandler() {
		handler = new StandaloneAdminHandler();
	}

	@Override
	public Void call() throws Exception {
		SslContext sslCtx;
		AbstractBootstrap<?, ?> bootstrap;


		if (STANDALONE) {
			File sslCertFile = new File(STANDALONE_SSL_CERT);
			File sslKeyFile = new File(STANDALONE_SSL_KEY);

			if (!(sslCertFile.exists() && sslKeyFile.exists())) {
				SelfSignedCertificate ssc = new SelfSignedCertificate(); // TODO: Switch to real SSL cert
				sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
			} else {
				sslCtx = SslContext.newServerContext(sslCertFile, sslKeyFile);
			}

			try {
				bootstrap = new ServerBootstrap()
					.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
					.handler(new LoggingHandler(LogLevel.INFO))
					.childHandler(new Initalizer(sslCtx, handler, true));
				bootstrap.bind(PORT).sync().channel().closeFuture().addListener(future -> {
					// TODO: Implement standalone server cleanup
				});
			} catch (Exception e) {
				bossGroup.shutdownGracefully();
				workerGroup.shutdownGracefully();
			}
		} else {
			sslCtx = SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE);
			try {
				remoteHandler = new RemoteAdminHandler();
				bootstrap = new Bootstrap().group(workerGroup)
					.channel(NioSocketChannel.class)
					.handler(new Initalizer(sslCtx, remoteHandler.getChannelHandler(), false));
				((Bootstrap) bootstrap).connect(SERVICE_PROVIDER, PORT).addListener(future -> {
					if(future.isSuccess()) {
						remoteHandler.init(((ChannelFuture)future).channel());
					}
				});
			} catch (Exception e) {
				workerGroup.shutdownGracefully();
			}
		}
		return null;
	}

	@Override
	public void finalize() {
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

	private final static class Initalizer extends ChannelInitializer<SocketChannel> {
		private final SslContext sslContext;
		private final ChannelHandler logicHandler;
		private final boolean isServer;

		public Initalizer(SslContext context, ChannelHandler logicHandler, boolean server) {
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
			pipeline.addLast(logicHandler);
		}
	}

	public enum ConnectionStatus {
		ALIVE,
		UNKNOWN,
		DEAD
	}
}
