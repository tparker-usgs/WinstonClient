package gov.usgs.volcanoes.wwsclient;

import java.io.Closeable;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.usgs.volcanoes.core.args.ArgumentException;
import gov.usgs.volcanoes.core.data.HelicorderData;
import gov.usgs.volcanoes.core.data.RSAMData;
import gov.usgs.volcanoes.core.data.Scnl;
import gov.usgs.volcanoes.core.data.Wave;
import gov.usgs.volcanoes.core.data.file.FileType;
import gov.usgs.volcanoes.core.data.file.SeismicDataFile;
import gov.usgs.volcanoes.core.time.J2kSec;
import gov.usgs.volcanoes.core.time.TimeSpan;
import gov.usgs.volcanoes.winston.Channel;
import gov.usgs.volcanoes.wwsclient.handler.AbstractCommandHandler;
import gov.usgs.volcanoes.wwsclient.handler.GetScnlHeliRawHandler;
import gov.usgs.volcanoes.wwsclient.handler.GetScnlRsamRawHandler;
import gov.usgs.volcanoes.wwsclient.handler.GetWaveHandler;
import gov.usgs.volcanoes.wwsclient.handler.GetChannelsHandler;
import gov.usgs.volcanoes.wwsclient.handler.StdoutHandler;
import gov.usgs.volcanoes.wwsclient.handler.VersionHandler;
import gov.usgs.volcanoes.wwsclient.handler.WWSClientHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;

