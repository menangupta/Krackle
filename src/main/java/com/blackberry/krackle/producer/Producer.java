/**
 * Copyright 2014 BlackBerry, Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.blackberry.krackle.producer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blackberry.krackle.Constants;
import com.blackberry.krackle.KafkaError;
import com.blackberry.krackle.MetricRegistrySingleton;
import com.blackberry.krackle.compression.Compressor;
import com.blackberry.krackle.compression.GzipCompressor;
import com.blackberry.krackle.compression.SnappyCompressor;
import com.blackberry.krackle.meta.Broker;
import com.blackberry.krackle.meta.MetaData;
import com.blackberry.krackle.meta.Topic;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;

/**
 * An implementation of the Kafka 0.8 producer.
 * 
 * This class acts as a producer of data to a cluster of Kafka brokers. Each
 * instance only sends data for a single topic, using a single key. If you need
 * to write to more topics (or using different keys), then instantiate more
 * instances.
 * 
 * This producer is asynchonous only. There is no synchronous mode.
 * 
 * This class was designed to be very light weight. The standard Java client
 * creates a lot of objects, and therefore causes a lot of garbage collection
 * that leads to a major slowdown in performance. This client creates ver few
 * new objects during steady state running, and so avoids most garbage
 * collection overhead.
 */
public class Producer {
  private static final Logger LOG = LoggerFactory.getLogger(Producer.class);
  private static final Charset UTF8 = Charset.forName("UTF-8");

  private ProducerConfiguration conf;

  private String clientIdString;
  private byte[] clientIdBytes;
  private short clientIdLength;

  private String keyString;
  private boolean rotatePartitions;
  private int partitionModifier;
  private boolean quickRotate;
  private long quickRotateMessageBlocks;
  private byte[] keyBytes;
  private int keyLength;

  private String topicString;
  private byte[] topicBytes;
  private short topicLength;

  // Various configs
  private short requiredAcks;
  private int brokerTimeout;
  private int retries;
  private int compressionLevel;
  private int retryBackoffMs;
  private long topicMetadataRefreshIntervalMs;
  private long queueBufferingMaxMs;

  // Store the uncompressed message set that we will be compressing later.
  private int numBuffers;
  private BlockingQueue<MessageSetBuffer> freeBuffers = null;
  private static final Object sharedBufferLock = new Object();
  private static BlockingQueue<MessageSetBuffer> sharedBuffers = null;

  private BlockingQueue<MessageSetBuffer> buffersToSend;

  // Store the compressed message + headers.
  // Generally, this needs to be bigger than the messageSetBufferSize
  // for uncompressed messages (size + key length + topic length + 34?)
  // For compressed messages, this shouldn't need to be bigger.
  private int sendBufferSize;
  private byte[] toSendBytes;
  private ByteBuffer toSendBuffer;

  // Buffer for reading responses from the server.
  private byte[] responseBytes;
  private ByteBuffer responseBuffer;

  // How to compress!
  Compressor compressor;

  // Stuff for internal use
  private int correlationId = 0;
  private int lastCorrelationId;
  private int messageSetSizePos;
  private int messageSizePos;
  private int messageCompressedSize;
  private int responseSize;
  private int responseCorrelationId;
  private short responseErrorCode;
  private int retry;

  // We could be using crc in 2 separate blocks, which could cause corruption
  // with one instance.
  private CRC32 crcSendMessage = new CRC32();
  private CRC32 crcSend = new CRC32();

  private MetaData metadata;
  private long lastMetadataRefresh;
  private int partition;
  private Broker broker;
  private String brokerAddress = null;
  private Socket socket;
  private OutputStream out;
  private InputStream in;

  private Sender sender;
  private Thread senderThread;

  private boolean closed = false;

  private ScheduledExecutorService scheduledExecutor = Executors
      .newSingleThreadScheduledExecutor();

