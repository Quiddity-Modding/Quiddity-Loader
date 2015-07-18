package me.querol.andrew.mcadmin;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Created by winsock on 7/11/15.
 */
public class StandaloneAdminHandler implements AdminChannelHandler {

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
