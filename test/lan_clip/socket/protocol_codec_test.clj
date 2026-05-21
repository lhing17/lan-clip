(ns lan-clip.socket.protocol-codec-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.protocol-codec :as codec]
            [lan-clip.protocol :as protocol])
  (:import (io.netty.buffer Unpooled ByteBuf)
           (io.netty.channel ChannelHandler ChannelInboundHandlerAdapter)
           (io.netty.channel.embedded EmbeddedChannel)
           (java.awt.image BufferedImage)
           (java.io File)
           (java.util Arrays Collections UUID)
           (lan_clip.protocol Message)))

(def ^:private test-secret "test-secret-key")
(def ^:private test-origin (UUID/fromString "11111111-1111-1111-1111-111111111111"))
(def ^:private test-sender (UUID/fromString "22222222-2222-2222-2222-222222222222"))

(deftest encode-frame-wraps-with-length-prefix
  (testing "encode-frame 应在数据前添加 4 字节大端 length prefix"
    (let [data (byte-array [0x01 0x02 0x03])
          ^ByteBuf buf (codec/encode-frame data)]
      (is (= 3 (.readInt buf)) "length prefix 应等于数据长度")
      (is (Arrays/equals data (let [b (byte-array 3)] (.readBytes buf b) b)))
      (is (= 0 (.readableBytes buf)) "应无剩余可读字节")
      (.release buf))))

(deftest protocol-encoder-produces-valid-frame
  (testing "->protocol-encoder 应将 String 编码为带 length prefix 的 protocol frame"
    (let [ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder test-origin test-sender test-secret)]))]
      (.writeOutbound ch (into-array Object ["hello codec"]))
      (let [^ByteBuf buf (.readOutbound ch)]
        (is (instance? ByteBuf buf))
        (let [frame-len (.readInt buf)
              frame-bytes (byte-array frame-len)]
          (.readBytes buf frame-bytes)
          (is (= 0 (.readableBytes buf)))
          (let [msg (protocol/decode-message frame-bytes test-secret)]
            (is (= :text (:content-type msg)))
            (is (= "hello codec" (String. ^bytes (:payload msg) "UTF-8")))
            (is (= test-origin (:origin-node-id msg)))
            (is (= test-sender (:sender-node-id msg)))))
        (.release buf))
      (.finish ch))))

(deftest protocol-decoder-reads-valid-frame
  (testing "->protocol-decoder 应能从 length-prefixed frame 解码出 Message"
    (let [frame (protocol/encode-text-message "decoder test" test-origin test-sender test-secret)
          ^ByteBuf buf (codec/encode-frame frame)
          ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-decoder test-secret)]))]
      (.writeInbound ch (into-array Object [buf]))
      (let [msg (.readInbound ch)]
        (is (instance? Message msg))
        (is (= :text (:content-type msg)))
        (is (= "decoder test" (String. ^bytes (:payload msg) "UTF-8")))
        (is (= test-origin (:origin-node-id msg)))
        (is (= test-sender (:sender-node-id msg))))
      (.finish ch))))

(deftest protocol-decoder-accumulates-partial-frames
  (testing "->protocol-decoder 应在数据不足时累积，等到完整 frame 后再输出"
    (let [frame (protocol/encode-text-message "partial" test-origin test-sender test-secret)
          ^ByteBuf full-buf (codec/encode-frame frame)
          total-len (.readableBytes full-buf)
          half-len (quot total-len 2)
          first-half (.readSlice full-buf half-len)
          second-half (.readSlice full-buf (- total-len half-len))
          ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-decoder test-secret)]))]
      (.retain first-half)
      (.retain second-half)
      (.writeInbound ch (into-array Object [first-half]))
      (is (nil? (.readInbound ch)) "半包不应输出")
      (.writeInbound ch (into-array Object [second-half]))
      (let [msg (.readInbound ch)]
        (is (instance? Message msg))
        (is (= "partial" (String. ^bytes (:payload msg) "UTF-8"))))
      (.release full-buf)
      (.finish ch))))

(deftest protocol-decoder-rejects-bad-hmac
  (testing "->protocol-decoder 应拒绝 HMAC 错误的 frame"
    (let [frame (protocol/encode-text-message "tampered" test-origin test-sender test-secret)
          _ (aset frame (dec (count frame)) (unchecked-byte 0xFF))
          ^ByteBuf buf (codec/encode-frame frame)
          ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-decoder test-secret)]))]
      (is (thrown? Exception (.writeInbound ch (into-array Object [buf]))))
      (.finish ch))))

(deftest protocol-encoder-handles-image
  (testing "->protocol-encoder 应将 BufferedImage 编码为 image protocol frame"
    (let [img (BufferedImage. 2 2 BufferedImage/TYPE_INT_ARGB)
          ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder test-origin test-sender test-secret)]))]
      (.writeOutbound ch (into-array Object [img]))
      (let [^ByteBuf buf (.readOutbound ch)]
        (try
          (is (instance? ByteBuf buf))
          (let [frame-len (.readInt buf)
                frame-bytes (byte-array frame-len)]
            (.readBytes buf frame-bytes)
            (is (= 0 (.readableBytes buf)))
            (let [msg (protocol/decode-message frame-bytes test-secret)]
              (is (= :image (:content-type msg)))
              (is (> (count (:payload msg)) 0) "payload 应非空 PNG 字节")))
          (finally
            (.release buf)
            (.finish ch)))))))