  private MetricRegistry metrics;
  private Meter mReceived = null;
  private Meter mReceivedTotal = null;
  private Meter mSent = null;
  private Meter mSentTotal = null;
  private Meter mDroppedQueueFull = null;
  private Meter mDroppedQueueFullTotal = null;
  private Meter mDroppedSendFail = null;
  private Meter mDroppedSendFailTotal = null;

  private String freeBufferGaugeName;

  /**
   * Create a new producer.
   * 
   * @param conf
   *          ProducerConfiguration to use
   * @param clientId
   *          client id to send to the brokers
   * @param topic
   *          topic to produce on
   * @param key
   *          key to use for partitioning
   * @throws Exception
   */
  public Producer(ProducerConfiguration conf, String clientId, String topic,
      String key) throws Exception {
    this(conf, clientId, topic, key, false, false, 0, null);
  }

  /**
   * Create a new producer.
   * 
   * @param conf
   *          ProducerConfiguration to use
   * @param clientId
   *          client id to send to the brokers
   * @param topic
   *          topic to produce on
   * @param key
   *          key to use for partitioning
   * @param rotatePartitions
   *          Whether we rotate partitions or not
   * @throws Exception
   */
  public Producer(ProducerConfiguration conf, String clientId, String topic,
      String key, boolean rotatePartitions) throws Exception {
    this(conf, clientId, topic, key, rotatePartitions, false, 0, null);
  }
  
  /**
  * Create a new producer.
  * 
  * @param conf
  *          ProducerConfiguration to use
  * @param clientId
  *          client id to send to the brokers
  * @param topic
  *          topic to produce on
  * @param key
  *          key to use for partitioning
  * @param metrics
  *          MetricRegistry instance to use for metrics.
  * @throws Exception
  */
 public Producer(ProducerConfiguration conf, String clientId, String topic,
     String key, MetricRegistry metrics) throws Exception {
   this(conf, clientId, topic, key, false, false, 0, metrics);
 }
 
 /**
  * Create a new producer using a given instance of MetricRegistry instead of
  * the default singleton.
  * 
  * @param conf
  *          ProducerConfiguration to use
  * @param clientId
  *          client id to send to the brokers
  * @param topic
  *          topic to produce on
  * @param key
  *          key to use for partitioning
  * @param rotatePartitions
  *          Whether we rotate partitions or not
  * @param quickRotate
  * 		 Whether we quickly rotate partitions or not
  * @param quickRotateMessageBlocks
  * 		 The number of message blocks before rotating
  * @throws Exception
  */
 public Producer(ProducerConfiguration conf, String clientId, String topic,
     String key, boolean rotatePartitions, boolean quickRotate, long quickRotateMessageBlocks) throws Exception {
	 this(conf, clientId, topic, key, rotatePartitions, quickRotate, quickRotateMessageBlocks, null);
 }
 
