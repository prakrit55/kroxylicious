/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.KeyManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.incubator.channel.uring.IOUring;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;

import io.kroxylicious.proxy.bootstrap.FilterChainFactory;
import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.config.admin.AdminHttpConfiguration;
import io.kroxylicious.proxy.internal.KafkaProxyInitializer;
import io.kroxylicious.proxy.internal.MeterRegistries;
import io.kroxylicious.proxy.internal.admin.AdminHttpInitializer;
import io.kroxylicious.proxy.internal.filter.FixedNetFilter;

public final class KafkaProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaProxy.class);

    private final String proxyHost;
    private final int proxyPort;
    private final String brokerHost;
    private final int brokerPort;
    private final boolean logNetwork;
    private final boolean logFrames;
    private final boolean useIoUring;
    private final FilterChainFactory filterChainFactory;
    private final AdminHttpConfiguration adminHttpConfig;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel acceptorChannel;
    private Channel metricsChannel;
    private Optional<File> keyStoreFile;
    private Optional<String> keyStorePassword;

    public KafkaProxy(Configuration config) {
        String proxyAddress = config.proxy().address();
        String[] proxyAddressParts = proxyAddress.split(":");

        // TODO: deal with list
        String brokerAddress = config.clusters().entrySet().iterator().next().getValue().bootstrapServers();
        String[] brokerAddressParts = brokerAddress.split(":");

        this.proxyHost = proxyAddressParts[0];
        this.proxyPort = Integer.valueOf(proxyAddressParts[1]);
        this.brokerHost = brokerAddressParts[0];
        this.brokerPort = Integer.valueOf(brokerAddressParts[1]);
        this.logNetwork = config.proxy().logNetwork();
        this.logFrames = config.proxy().logFrames();
        this.useIoUring = config.proxy().useIoUring();
        this.adminHttpConfig = config.adminHttpConfig();

        this.filterChainFactory = new FilterChainFactory(config);

        this.keyStoreFile = config.proxy().keyStoreFile().map(File::new);
        this.keyStorePassword = config.proxy().keyPassword();
    }

    public String proxyHost() {
        return proxyHost;
    }

    public int proxyPort() {
        return proxyPort;
    }

    public String proxyAddress() {
        return proxyHost() + ":" + proxyPort();
    }

    public String brokerHost() {
        return brokerHost;
    }

    public int brokerPort() {
        return brokerPort;
    }

    public String brokerAddress() {
        return brokerHost() + ":" + brokerPort();
    }

    public boolean useIoUring() {
        return useIoUring;
    }

    /**
     * Starts this proxy.
     * @return This proxy.
     */
    public KafkaProxy startup() throws InterruptedException {
        if (acceptorChannel != null) {
            throw new IllegalStateException("This proxy is already running");
        }

        LOGGER.info("Proxying local {} to remote {}",
                proxyAddress(), brokerAddress());

        Optional<SslContext> sslContext = keyStoreFile.map(ksf -> {
            try (var is = new FileInputStream(ksf)) {
                var password = keyStorePassword.map(String::toCharArray).orElse(null);
                var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(is, password);
                var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, password);
                return SslContextBuilder.forServer(keyManagerFactory).build();
            }
            catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException e) {
                throw new RuntimeException(e);
            }
        });

        KafkaProxyInitializer initializer = new KafkaProxyInitializer(false,
                Map.of(),
                new FixedNetFilter(brokerHost,
                        brokerPort,
                        filterChainFactory),
                logNetwork,
                logFrames,
                sslContext);

        final int availableCores = Runtime.getRuntime().availableProcessors();

        // Configure the bootstrap.
        final Class<? extends ServerChannel> channelClass;
        if (useIoUring) {
            if (!IOUring.isAvailable()) {
                throw new IllegalStateException("io_uring not available due to: " + IOUring.unavailabilityCause());
            }
            bossGroup = new IOUringEventLoopGroup(1);
            workerGroup = new IOUringEventLoopGroup(availableCores);
            channelClass = IOUringServerSocketChannel.class;
        }
        else if (Epoll.isAvailable()) {
            bossGroup = new EpollEventLoopGroup(1);
            workerGroup = new EpollEventLoopGroup(availableCores);
            channelClass = EpollServerSocketChannel.class;
        }
        else if (KQueue.isAvailable()) {
            bossGroup = new KQueueEventLoopGroup(1);
            workerGroup = new KQueueEventLoopGroup(availableCores);
            channelClass = KQueueServerSocketChannel.class;
        }
        else {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup(availableCores);
            channelClass = NioServerSocketChannel.class;
        }

        MeterRegistries meterRegistries = new MeterRegistries();
        maybeStartMetricsListener(bossGroup, workerGroup, channelClass, meterRegistries);

        ServerBootstrap serverBootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                .channel(channelClass)
                .childHandler(initializer)
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.TCP_NODELAY, true);
        ChannelFuture bindFuture;
        if (proxyHost != null) {
            bindFuture = serverBootstrap.bind(proxyHost, proxyPort);
        }
        else {
            bindFuture = serverBootstrap.bind(proxyPort);
        }
        acceptorChannel = bindFuture.sync().channel();
        return this;
    }

    private void maybeStartMetricsListener(EventLoopGroup bossGroup,
                                           EventLoopGroup workerGroup,
                                           Class<? extends ServerChannel> channelClass,
                                           MeterRegistries meterRegistries)
            throws InterruptedException {
        if (adminHttpConfig != null
                && adminHttpConfig.getEndpoints().maybePrometheus().isPresent()) {
            ServerBootstrap metricsBootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                    .channel(channelClass)
                    .childHandler(new AdminHttpInitializer(meterRegistries, adminHttpConfig));
            metricsChannel = metricsBootstrap.bind(adminHttpConfig.getHost(), adminHttpConfig.getPort()).sync().channel();
        }
    }

    /**
     * Blocks while this proxy is running.
     * This should only be called after a successful call to {@link #startup()}.
     * @throws InterruptedException
     */
    public void block() throws InterruptedException {
        if (acceptorChannel == null) {
            throw new IllegalStateException("This proxy is not running");
        }
        acceptorChannel.closeFuture().sync();
    }

    /**
     * Shuts down a running proxy.
     * @throws InterruptedException
     */
    public void shutdown() throws InterruptedException {
        if (acceptorChannel == null) {
            throw new IllegalStateException("This proxy is not running");
        }
        bossGroup.shutdownGracefully().sync();
        workerGroup.shutdownGracefully().sync();
        bossGroup = null;
        workerGroup = null;
        acceptorChannel = null;
        metricsChannel = null;
    }

}