/**
 * A class that extends the Earthworm Wave Server to include a get helicorder function for WWS.
 *
 * @author Dan Cervelli
 * @author Tom Parker
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "Protocol requires just a LF")
public class WWSClient implements Closeable {
  private static final Logger LOGGER = LoggerFactory.getLogger(WWSClient.class);
  private static final int DEFAULT_IDLE_TIMEOUT = 30000;

  private final String server;
  private final int port;
  private final int idleTimeout;
  private io.netty.channel.Channel channel;
  private EventLoopGroup workerGroup;

  /**
   * Constructor.
   * 
   * @param server remote winston address
   * @param port remote winston port
   */
  public WWSClient(final String server, final int port) {
    this(server, port, DEFAULT_IDLE_TIMEOUT);
  }

  /**
   * Constructor.
   * 
   * @param server remote winston server
   * @param port remote winston port
   * @param idleTimeout connection idle timeout
   */
  public WWSClient(String server, int port, int idleTimeout) {
    this.server = server;
    this.port = port;
    this.idleTimeout = idleTimeout;
  }


  private boolean connect() throws InterruptedException {
    if (channel != null && channel.isActive()) {
      LOGGER.debug("Reusing connection");
      return true;
    }

    LOGGER.debug("Connecting");
    workerGroup = new NioEventLoopGroup();
    Bootstrap b = new Bootstrap();
    b.group(workerGroup);
    b.channel(NioSocketChannel.class);
    b.option(ChannelOption.SO_KEEPALIVE, true);
    b.handler(new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast("idleStateHandler",
            new IdleStateHandler(idleTimeout, idleTimeout, idleTimeout, TimeUnit.MILLISECONDS));
        ch.pipeline().addLast(new StringEncoder()).addLast(new WWSClientHandler());
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.debug("Exception caught in ChannelInitalizer");
        cause.printStackTrace();
      }
    });

    b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, idleTimeout);
    ChannelFuture f = b.connect(server, port);
    f.awaitUninterruptibly();

    if (f.isCancelled()) {
      LOGGER.error("Connection attempt to {}:{} canceled in WWSClient.", server, port);
      return false;
    } else if (!f.isSuccess()) {
      Throwable cause = f.cause();
      if (cause instanceof ConnectTimeoutException) {
        LOGGER.error("Timeout connecting to {}:{} in WWSClient.", server, port);
      } else {
        LOGGER.error("Error connecting to {}:{} in WWSClient. ({})", server, port,
            cause.getClass().getName());
        // f.cause().printStackTrace();
      }
      return false;
    } else {
      channel = f.channel();
      return true;
    }
  }

  /**
   * Close connection to winston.
   */
  public void close() {
    if (channel != null && channel.isActive()) {
      LOGGER.debug("Closing channel.");
      try {
        channel.close().sync();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
  }

  /**
   * Send a request to Winston and block until the response has been processed.
   * 
   * @param req Request string
   * @param handler Object to handle server response
   */
  private void sendRequest(String req, AbstractCommandHandler handler) {

    try {
      if (connect()) {
        LOGGER.debug("Sending: " + req);
        AttributeKey<AbstractCommandHandler> handlerKey = WWSClientHandler.handlerKey;
        channel.attr(handlerKey).set(handler);
        channel.writeAndFlush(req).sync();
        LOGGER.debug("Sent: " + req);

        handler.responseWait();
        LOGGER.debug("Completed: " + req);
      }
    } catch (

    InterruptedException ex)

    {
      Thread.currentThread().interrupt();
    } finally

    {
      close();
    }

  }

  /**
   * Return protocol version used by remote winston.
   * 
   * @return protocol version
   */
  public int getProtocolVersion() {
    VersionHolder version = new VersionHolder();
    sendRequest("VERSION\n", new VersionHandler(version));

    return version.version;
  }

  /**
   * Request RSAM from winston.
   * 
   * @param scnl channel to request
   * @param timeSpan time span to request
   * @param period RSAM period
   * @param doCompress if true, compress data transmitted over the network
   * @return RSAM data
   */
  public RSAMData getRSAMData(final Scnl scnl, final TimeSpan timeSpan, final int period,
      final boolean doCompress) {
    RSAMData rsam = new RSAMData();
    double st = J2kSec.fromEpoch(timeSpan.startTime);
    double et = J2kSec.fromEpoch(timeSpan.endTime);
    final String req = String.format(Locale.US, "GETSCNLRSAMRAW: GS %s %f %f %d %s\n",
        scnl.toString(" "), st, et, period, (doCompress ? "1" : "0"));
    sendRequest(req, new GetScnlRsamRawHandler(rsam, doCompress));

    return rsam;

  }

  /**
   * Fetch a wave data from a Winston.
   * 
   * @param station station
   * @param comp component
   * @param network network
   * @param location location
   * @param start start time as J2kSec
   * @param end end time as J2kSec
   * @param doCompress if true, request data be compressed before being transmitted over the network
   * @return wave data
   */
  public Wave getWave(final String station, final String comp, final String network,
      final String location, final double start, final double end, final boolean doCompress) {

    Scnl scnl = new Scnl(station, comp, network, location);
    TimeSpan timeSpan = new TimeSpan(J2kSec.asEpoch(start), J2kSec.asEpoch(end));

    return getWave(scnl, timeSpan, doCompress);
  }

  /**
   * Retrieve a waveform.
   * 
   * @deprecated  As of release 1.3, replaced by {@link #getWave()}
   * @param station station
   * @param comp component
   * @param network network
   * @param start start time as J2kSec
   * @param end end time as J2kSec
   * @return requested waveform or null if data is not available
   */
  @Deprecated
  public Wave getRawData(String station, String comp, String network, double start, double end) {
    return getWave(station, comp, network, "--", start, end, true);
  }

  /**
   * Retrieve a waveform.
   * 
   * @deprecated  As of release 1.3, replaced by {@link #getWave()}
   * @param station station
   * @param comp component
   * @param network network
   * @param location location
   * @param start start time as J2kSec
   * @param end end time as J2kSec
   * @return requested waveform or null if data is not available
   */
  @Deprecated
  public Wave getRawData(String station, String comp, String network, String location, double start,
      double end) {
    return getWave(station, comp, network, location, start, end, true);
  }

  /**
   * Fetch a wave data from a Winston.
   * 
   * @param scnl channel to query
   * @param timeSpan time span to query
   * @param doCompress if true, compress data over the network
   * @return wave data, empty if no data is avilable
   */
  public Wave getWave(final Scnl scnl, final TimeSpan timeSpan, final boolean doCompress) {
    Wave wave = new Wave();
    double st = J2kSec.fromEpoch(timeSpan.startTime);
    double et = J2kSec.fromEpoch(timeSpan.endTime);
    final String req = String.format(Locale.US, "GETWAVERAW: GS %s %f %f %s\n", scnl.toString(" "),
        st, et, (doCompress ? "1" : "0"));
    sendRequest(req, new GetWaveHandler(wave, doCompress));
    return wave;
  }

  /**
   * Fetch helicorder data from Winston.
   * 
   * @param station station
   * @param comp component
   * @param network network
   * @param location location
   * @param start start time as J2kSec
   * @param end end time as J2kSec
   * @param doCompress if true, compress data over the network
   * @return heli data
   */
  public HelicorderData getHelicorder(final String station, final String comp, final String network,
      final String location, final double start, final double end, final boolean doCompress) {
    Scnl scnl = new Scnl(station, comp, network, location);
    TimeSpan timeSpan = new TimeSpan(J2kSec.asEpoch(start), J2kSec.asEpoch(end));

    return getHelicorder(scnl, timeSpan, doCompress);
  }

  /**
   * Fetch helicorder data from Winston.
   * 
   * @param scnl channel to query
   * @param timeSpan time span to query
   * @param doCompress if true, compress data before sending
   * @return one second max/min values
   */
  public HelicorderData getHelicorder(final Scnl scnl, final TimeSpan timeSpan,
      final boolean doCompress) {
    HelicorderData heliData = new HelicorderData();
    double st = J2kSec.fromEpoch(timeSpan.startTime);
    double et = J2kSec.fromEpoch(timeSpan.endTime);
    final String req = String.format(Locale.US, "GETSCNLHELIRAW: GS %s %f %f %s\n",
        scnl.toString(" "), st, et, (doCompress ? "1" : "0"));
    sendRequest(req, new GetScnlHeliRawHandler(heliData, doCompress));

    return heliData;
  }

  /**
   * Retrieve a wave and write to a SAC file.
   * 
   * @param server Winston address
   * @param port Winston port
   * @param timeSpan time span to request
   * @param scnl SCNL to request
   */
  public static void outputSac(final String server, final int port, final TimeSpan timeSpan,
      final Scnl scnl, String outFile) {
    System.out.println("Writing wave to SAC\n");
    final WWSClient wws = new WWSClient(server, port);
    Wave wave = wws.getWave(scnl, timeSpan, true);
    if (wave.buffer != null) {
      System.err.println("Date: " + J2kSec.toDateString(wave.getStartTime()));
      final SeismicDataFile file = SeismicDataFile.getFile(outFile, FileType.SAC);
      file.putWave(scnl.toString("$"), wave);
      try {
        file.write();
      } catch (final IOException e) {
        System.err.println("Couldn't write file: " + e.getLocalizedMessage());
        e.printStackTrace();
      }
    } else {
      System.out.println("No data received, not writing SAC file.");
    }
    wws.close();
  }

  public static void outputSac(final String server, final int port, final TimeSpan timeSpan,
      final Scnl scnl) {
    final DateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
    final String date =
        df.format(new Date(timeSpan.startTime)) + "-" + df.format(new Date(timeSpan.endTime));

    String outFile = scnl.toString("_") + "_" + date + ".sac";

    outputSac(server, port, timeSpan, scnl, outFile);
  }
  /**
   * Retrieve a wave and write to STDOUT.
   * 
   * @param server Winston address
   * @param port Winston port
   * @param timeSpan time span to request
   * @param scnl SCNL to request
   */
  private static void outputText(final String server, final int port, final TimeSpan timeSpan,
      final Scnl scnl) {
    System.out.println("dumping samples as text\n");
    final WWSClient wws = new WWSClient(server, port);
    Wave wave = wws.getWave(scnl, timeSpan, true);
    wws.close();
    for (final int i : wave.buffer) {
      System.out.println(i);
    }
  }

  /**
   * Retrieve Heli and write to STDOUT.
   * 
   * @param server Winston address
   * @param port Winston port
   * @param timeSpan time span to request
   * @param scnl SCNL to request
   */
  private static void outputHeli(final String server, final int port, final TimeSpan timeSpan,
      final Scnl scnl) {
    System.out.println("dumping Heli data as text\n");
    final WWSClient wws = new WWSClient(server, port);
    HelicorderData heliData = wws.getHelicorder(scnl, timeSpan, true);
    wws.close();
    System.out.println(heliData.toCSV());
  }


  /**
   * Retrieve RSAM and write to STDOUT.
   * 
   * @param server Winston address
   * @param port Winston port
   * @param timeSpan time span to request
   * @param scnl SCNL to request
   */
  private static void outputRsam(final String server, final int port, final TimeSpan timeSpan,
      final int period, final Scnl scnl) {
    System.out.println("dumping RSAM as text\n");
    final WWSClient wws = new WWSClient(server, port);
    RSAMData rsam = wws.getRSAMData(scnl, timeSpan, period, true);
    wws.close();
    System.out.println(rsam.toCSV());
  }

  /**
   * Retrieve a list of channels from a remote Winston.
   * 
   * @return List of channels
   */
  public List<Channel> getChannels() {
    return getChannels(false);
  }

  /**
   * Retrieve a list of channels from Winston.
   * 
   * @param meta if true, request metadata
   * @return List of channels
   */
  public List<Channel> getChannels(final boolean meta) {
    List<Channel> channels = new ArrayList<Channel>();
    String req = String.format("GETCHANNELS: GC%s\n", meta ? " METADATA" : "");

    sendRequest(req, new GetChannelsHandler(channels));
    return channels;
  }

  /**
   * Print server menu to STDOUT.
   * 
   * @param server Winston to query
   * @param port Winston port
   */
  private static void displayMenu(final String server, final int port) {
    WWSClient wws = new WWSClient(server, port);
    List<Channel> channels = wws.getChannels();
    System.out.println("Channel count: " + channels.size());
    for (Channel chan : channels) {
      System.out.println(chan.toMetadataString());
    }
    wws.close();
  }

  /**
   * Send a command string to winston and return result to <STDOUT>
   * 
   * @param server Winston to query
   * @param port Winston port
   * @param command Command String to send
   */
  private static void sendCommand(final String server, final int port, final String command) {
    WWSClient wws = new WWSClient(server, port);
    wws.sendRequest(command + "\n", new StdoutHandler(System.out));
    wws.close();
  }

  /**
   * Here's where it all begins
   * 
   * @param args command line args
   * @see gov.usgs.volcanoes.wwsclient.WwsClientArgs
   */
  public static void main(final String[] args) {
    try {
      final WwsClientArgs config = new WwsClientArgs(args);

      if (config.menu) {
        LOGGER.debug("Requesting menu from {}:{}.", config.server, config.port);
        displayMenu(config.server, config.port);
      }

      if (config.sacOutput) {
        LOGGER.debug("Requesting {} from {}:{} for {} and writing to SAC.", config.channel,
            config.server, config.port, config.timeSpan);
        outputSac(config.server, config.port, config.timeSpan, config.channel);
      }

      if (config.txtOutput) {
        LOGGER.debug("Requesting {} from {}:{} for {} and writing to TXT.", config.channel,
            config.server, config.port, config.timeSpan);
        outputText(config.server, config.port, config.timeSpan, config.channel);
      }

      if (config.rsamOutput) {
        LOGGER.debug("Requesting RSAM {} from {}:{} for {} and writing to TXT.", config.channel,
            config.server, config.port, config.timeSpan);
        outputRsam(config.server, config.port, config.timeSpan, config.rsamPeriod, config.channel);
      }

      if (config.heliOutput) {
        LOGGER.debug("Requesting helicorder data {} from {}:{} for {}.", config.channel,
            config.server, config.port, config.timeSpan);
        outputHeli(config.server, config.port, config.timeSpan, config.channel);
      }

      if (config.command != null) {
        LOGGER.debug("Sending: {}", config.command);
        sendCommand(config.server, config.port, config.command);
      }

    } catch (ArgumentException e) {
      System.out.println(e.getLocalizedMessage());
    }
    LOGGER.debug("Exiting.");
  }

}