  /**
   * Create a new producer using a given instance of MetricRegistry instead of
   * the default singleton.
   * 
   * @param conf
   *          ProducerConfiguration to use
   * @param clientId
   *          client id to send to the brokers
   * @param topic
   *          topic to produce on
   * @param key
   *          key to use for partitioning
   * @param rotatePartitions
   *          Whether we rotate partitions or not
   * @param metrics
   *          MetricRegistry instance to use for metrics.
   * @throws Exception
   */
  public Producer(ProducerConfiguration conf, String clientId, String topic,
      String key, boolean rotatePartitions, boolean quickRotate, long quickRotateMessageBlocks, 
      MetricRegistry metrics) throws Exception {
    LOG.info("Creating new producer for topic {}, key {}", topic, key);

    this.conf = conf;

    this.topicString = topic;
    this.topicBytes = topic.getBytes(UTF8);
    this.topicLength = (short) topicBytes.length;

    this.clientIdString = clientId;
    this.clientIdBytes = clientId.getBytes(UTF8);
    this.clientIdLength = (short) clientId.length();

    this.keyString = key;
    this.keyBytes = key.getBytes(UTF8);
    this.keyLength = keyBytes.length;
    
    this.rotatePartitions = rotatePartitions;
    this.quickRotate = quickRotate;
    this.quickRotateMessageBlocks = quickRotateMessageBlocks;
    this.partitionModifier = 0;
    this.lastCorrelationId = correlationId;

    if (metrics == null) {
      this.metrics = MetricRegistrySingleton.getInstance().getMetricsRegistry();
      MetricRegistrySingleton.getInstance().enableJmx();
      MetricRegistrySingleton.getInstance().enableConsole();
    } else {
      this.metrics = metrics;
    }
    initializeMetrics();

    configure();

    // Start a periodic sender to ensure that things get sent from time to
    // time.
    scheduledExecutor.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        try {
          send(null, 0, 0);
        } catch (Exception e) {
          // That's fine.
        }
      }
    }, queueBufferingMaxMs, queueBufferingMaxMs, TimeUnit.MILLISECONDS);

    // Try to do this. If it fails, then we can try again when it's time to
    // send.
    try {
      // In case this fails, we don't want the value to be null;
      lastMetadataRefresh = System.currentTimeMillis();
      updateMetaDataAndConnection(true);
    } catch (Throwable t) {
      LOG.warn("Initial load of metadata failed.", t);
      metadata = null;
    }

    // Start the send thread
    sender = new Sender();
    senderThread = new Thread(sender);
    senderThread.setDaemon(false);
    senderThread.setName("Sender-Thread");
    senderThread.start();

    // Ensure that if the sender thread ever dies, it is restarted.
    scheduledExecutor.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        if (senderThread == null || senderThread.isAlive() == false) {
          LOG.error("[{}] Sender thread is dead! Restarting it.", topicString);
          sender = new Sender();
          senderThread = new Thread(sender);
          senderThread.setDaemon(false);
          senderThread.setName("Sender-Thread");
          senderThread.start();
        }
      }
    }, 1, 1, TimeUnit.MINUTES);
  }

  private void initializeMetrics() {
    String name = topicString;

    mReceived = this.metrics.meter("krackle:producer:topics:" + name
        + ":messages received");
    mReceivedTotal = this.metrics
        .meter("krackle:producer:total:messages received");

    mSent = this.metrics.meter("krackle:producer:topics:" + name
        + ":messages sent");
    mSentTotal = this.metrics.meter("krackle:producer:total:messages sent");

    mDroppedQueueFull = this.metrics.meter("krackle:producer:topics:" + name
        + ":messages dropped (queue full)");
    mDroppedQueueFullTotal = this.metrics
        .meter("krackle:producer:total:messages dropped (queue full)");

    mDroppedSendFail = this.metrics.meter("krackle:producer:topics:" + name
        + ":messages dropped (send failure)");
    mDroppedSendFailTotal = this.metrics
        .meter("krackle:producer:total:messages dropped (send failure)");
  }

  private void configure() throws Exception {
    requiredAcks = conf.getRequestRequiredAcks();
    retryBackoffMs = conf.getRetryBackoffMs();
    brokerTimeout = conf.getRequestTimeoutMs();
    retries = conf.getMessageSendMaxRetries();
    sendBufferSize = conf.getSendBufferSize();
    topicMetadataRefreshIntervalMs = conf.getTopicMetadataRefreshIntervalMs();
    queueBufferingMaxMs = conf.getQueueBufferingMaxMs();

    String compressionCodec = conf.getCompressionCodec();
    compressionLevel = conf.getCompressionLevel();

    int messageBufferSize = conf.getMessageBufferSize();
    numBuffers = conf.getNumBuffers();

    // Check to see if we're using a shared buffer, or dedicated buffers.
    if (conf.isUseSharedBuffers()) {
      synchronized (sharedBufferLock) {
        if (sharedBuffers == null) {
          sharedBuffers = new ArrayBlockingQueue<MessageSetBuffer>(numBuffers);
          for (int i = 0; i < numBuffers; i++) {
            sharedBuffers.add(new MessageSetBuffer(this, messageBufferSize));
          }

          MetricRegistrySingleton
              .getInstance()
              .getMetricsRegistry()
              .register("krackle:producer:shared free buffers",
                  new Gauge<Integer>() {
                    @Override
                    public Integer getValue() {
                      return sharedBuffers.size();
                    }
                  });
        }
      }
      freeBuffers = sharedBuffers;
    } else {
      freeBuffers = new ArrayBlockingQueue<MessageSetBuffer>(numBuffers);
      for (int i = 0; i < numBuffers; i++) {
        freeBuffers.add(new MessageSetBuffer(this, messageBufferSize));
      }

      freeBufferGaugeName = "krackle:producer:topics:" + topicString
          + ":free buffers";
      if (MetricRegistrySingleton.getInstance().getMetricsRegistry()
          .getGauges(new MetricFilter() {
            @Override
            public boolean matches(String s, Metric m) {
              return s.equals(freeBufferGaugeName);
            }
          }).size() > 0) {
        LOG.warn("Gauge already exists for '{}'", freeBufferGaugeName);
      } else {
        MetricRegistrySingleton.getInstance().getMetricsRegistry()
            .register(freeBufferGaugeName, new Gauge<Integer>() {
              @Override
              public Integer getValue() {
                return freeBuffers.size();
              }
            });
      }
    }

    buffersToSend = new ArrayBlockingQueue<MessageSetBuffer>(numBuffers);

    toSendBytes = new byte[sendBufferSize];
    toSendBuffer = ByteBuffer.wrap(toSendBytes);

    // We need this to be big enough to read the length of the first response,
    // then we can expand it to the appropriate size.
    responseBytes = new byte[4];
    responseBuffer = ByteBuffer.wrap(responseBytes);

    if (compressionCodec.equals("none")) {
      compressor = null;
    } else if (compressionCodec.equals("snappy")) {
      compressor = new SnappyCompressor();
    } else if (compressionCodec.equals("gzip")) {
      compressor = new GzipCompressor(compressionLevel);
    } else {
      throw new Exception("Unknown compression type: " + compressionCodec);
    }

  }

  private void updateMetaDataAndConnection(boolean force) {
    LOG.info("Updating metadata");
    metadata = MetaData.getMetaData(conf.getMetadataBrokerList(), topicString,
        clientIdString);
    LOG.debug("Metadata: {}", metadata);

    Topic topic = metadata.getTopic(topicString);

    // If we have rotateParitions set, add one to the modifier
    if(rotatePartitions && !force) {
    	partitionModifier = (partitionModifier + 1) % topic.getNumPartitions();
    	lastCorrelationId = correlationId;
    }
    
    partition = (Math.abs(keyString.hashCode()) + partitionModifier) % topic.getNumPartitions();
    LOG.info("Sending to partition {} of {}", partition,
        topic.getNumPartitions());

    broker = metadata.getBroker(topic.getPartition(partition).getLeader());

    // Only reset our connection if the broker has changed, or it's forced
    String newBrokerAddress = broker.getHost() + ":" + broker.getPort();
    if (force || brokerAddress == null
        || brokerAddress.equals(newBrokerAddress) == false) {
      brokerAddress = newBrokerAddress;
      LOG.info("Changing brokers to {}", broker);

      if (socket != null) {
        try {
          socket.close();
        } catch (IOException e) {
          LOG.error("Error closing connection to broker.", e);
        }
      }

      try {
        socket = new Socket(broker.getHost(), broker.getPort());
        socket.setSendBufferSize(conf.getSendBufferSize());
        socket.setSoTimeout(conf.getRequestTimeoutMs() + 1000);
        LOG.info("Connected to {}", socket);
        in = socket.getInputStream();
        out = socket.getOutputStream();
      } catch (UnknownHostException e) {
        LOG.error("Error connecting to broker.", e);
      } catch (IOException e) {
        LOG.error("Error connecting to broker.", e);
      }
    }

    lastMetadataRefresh = System.currentTimeMillis();
  }

  private int crcPos;

  private MessageSetBuffer activeMessageSetBuffer = null;
  private ByteBuffer activeByteBuffer;

  /**
   * Add a message to the send queue
   * 
   * Takes bytes from buffer from start, up to length bytes.
   * 
   * @param buffer
   *          byte array to read from.
   * @param offset
   *          location in source to start from.
   * @param length
   *          length of input data to read.
   * @throws Exception
   */
  public synchronized void send(byte[] buffer, int offset, int length)
      throws Exception {
    if (closed) {
      LOG.warn("Trying to send data on a closed producer.");
      return;
    }

    if (activeMessageSetBuffer == null) {
      if (conf.getQueueEnqueueTimeoutMs() == -1) {
        activeMessageSetBuffer = freeBuffers.take();
      } else {
        activeMessageSetBuffer = freeBuffers.poll(
            conf.getQueueEnqueueTimeoutMs(), TimeUnit.MILLISECONDS);
      }

      if (activeMessageSetBuffer == null) {
        mDroppedQueueFull.mark();
        mDroppedQueueFullTotal.mark();
        return;
      }
    }

    // null buffer means send what we have
    if (buffer == null) {
      if (activeMessageSetBuffer.getBatchSize() > 0) {
        buffersToSend.put(activeMessageSetBuffer);
        activeMessageSetBuffer = null;
      }
      return;
    }

    mReceived.mark();
    mReceivedTotal.mark();

    if (activeMessageSetBuffer.getBuffer().remaining() < length + keyLength
        + 26) {
      buffersToSend.put(activeMessageSetBuffer);
      activeMessageSetBuffer = null;

      if (conf.getQueueEnqueueTimeoutMs() == -1) {
        activeMessageSetBuffer = freeBuffers.take();
      } else {
        activeMessageSetBuffer = freeBuffers.poll(
            conf.getQueueEnqueueTimeoutMs(), TimeUnit.MILLISECONDS);
      }

      if (activeMessageSetBuffer == null) {
        mDroppedQueueFull.mark();
        mDroppedQueueFullTotal.mark();
        return;
      }
    }

    activeByteBuffer = activeMessageSetBuffer.getBuffer();

    // Offset
    activeByteBuffer.putLong(0L);
    // Size of uncompressed message
    activeByteBuffer.putInt(length + keyLength + 14);

    crcPos = activeByteBuffer.position();
    activeByteBuffer.position(crcPos + 4);

    // magic number
    activeByteBuffer.put(Constants.MAGIC_BYTE);

    // no compression
    activeByteBuffer.put(Constants.NO_COMPRESSION);

    activeByteBuffer.putInt(keyLength); // Key length
    activeByteBuffer.put(keyBytes); // Key
    activeByteBuffer.putInt(length); // Value length
    activeByteBuffer.put(buffer, offset, length); // Value

    crcSend.reset();
    crcSend.update(activeMessageSetBuffer.getBytes(), crcPos + 4, length
        + keyLength + 10);

    activeByteBuffer.putInt(crcPos, (int) crcSend.getValue());

    activeMessageSetBuffer.incrementBatchSize();
  }

  int nextLoadingBuffer;
  boolean incrementLoadingBufferResult;

  // Send accumulated messages
  protected void sendMessage(MessageSetBuffer messageSetBuffer) {
    try {
      // New message, new id
      correlationId++;

      /* headers */
      // Skip 4 bytes for the size
      toSendBuffer.position(4);
      // API key for produce
      toSendBuffer.putShort(Constants.APIKEY_PRODUCE);
      // Version
      toSendBuffer.putShort(Constants.API_VERSION);
      // Correlation Id
      toSendBuffer.putInt(correlationId);
      // Client Id
      toSendBuffer.putShort(clientIdLength);
      toSendBuffer.put(clientIdBytes);
      // Required Acks
      toSendBuffer.putShort(requiredAcks);
      // Timeout in ms
      toSendBuffer.putInt(brokerTimeout);
      // Number of topics
      toSendBuffer.putInt(1);
      // Topic name
      toSendBuffer.putShort(topicLength);
      toSendBuffer.put(topicBytes);
      // Number of partitions
      toSendBuffer.putInt(1);
      // Partition
      toSendBuffer.putInt(partition);

      if (compressor == null) {
        // If we 're not compressing, then we can just dump the rest of the
        // message here.

        // Mesage set size
        toSendBuffer.putInt(messageSetBuffer.getBuffer().position());

        // Message set
        toSendBuffer.put(messageSetBuffer.getBytes(), 0, messageSetBuffer
            .getBuffer().position());

      } else {
        // If we are compressing, then do that.

        // Message set size ? We'll have to do this later.
        messageSetSizePos = toSendBuffer.position();
        toSendBuffer.position(toSendBuffer.position() + 4);

        // offset can be anything for produce requests. We'll use 0
        toSendBuffer.putLong(0L);

        // Skip 4 bytes for size, and 4 for crc
        messageSizePos = toSendBuffer.position();
        toSendBuffer.position(toSendBuffer.position() + 8);

        toSendBuffer.put(Constants.MAGIC_BYTE); // magic number
        toSendBuffer.put(compressor.getAttribute()); // Compression goes
        // here.

        // Add the key
        toSendBuffer.putInt(keyLength);
        toSendBuffer.put(keyBytes);

        // Compress the value here, into the toSendBuffer
        try {
          messageCompressedSize = compressor.compress(messageSetBuffer
              .getBytes(), 0, messageSetBuffer.getBuffer().position(),
              toSendBytes, toSendBuffer.position() + 4);

          if (messageCompressedSize == -1) {
            // toSendBytes is too small to hold the compressed data
            throw new IOException(
                "Not enough room in the send buffer for the compressed data.");
          }
        } catch (IOException e) {
          LOG.error("Exception while compressing data.  (data lost).", e);
          return;
        }

        // Write the size
        toSendBuffer.putInt(messageCompressedSize);

        // Update the send buffer position
        toSendBuffer.position(toSendBuffer.position() + messageCompressedSize);

        /** Go back and fill in the missing pieces **/
        // Message Set Size
        toSendBuffer.putInt(messageSetSizePos, toSendBuffer.position()
            - (messageSetSizePos + 4));

        // Message Size
        toSendBuffer.putInt(messageSizePos, toSendBuffer.position()
            - (messageSizePos + 4));

        // Message CRC
        crcSendMessage.reset();
        crcSendMessage.update(toSendBytes, messageSizePos + 8,
            toSendBuffer.position() - (messageSizePos + 8));
        toSendBuffer
            .putInt(messageSizePos + 4, (int) crcSendMessage.getValue());
      }

      // Fill in the complete message size
      toSendBuffer.putInt(0, toSendBuffer.position() - 4);

      // Send it!
      retry = 0;
      while (retry <= retries) {
        try {
          if (metadata == null || socket == null) {
            updateMetaDataAndConnection(true);
          }

          // Send request
          out.write(toSendBytes, 0, toSendBuffer.position());

          if (requiredAcks != 0) {
            // Check response
            responseBuffer.clear();
            in.read(responseBytes, 0, 4);
            responseSize = responseBuffer.getInt();
            if (responseBuffer.capacity() < responseSize) {
              responseBytes = new byte[responseSize];
              responseBuffer = ByteBuffer.wrap(responseBytes);
            }
            responseBuffer.clear();
            in.read(responseBytes, 0, responseSize);

            // Response bytes are
            // - 4 byte correlation id
            // - 4 bytes for number of topics. This is always 1 in this case.
            // - 2 bytes for length of topic name.
            // - topicLength bytes for topic
            // - 4 bytes for number of partitions. Always 1.
            // - 4 bytes for partition number (which we already know)
            // - 2 bytes for error code (That's interesting to us)
            // - 8 byte offset of the first message (we don't care).
            // The only things we care about here are the correlation id (must
            // match) and the error code (so we can throw an exception if it's
            // not 0)
            responseCorrelationId = responseBuffer.getInt();
            if (responseCorrelationId != correlationId) {
              throw new Exception("Correlation ID mismatch.  Expected "
                  + correlationId + ", got " + responseCorrelationId);
            }
            responseErrorCode = responseBuffer.getShort(18 + topicLength);
            if (responseErrorCode != KafkaError.NoError.getCode()) {
              throw new Exception("Got error from broker. Error Code "
                  + responseErrorCode + " ("
                  + getErrorString(responseErrorCode) + ")");
            }

            // Clear the responses, if there is anything else to read
            while (in.available() > 0) {
              in.read(responseBytes, 0, responseBytes.length);
            }
          }

          break;
        } catch (Throwable t) {
          metadata = null;

          retry++;
          if (retry <= retries) {
            LOG.warn("Request failed. Retrying {} more times for {}.", retries - retry
                + 1, topicString, t);
            try {
              Thread.sleep(retryBackoffMs);
            } catch (InterruptedException e) {
              // Do nothing
            }
          } else {
            LOG.error("Request failed. No more retries (data lost) for {}.", topicString, t);
            mDroppedSendFail.mark(messageSetBuffer.getBatchSize());
            mDroppedSendFailTotal.mark(messageSetBuffer.getBatchSize());
          }

        }
      }

      toSendBuffer.clear();
      mSent.mark(messageSetBuffer.getBatchSize());
      mSentTotal.mark(messageSetBuffer.getBatchSize());

      // Periodic metadata refreshes.
      if ((topicMetadataRefreshIntervalMs >= 0
          && System.currentTimeMillis() - lastMetadataRefresh >= topicMetadataRefreshIntervalMs) 
          || (quickRotate 
        		  && (correlationId - lastCorrelationId > quickRotateMessageBlocks)
        		  && (System.currentTimeMillis() - lastMetadataRefresh > 30000))) {
        try {
          updateMetaDataAndConnection(false);
        } catch (Throwable t) {
          LOG.error("Error refreshing metadata.", t);
        }
      }
    } catch (Throwable t) {
      LOG.error("Unexpected exception: {}", t);
      mDroppedSendFail.mark(messageSetBuffer.getBatchSize());
      mDroppedSendFailTotal.mark(messageSetBuffer.getBatchSize());
    }
  }

  private String getErrorString(short errorCode) {
    for (KafkaError e : KafkaError.values()) {
      if (e.getCode() == errorCode) {
        return e.getMessage();
      }
    }
    return null;
  }

  public synchronized void close() {
    LOG.info("Closing producer.");
    closed = true;

    buffersToSend.add(activeMessageSetBuffer);
    activeMessageSetBuffer = null;

    try {
      senderThread.join();
    } catch (InterruptedException e) {
      LOG.error("Error shutting down sender and loader threads.", e);
    }

    if (conf.isUseSharedBuffers() == false) {
      MetricRegistrySingleton.getInstance().getMetricsRegistry()
          .remove(freeBufferGaugeName);
    }
  }

  private class Sender implements Runnable {
    private MessageSetBuffer buffer;

    @Override
    public void run() {
      while (true) {
        try {
          if (closed && buffersToSend.isEmpty()) {
            break;
          }

          try {
            buffer = buffersToSend.poll(1, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            LOG.error("Interrupted polling for a new buffer.", e);
            continue;
          }
          if (buffer == null) {
            continue;
          }
          
          sendMessage(buffer);
          
          buffer.clear();
          freeBuffers.add(buffer);

        } catch (Throwable t) {
          LOG.error("Unexpected error", t);
        }
      }
    }

  }
}
