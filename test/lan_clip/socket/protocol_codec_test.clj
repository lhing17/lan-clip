(ns lan-clip.socket.protocol-codec-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.protocol-codec :as codec]
            [lan-clip.protocol :as protocol])
  (:import (io.netty.buffer Unpooled ByteBuf)
           (io.netty.channel ChannelHandler)
           (io.netty.channel.embedded EmbeddedChannel)
           (java.util Arrays UUID)
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