(deftest protocol-encoder-handles-file-list
  (testing "->protocol-encoder 应将文件列表编码为 file-list protocol frame"
    (let [temp-file (doto (File/createTempFile "test" ".txt")
                      (.deleteOnExit))
          _ (spit temp-file "file list test content")
          files (Collections/singletonList temp-file)
          ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder test-origin test-sender test-secret)]))]
      (.writeOutbound ch (into-array Object [files]))
      (let [^ByteBuf buf (.readOutbound ch)]
        (try
          (is (instance? ByteBuf buf))
          (let [frame-len (.readInt buf)
                frame-bytes (byte-array frame-len)]
            (.readBytes buf frame-bytes)
            (is (= 0 (.readableBytes buf)))
            (let [msg (protocol/decode-message frame-bytes test-secret)]
              (is (= :file-list (:content-type msg)))
              (is (> (count (:payload msg)) 0) "payload 应非空 zip 字节")))
          (finally
            (.release buf)
            (.finish ch)))))))

(deftest protocol-encoder-file-list-includes-metadata
  (testing "->protocol-encoder 文件列表应包含文件名、大小、hash 元数据"
    (let [temp-file (doto (File/createTempFile "meta" ".txt")
                      (.deleteOnExit))
          content "metadata test content"
          _ (spit temp-file content)
          expected-hash (org.apache.commons.codec.digest.DigestUtils/md5Hex content)
          files (Collections/singletonList temp-file)
          ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder test-origin test-sender test-secret)]))]
      (.writeOutbound ch (into-array Object [files]))
      (let [^ByteBuf buf (.readOutbound ch)]
        (try
          (is (instance? ByteBuf buf))
          (let [frame-len (.readInt buf)
                frame-bytes (byte-array frame-len)]
            (.readBytes buf frame-bytes)
            (let [msg (protocol/decode-message frame-bytes test-secret)
                  metadata (clojure.edn/read-string (:metadata msg))
                  file-meta (first (:files metadata))]
              (is (= :file-list (:content-type msg)))
              (is (= (.getName temp-file) (:name file-meta)) "metadata 应包含文件名")
              (is (= (.length temp-file) (:size file-meta)) "metadata 应包含文件大小")
              (is (= expected-hash (:hash file-meta)) "metadata 应包含文件 hash")))
          (finally
            (.release buf)
            (.finish ch)))))))

(deftest protocol-decoder-rejects-oversized-frame
  (testing "->protocol-decoder 应拒绝超过 max-frame-size 的 frame"
    (let [max-size 1024
          oversized-len (+ max-size 1)
          ^ByteBuf buf (Unpooled/buffer)
          caught (atom nil)]
      (.writeInt buf oversized-len)
      (.writeBytes buf (byte-array oversized-len))
      (let [ch (EmbeddedChannel. (into-array ChannelHandler
                                             [(codec/->protocol-decoder test-secret max-size)
                                              (proxy [ChannelInboundHandlerAdapter] []
                                                (exceptionCaught [_ cause]
                                                  (reset! caught cause)))]))]
        (.writeInbound ch (into-array Object [buf]))
        (is (some? @caught) "decoder 应在检测到超大 frame 后触发 exceptionCaught")
        (is (instance? io.netty.handler.codec.DecoderException @caught))
        (.finish ch)))))

(deftest protocol-decoder-rejects-negative-length
  (testing "->protocol-decoder 应拒绝负的或零的 length prefix"
    (doseq [bad-len [-1 0]]
      (let [^ByteBuf buf (Unpooled/buffer)
            caught (atom nil)]
        (.writeInt buf bad-len)
        (.writeBytes buf (byte-array 1))
        (let [ch (EmbeddedChannel. (into-array ChannelHandler
                                               [(codec/->protocol-decoder test-secret 10485760)
                                                (proxy [ChannelInboundHandlerAdapter] []
                                                  (exceptionCaught [_ cause]
                                                    (reset! caught cause)))]))]
          (.writeInbound ch (into-array Object [buf]))
          (is (some? @caught) (str "length=" bad-len " 应触发 exceptionCaught"))
          (is (instance? io.netty.handler.codec.DecoderException @caught))
          (.finish ch))))))

(deftest roundtrip-encode-decode
  (testing "encoder 输出应能被 decoder 正确解码"
    (let [encoder-ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder test-origin test-sender test-secret)]))
          decoder-ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-decoder test-secret)]))]
      (.writeOutbound encoder-ch (into-array Object ["roundtrip text"]))
      (let [^ByteBuf encoded (.readOutbound encoder-ch)]
        (.writeInbound decoder-ch (into-array Object [encoded]))
        (let [msg (.readInbound decoder-ch)]
          (is (instance? Message msg))
          (is (= :text (:content-type msg)))
          (is (= "roundtrip text" (String. ^bytes (:payload msg) "UTF-8")))
          (is (= test-origin (:origin-node-id msg)))
          (is (= test-sender (:sender-node-id msg)))))
      (.finish encoder-ch)
      (.finish decoder-ch))))
