package me.querol.andrew.mcadmin;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Created by winsock on 7/11/15.
 */
public class StandaloneAdminHandler extends AdminHandler implements AdminChannelHandler {

	private final ServerBootstrap bootstrap;
	private static final String STANDALONE_SSL_CERT = System.getProperty("standalone-ssl-cert", "ssl.cert");
	private static final String STANDALONE_SSL_KEY = System.getProperty("standalone-ssl-key", "ssl.key");

	public StandaloneAdminHandler() {
		super();
		SslContext sslCtx = null;

		try {
			File sslCertFile = new File(STANDALONE_SSL_CERT);
			File sslKeyFile = new File(STANDALONE_SSL_KEY);
			if (!(sslCertFile.exists() && sslKeyFile.exists())) {
				SelfSignedCertificate ssc = new SelfSignedCertificate(); // TODO: Switch to real SSL cert
				sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
			} else {
				sslCtx = SslContext.newServerContext(sslCertFile, sslKeyFile);
			}
		} catch (Exception e) { e.printStackTrace(); }

		bootstrap = new ServerBootstrap()
			.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
			.handler(new LoggingHandler(LogLevel.INFO))
			.childHandler(new Initalizer(sslCtx, this, true));
	}

	protected Consumer<String> getOutputConsumer() {
		return string -> { };
	}

	@Override
	protected Function<Void, Void> getStart() {
		return Void -> {
			if (!ServerBootstrap.class.isAssignableFrom(bootstrap.getClass())) {
				return null;
			}
			try {
				ChannelFuture bindFuture = bootstrap.bind(PORT);
				bindFuture.awaitUninterruptibly();
				if (!bindFuture.isSuccess()) {
					System.err.println("Unable to bind to port: " + PORT);
					System.err.println("Will retry in 30 seconds!");
					executorService.schedule(startTask, 30, TimeUnit.SECONDS);
				} else {
					bindFuture.sync().channel().closeFuture().addListener(future -> {
						bossGroup.shutdownGracefully();
						workerGroup.shutdownGracefully();
					});
				}
			} catch (Exception e) {
				bossGroup.shutdownGracefully();
				workerGroup.shutdownGracefully();
			}
			return null;
		};
	}

	@Override
	protected AbstractBootstrap getBootstrap() {
		return bootstrap;
	}

	@Override
	protected AdminChannelHandler getHandler() {
		return this;
	}

	@Override
	public ChannelHandler getChannelHandler() {
		return new StandaloneAdminChannelHandler();
	}

	private class StandaloneAdminChannelHandler extends SimpleChannelInboundHandler<String> {
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {

		}
	}
}
