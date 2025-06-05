package org.csit;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;

public class NettyServer {

    private final int port;

    public NettyServer(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(65536))
                                    .addLast(new SearchServerHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(port).sync();
            System.out.println("http://localhost:" + port);
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        new NettyServer(port).run();
    }

    private static class SearchServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private static final List<String> FIXED_RESULTS = Arrays.asList(
                "Netty 高性能网络框架",
                "Java 编程语言",
                "Spring Boot 开发框架",
                "微服务架构设计",
                "分布式系统原理",
                "数据库优化技巧",
                "算法与数据结构",
                "云计算技术实践",
                "人工智能基础",
                "Web 安全防护"
        );

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            if (request.method() == HttpMethod.GET) {
                String uri = request.uri();
                if (uri.equals("/")) {
                    sendSearchPage(ctx);
                } else if (uri.startsWith("/search")) {
                    handleSearchRequest(ctx, request);
                } else {
                    sendError(ctx, HttpResponseStatus.NOT_FOUND);
                }
            } else {
                sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            }
        }

        private void sendSearchPage(ChannelHandlerContext ctx) {
            String html = "<!DOCTYPE html>" +
                    "<html>" +
                    "<head>" +
                    "    <title>Lite-Search</title>" +
                    "    <style>" +
                    "        body { font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; }" +
                    "        .search-box { width: 100%; padding: 10px; font-size: 16px; margin-bottom: 20px; }" +
                    "        .result-item { padding: 10px; border-bottom: 1px solid #eee; }" +
                    "        .no-results { color: #666; text-align: center; padding: 20px; }" +
                    "    </style>" +
                    "</head>" +
                    "<body>" +
                    "    <h1>简洁搜索</h1>" +
                    "    <form action='/search' method='get'>" +
                    "        <input type='text' name='q' class='search-box' placeholder='输入搜索关键词...' autofocus>" +
                    "    </form>" +
                    "    <div id='results'></div>" +
                    "</body>" +
                    "</html>";

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(html, CharsetUtil.UTF_8));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            ctx.writeAndFlush(response);
        }

        private void handleSearchRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
            String query = queryStringDecoder.parameters().get("q").get(0);
            query = URLDecoder.decode(query, "UTF-8");

            // 构建搜索结果HTML
            StringBuilder resultsHtml = new StringBuilder();
            for (String result : FIXED_RESULTS) {
                if (result.toLowerCase().contains(query.toLowerCase())) {
                    resultsHtml.append("<div class='result-item'>").append(result).append("</div>");
                }
            }

            String html = "<!DOCTYPE html>" +
                    "<html>" +
                    "<head>" +
                    "    <title>搜索: " + query + "</title>" +
                    "    <style>" +
                    "        body { font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; }" +
                    "        .search-box { width: 100%; padding: 10px; font-size: 16px; margin-bottom: 20px; }" +
                    "        .result-item { padding: 10px; border-bottom: 1px solid #eee; }" +
                    "        .no-results { color: #666; text-align: center; padding: 20px; }" +
                    "    </style>" +
                    "</head>" +
                    "<body>" +
                    "    <h1>搜索: " + query + "</h1>" +
                    "    <form action='/search' method='get'>" +
                    "        <input type='text' name='q' class='search-box' value='" + query + "' placeholder='输入搜索关键词...'>" +
                    "    </form>" +
                    "    <div id='results'>" +
                    (resultsHtml.length() > 0 ? resultsHtml.toString() : "<div class='no-results'>没有找到相关结果</div>") +
                    "    </div>" +
                    "</body>" +
                    "</html>";

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(html, CharsetUtil.UTF_8));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            ctx.writeAndFlush(response);
        }

        private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    status,
                    Unpooled.copiedBuffer("Error: " + status + "\r\n", CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}