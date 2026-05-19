(ns lan-clip.socket.protocol-codec
  "Netty 编解码器：将 protocol.clj 的二进制消息封装为带 4-byte length prefix 的 TCP frame，
  供 pipeline 替换 ObjectEncoder/ObjectDecoder 使用。"
  (:require [lan-clip.protocol :as protocol]
            [lan-clip.util :as util])
  (:import (io.netty.buffer ByteBuf Unpooled)
           (io.netty.channel ChannelHandlerContext ChannelPromise ChannelOutboundHandlerAdapter)
           (io.netty.handler.codec ByteToMessageDecoder)
           (java.awt Image)
           (java.awt.image BufferedImage)))

(defn encode-frame
  "将原始字节数组包装为 4-byte 大端 length prefix 的 ByteBuf。"
  [^bytes data]
  (let [len (alength data)
        ^ByteBuf buf (Unpooled/buffer (+ 4 len))]
    (.writeInt buf len)
    (.writeBytes buf data)
    buf))

(defn ->protocol-encoder
  "返回一个 Netty outbound handler，将 String 或 Image 消息编码为 protocol frame。"
  [origin-node-id sender-node-id secret-key]
  (proxy [ChannelOutboundHandlerAdapter] []
    (write [^ChannelHandlerContext ctx msg ^ChannelPromise promise]
      (cond
        (string? msg)
        (let [frame (protocol/encode-text-message msg origin-node-id sender-node-id secret-key)]
          (.write ctx (encode-frame frame) promise))

        (instance? Image msg)
        (let [^BufferedImage bi (if (instance? BufferedImage msg) msg (util/buffered-image msg))
              png-bytes (util/image->bytes bi)
              frame (protocol/encode-image-message png-bytes origin-node-id sender-node-id secret-key)]
          (.write ctx (encode-frame frame) promise))

        :else
        (.write ctx msg promise)))))

(defn ->protocol-decoder
  "返回一个 Netty inbound handler，从 length-prefixed stream 中解码 protocol Message。"
  [secret-key]
  (proxy [ByteToMessageDecoder] []
    (decode [^ChannelHandlerContext ctx ^ByteBuf in out]
      (when (>= (.readableBytes in) 4)
        (.markReaderIndex in)
        (let [len (.readInt in)]
          (if (< (.readableBytes in) len)
            (.resetReaderIndex in)
            (let [frame (byte-array len)]
              (.readBytes in frame)
              (.add out (protocol/decode-message frame secret-key)))))))))
